package com.example.httpserver;

import fi.iki.elonen.NanoHTTPD;
import android.content.Context;
import android.database.sqlite.*;
import android.database.Cursor;

import java.io.*;
import java.util.*;

public class MyServer extends NanoHTTPD {

    private Context context;

    public MyServer(Context context) {
        super(5000);
        this.context = context;
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

                double pv = get(body,"pv");
                double load = get(body,"load");
                double soc = get(body,"soc");

                SQLiteDatabase db = context.openOrCreateDatabase("solar.db", Context.MODE_PRIVATE, null);

                db.execSQL("CREATE TABLE IF NOT EXISTS data(ts INTEGER,pv REAL,load REAL,soc REAL)");

                db.execSQL("INSERT INTO data VALUES(?,?,?,?)",
                        new Object[]{
                                System.currentTimeMillis()/1000,
                                pv, load, soc
                        });

                db.close();

                return newFixedLengthResponse("OK");
            }

            if (Method.GET.equals(session.getMethod()) &&
                "/api/history".equals(session.getUri())) {

                Map<String,String> p = session.getParms();

                long from = Long.parseLong(p.get("from"));
                long to   = Long.parseLong(p.get("to"));

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

    private double get(String j,String k){
        try{
            String s="\""+k+"\"";
            int i=j.indexOf(s);
            int c=j.indexOf(":",i);
            int e=j.indexOf(",",c);
            if(e==-1) e=j.indexOf("}",c);
            return Double.parseDouble(j.substring(c+1,e).trim());
        }catch(Exception ex){return 0;}
    }
}
