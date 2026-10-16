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
            // V2 records are stored separately; the legacy data table is unchanged.
            // JSON retains all ESP32 fields without a destructive schema migration.
            db.execSQL("CREATE TABLE IF NOT EXISTS telemetry_v2(ts INTEGER NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_telemetry_v2_ts ON telemetry_v2(ts)");
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

            if (Method.GET.equals(session.getMethod()) &&
                "/scheme".equals(session.getUri())) {
                InputStream is = context.getAssets().open("scheme.html");
                try {
                    Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
                    String html = s.hasNext() ? s.next() : "";
                    return newFixedLengthResponse(Response.Status.OK,
                            "text/html; charset=utf-8", html);
                } finally {
                    is.close();
                }
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/modern".equals(session.getUri())) {
                InputStream is = context.getAssets().open("modern.html");
                try {
                    Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
                    String html = s.hasNext() ? s.next() : "";
                    return newFixedLengthResponse(Response.Status.OK,
                            "text/html; charset=utf-8", html);
                } finally {
                    is.close();
                }
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/dashboard".equals(session.getUri())) {
                InputStream is = context.getAssets().open("dashboard.html");
                try {
                    Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
                    String html = s.hasNext() ? s.next() : "";
                    return newFixedLengthResponse(Response.Status.OK,
                            "text/html; charset=utf-8", html);
                } finally {
                    is.close();
                }
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
                final JSONObject telemetry;
                final boolean isV2;
                try {
                    telemetry = new JSONObject(body);
                    pv = requiredNumber(telemetry, "pv");
                    load = requiredNumber(telemetry, "load");
                    soc = requiredNumber(telemetry, "soc");
                    // Version is opt-in; existing ESP32 firmware sends no version.
                    if (telemetry.has("version")) {
                        Object version = telemetry.get("version");
                        if (!(version instanceof Number) ||
                                ((Number) version).intValue() != 2 ||
                                ((Number) version).doubleValue() != 2.0) {
                            throw new JSONException("Unsupported telemetry version");
                        }
                        isV2 = true;
                    } else {
                        isV2 = false;
                    }
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
                    db.beginTransaction();
                    try {
                        long timestamp = System.currentTimeMillis() / 1000;
                        // Every POST remains visible to the original history graph.
                        db.execSQL("INSERT INTO data VALUES(?,?,?,?)",
                                new Object[]{timestamp, pv, load, soc});
                        // V2 keeps the complete payload for future dashboards and BMS metrics.
                        // Old (unversioned) POSTs do not grow the V2 table.
                        if (isV2) {
                            db.execSQL("INSERT INTO telemetry_v2(ts,payload) VALUES(?,?)",
                                    new Object[]{timestamp, telemetry.toString()});
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                } finally {
                    db.close();
                }

                return newFixedLengthResponse("OK");
            }

            // STEP 09: preflight only. No request path can dispatch inverter commands.
            if (Method.POST.equals(session.getMethod()) &&
                "/api/control/prepare".equals(session.getUri())) {
                return prepareControl(session);
            }

            // Explicitly deny direct mode changes until authenticated execution exists.
            if ("/api/control/mode".equals(session.getUri())) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN,
                        "application/json; charset=utf-8",
                        "{\"ok\":false,\"reason\":\"commands_disabled\"}");
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/api/control/status".equals(session.getUri())) {
                return controlStatus();
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/api/telemetry/status".equals(session.getUri())) {
                return telemetryStatus();
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

    // Validate a candidate request without forwarding or storing it.
    // The result is informational, not authorization to operate the inverter.
    private Response prepareControl(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            String body = files.get("postData");
            if (body == null || body.length() > 1024) {
                return controlPreflightError(Response.Status.BAD_REQUEST, "invalid_body");
            }
            JSONObject request = new JSONObject(body);
            Object proposed = request.opt("mode");
            if (!(proposed instanceof Number) ||
                    ((Number) proposed).doubleValue() != ((Number) proposed).intValue() ||
                    ((Number) proposed).intValue() < 0 ||
                    ((Number) proposed).intValue() > 2) {
                return controlPreflightError(Response.Status.BAD_REQUEST, "invalid_mode");
            }
            int target = ((Number) proposed).intValue();
            SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
            Cursor c = null;
            try {
                c = db.rawQuery(
                        "SELECT ts,payload FROM telemetry_v2 ORDER BY rowid DESC LIMIT 1",
                        null);
                if (!c.moveToFirst()) {
                    return controlPreflightError(Response.Status.SERVICE_UNAVAILABLE,
                            "no_telemetry");
                }
                long ts = c.getLong(0);
                long now = System.currentTimeMillis() / 1000;
                if (ts > now + 30 || now - ts > 60) {
                    return controlPreflightError(Response.Status.SERVICE_UNAVAILABLE,
                            "stale_telemetry");
                }
                JSONObject inv = new JSONObject(c.getString(1)).optJSONObject("inverter");
                Object current = inv == null ? null : inv.opt("control_mode");
                if (!(current instanceof Number) ||
                        ((Number) current).doubleValue() != ((Number) current).intValue() ||
                        ((Number) current).intValue() < 0 ||
                        ((Number) current).intValue() > 2) {
                    return controlPreflightError(Response.Status.SERVICE_UNAVAILABLE,
                            "unknown_current_mode");
                }
                JSONObject reply = new JSONObject();
                reply.put("ok", true);
                reply.put("dry_run", true);
                reply.put("commands_enabled", false);
                reply.put("requested_mode", target);
                reply.put("current_mode", ((Number) current).intValue());
                reply.put("change_required", target != ((Number) current).intValue());
                reply.put("reason", "preflight_only");
                Response response = newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=utf-8", reply.toString());
                response.addHeader("Cache-Control", "no-store");
                return response;
            } finally {
                if (c != null) c.close();
                db.close();
            }
        } catch (Exception e) {
            return controlPreflightError(Response.Status.BAD_REQUEST, "invalid_request");
        }
    }

    private Response controlPreflightError(Response.Status code, String reason) {
        return newFixedLengthResponse(code, "application/json; charset=utf-8",
                "{\"ok\":false,\"dry_run\":true,\"commands_enabled\":false,\"reason\":\""
                        + reason + "\"}");
    }

    // STEP 08: read-only control readiness. Never sends commands to ESP32.
    // Only freshly received V2 telemetry can serve as current state.
    private Response controlStatus() {
        SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
        Cursor c = null;
        try {
            JSONObject result = new JSONObject();
            result.put("commands_enabled", false);
            result.put("source", "telemetry_v2");
            c = db.rawQuery(
                    "SELECT ts,payload FROM telemetry_v2 ORDER BY rowid DESC LIMIT 1",
                    null);
            if (!c.moveToFirst()) {
                result.put("online", false);
                result.put("reason", "no_telemetry");
                result.put("control_mode", JSONObject.NULL);
            } else {
                long ts = c.getLong(0);
                long age = Math.max(0, System.currentTimeMillis() / 1000L - ts);
                JSONObject payload = new JSONObject(c.getString(1));
                JSONObject inverter = payload.optJSONObject("inverter");
                Object mode = inverter == null ? null : inverter.opt("control_mode");
                boolean recognized = mode instanceof Number &&
                        ((Number) mode).doubleValue() == ((Number) mode).intValue() &&
                        ((Number) mode).intValue() >= 0 &&
                        ((Number) mode).intValue() <= 2;
                result.put("last_timestamp", ts);
                result.put("age_seconds", age);
                result.put("online", age <= 90);
                result.put("control_mode", recognized ? mode : JSONObject.NULL);
                result.put("mode_name", recognized ?
                        new String[]{"AUTO", "PU", "PBG"}[((Number) mode).intValue()] :
                        JSONObject.NULL);
                result.put("reason", age > 90 ? "stale_telemetry" :
                        recognized ? "read_only" : "unknown_mode");
            }
            Response response = newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    result.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Invalid stored telemetry");
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    // Read-only diagnostic endpoint; never alters telemetry or the history table.
    private Response telemetryStatus() {
        SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
        Cursor cursor = null;
        try {
            JSONObject status = new JSONObject();
            long count = android.database.DatabaseUtils.longForQuery(
                    db, "SELECT COUNT(*) FROM telemetry_v2", null);
            status.put("v2_records", count);
            cursor = db.rawQuery(
                    "SELECT ts,payload FROM telemetry_v2 ORDER BY rowid DESC LIMIT 1",
                    null);
            if (cursor.moveToFirst()) {
                status.put("last_timestamp", cursor.getLong(0));
                status.put("last_payload", new JSONObject(cursor.getString(1)));
            } else {
                status.put("last_timestamp", JSONObject.NULL);
                status.put("last_payload", JSONObject.NULL);
            }
            Response response = newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    status.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Invalid stored V2 JSON");
        } finally {
            if (cursor != null) cursor.close();
            db.close();
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
