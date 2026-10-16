package com.example.httpserver;

import fi.iki.elonen.NanoHTTPD;
import android.content.Context;
import android.database.sqlite.*;
import android.database.Cursor;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

public class MyServer extends NanoHTTPD {

    private Context context;

    public MyServer(Context context) {
        super(5000);
        this.context = context;

        SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS data(ts INTEGER,pv REAL,load REAL,soc REAL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_ts ON data(ts)");
        } finally {
            db.close();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {

        try {

            if (Method.GET.equals(session.getMethod()) &&
                "/".equals(session.getUri())) {

                InputStream is = context.getAssets().open("graph.html");
                Scanner s = new Scanner(is).useDelimiter("\\A");
                String html = s.hasNext() ? s.next() : "";

                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            }

            if (Method.POST.equals(session.getMethod()) &&
                "/api/telemetry".equals(session.getUri())) {

                Map<String,String> files = new HashMap<>();
                session.parseBody(files);

                String body = files.get("postData");
                if (body == null || body.trim().isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                            "text/plain", "Missing telemetry JSON");
                }

                // The existing ESP32 firmware sends numeric pv/load/soc fields.
                // Accept additional fields so future firmware can extend the payload.
                final double pv;
                final double load;
                final double soc;
                try {
                    JSONObject telemetry = new JSONObject(body);
                    pv = requiredNumber(telemetry, "pv");
                    load = requiredNumber(telemetry, "load");
                    soc = requiredNumber(telemetry, "soc");
                } catch (JSONException e) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                            "text/plain", "Invalid telemetry JSON");
                }

                if (pv < 0 || load < 0 || soc < 0 || soc > 100) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                            "text/plain", "Telemetry values out of range");
                }

                SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
                try {
                    // Preserve the existing database schema and legacy history API.
                    db.execSQL("INSERT INTO data VALUES(?,?,?,?)",
                            new Object[]{
                                    System.currentTimeMillis()/1000,
                                    pv, load, soc
                            });
                } finally {
                    db.close();
                }

                return newFixedLengthResponse("OK");
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/api/history".equals(session.getUri())) {

                Map<String,String> p = session.getParms();

                long from = Long.parseLong(p.get("from"));
                long to   = Long.parseLong(p.get("to"));

                // Optional aggregation; requests without "step" keep the legacy raw response.
                String stepValue = p.get("step");
                if (stepValue != null) {
                    int step;
                    try {
                        step = Integer.parseInt(stepValue);
                    } catch (NumberFormatException e) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                                "text/plain", "Invalid step");
                    }
                    return aggregatedHistory(from, to, step);
                }

                SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);

                Cursor c = db.rawQuery(
                        "SELECT * FROM data WHERE ts BETWEEN ? AND ? ORDER BY ts",
                        new String[]{String.valueOf(from), String.valueOf(to)}
                );

                StringBuilder json = new StringBuilder();
                json.append("[");

                boolean first = true;

                while(c.moveToNext()){
                    if(!first) json.append(",");
                    first=false;

                    json.append("{")
                            .append("\"ts\":").append(c.getLong(0)).append(",")
                            .append("\"pv\":").append(c.getDouble(1)).append(",")
                            .append("\"load\":").append(c.getDouble(2)).append(",")
                            .append("\"soc\":").append(c.getDouble(3))
                            .append("}");
                }

                json.append("]");

                c.close();
                db.close();

                return newFixedLengthResponse(json.toString());
            }

            return newFixedLengthResponse("OK");

        } catch (Exception e) {
            return newFixedLengthResponse("ERR");
        }
    }

    // Return one point per interval, including zeros for intervals without measurements.
    // Buckets are aligned to "from", matching the previous browser implementation.
    private Response aggregatedHistory(long from, long to, int step) {
        if (from < 0 || to < from || step <= 0 || (to - from) / step >= 10000) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    "text/plain", "Invalid history range or step");
        }

        int count = (int) ((to - from) / step + 1);
        SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT ((ts - ?) / ?) AS bucket, " +
                    "AVG(pv), AVG(load), AVG(soc) " +
                    "FROM data WHERE ts BETWEEN ? AND ? " +
                    "GROUP BY bucket ORDER BY bucket",
                    new String[]{String.valueOf(from), String.valueOf(step),
                            String.valueOf(from), String.valueOf(to)}
            );

            StringBuilder json = new StringBuilder(count * 60);
            json.append("[");
            boolean hasRow = c.moveToFirst();

            for (int i = 0; i < count; i++) {
                double pv = 0;
                double load = 0;
                double soc = 0;

                if (hasRow && c.getLong(0) == i) {
                    pv = c.getDouble(1);
                    load = c.getDouble(2);
                    soc = c.getDouble(3);
                    hasRow = c.moveToNext();
                }

                if (i > 0) json.append(",");
                json.append("{")
                        .append("\"ts\":").append(from + (long) i * step).append(",")
                        .append("\"pv\":").append(pv).append(",")
                        .append("\"load\":").append(load).append(",")
                        .append("\"soc\":").append(soc)
                        .append("}");
            }

            json.append("]");
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    // Do not silently store zeros for missing, null, quoted or invalid numbers.
    private static double requiredNumber(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number)) {
            throw new JSONException("Telemetry field is not numeric: " + key);
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new JSONException("Telemetry field is not finite: " + key);
        }
        return number;
    }
}
