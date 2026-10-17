package com.example.httpserver;

import fi.iki.elonen.NanoHTTPD;
import android.content.Context;
import android.database.sqlite.*;
import android.database.Cursor;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyServer extends NanoHTTPD {

    private Context context;
    private volatile String latestPayload;
    private volatile long latestTimestamp;
    private volatile long latestReceivedElapsedMs;
    private final Object sampleLock = new Object();
    private final ExecutorService historyWriter = Executors.newSingleThreadExecutor();
    private long windowStartMs = 0;
    private double totalPv = 0, totalLoad = 0, totalSoc = 0;
    private int sampleCount = 0;
    private long lastCleanupMs = 0;
    private static final long HISTORY_WINDOW_MS = 20000L;
    private static final long CLEANUP_INTERVAL_MS = 86400000L;

    public MyServer(Context context) {
        super(5000);
        this.context = context;

        SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS data(ts INTEGER,pv REAL,load REAL,soc REAL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_ts ON data(ts)");
            // Full V2 JSON is retained in RAM only; legacy compact history survives.
            db.execSQL("DROP TABLE IF EXISTS telemetry_v2");
            db.execSQL("DELETE FROM data WHERE ts < CAST(strftime('%s','now','-6 months') AS INTEGER)");
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

                final long nowMs = System.currentTimeMillis();
                latestPayload = isV2 ? telemetry.toString() : null;
                latestTimestamp = nowMs / 1000L;
                latestReceivedElapsedMs = android.os.SystemClock.elapsedRealtime();
                synchronized (sampleLock) {
                    if (windowStartMs == 0) windowStartMs = nowMs;
                    totalPv += pv; totalLoad += load; totalSoc += soc;
                    sampleCount++;
                    if (nowMs - windowStartMs >= HISTORY_WINDOW_MS) {
                        final long ts = nowMs / 1000L;
                        final double avgPv = totalPv / sampleCount;
                        final double avgLoad = totalLoad / sampleCount;
                        final double avgSoc = totalSoc / sampleCount;
                        final boolean cleanup = nowMs - lastCleanupMs >= CLEANUP_INTERVAL_MS;
                        if (cleanup) lastCleanupMs = nowMs;
                        sampleCount = 0;
                        totalPv = totalLoad = totalSoc = 0;
                        windowStartMs = nowMs;
                        historyWriter.execute(() -> saveCompactHistory(ts, avgPv, avgLoad, avgSoc, cleanup));
                    }
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

    // Control stays disabled; preflight uses the current live snapshot.
    private Response prepareControl(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            JSONObject request = new JSONObject(files.get("postData"));
            Object proposed = request.opt("mode");
            if (!(proposed instanceof Number) ||
                ((Number) proposed).intValue() != ((Number) proposed).doubleValue() ||
                ((Number) proposed).intValue() < 0 || ((Number) proposed).intValue() > 2)
                return controlPreflightError(Response.Status.BAD_REQUEST, "invalid_mode");
            String snapshot = latestPayload;
            long age = System.currentTimeMillis() / 1000L - latestTimestamp;
            if (snapshot == null || age > 60 || age < -30)
                return controlPreflightError(Response.Status.SERVICE_UNAVAILABLE, "stale_telemetry");
            JSONObject inv = new JSONObject(snapshot).optJSONObject("inverter");
            Object current = inv == null ? null : inv.opt("control_mode");
            if (!(current instanceof Number) ||
                ((Number) current).intValue() != ((Number) current).doubleValue() ||
                ((Number) current).intValue() < 0 || ((Number) current).intValue() > 2)
                return controlPreflightError(Response.Status.SERVICE_UNAVAILABLE, "unknown_current_mode");
            JSONObject reply = new JSONObject();
            reply.put("ok", true); reply.put("dry_run", true); reply.put("commands_enabled", false);
            reply.put("requested_mode", proposed); reply.put("current_mode", current);
            reply.put("change_required", ((Number) proposed).intValue() != ((Number) current).intValue());
            reply.put("reason", "preflight_only");
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", reply.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (Exception e) {
            return controlPreflightError(Response.Status.BAD_REQUEST, "invalid_request");
        }
    }

    private Response controlPreflightError(Response.Status code, String reason) {
        return newFixedLengthResponse(code, "application/json; charset=utf-8",
                "{\"ok\":false,\"dry_run\":true,\"commands_enabled\":false,\"reason\":\""
                        + reason + "\"}");
    }

    private Response controlStatus() {
        try {
            JSONObject result = new JSONObject();
            result.put("commands_enabled", false); result.put("source", "live_memory");
            String snapshot = latestPayload;
            long ts = latestTimestamp;
            long age = ts == 0 ? Long.MAX_VALUE : Math.max(0, System.currentTimeMillis() / 1000L - ts);
            JSONObject inv = snapshot == null ? null : new JSONObject(snapshot).optJSONObject("inverter");
            Object mode = inv == null ? null : inv.opt("control_mode");
            boolean recognized = mode instanceof Number &&
                ((Number) mode).doubleValue() == ((Number) mode).intValue() &&
                ((Number) mode).intValue() >= 0 && ((Number) mode).intValue() <= 2;
            result.put("online", snapshot != null && age <= 90);
            result.put("last_timestamp", ts == 0 ? JSONObject.NULL : ts);
            result.put("age_seconds", ts == 0 ? JSONObject.NULL : age);
            result.put("control_mode", recognized ? mode : JSONObject.NULL);
            result.put("mode_name", recognized ?
                new String[]{"AUTO", "PU", "PBG"}[((Number) mode).intValue()] : JSONObject.NULL);
            result.put("reason", snapshot == null ? "no_telemetry" :
                age > 90 ? "stale_telemetry" : recognized ? "read_only" : "unknown_mode");
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Invalid live telemetry");
        }
    }

    private Response telemetryStatus() {
        try {
            JSONObject status = new JSONObject();
            String snapshot = latestPayload;
            status.put("v2_records", 0);
            status.put("last_timestamp", snapshot == null ? JSONObject.NULL : latestTimestamp);
            status.put("age_seconds", snapshot == null ? JSONObject.NULL : Math.max(0L, (android.os.SystemClock.elapsedRealtime() - latestReceivedElapsedMs) / 1000L));
            status.put("last_payload", snapshot == null ? JSONObject.NULL : new JSONObject(snapshot));
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", status.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Invalid live JSON");
        }
    }

    private void saveCompactHistory(long ts, double pv, double load, double soc, boolean cleanup) {
        SQLiteDatabase db = null;
        try {
            db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);
            db.execSQL("INSERT INTO data(ts,pv,load,soc) VALUES(?,?,?,?)", new Object[]{ts, pv, load, soc});
            if (cleanup) db.execSQL("DELETE FROM data WHERE ts < CAST(strftime('%s','now','-6 months') AS INTEGER)");
        } catch (Exception e) {
            android.util.Log.e("SolarHistory", "History write failed", e);
        } finally {
            if (db != null) db.close();
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
