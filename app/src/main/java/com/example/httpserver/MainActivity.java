package com.example.httpserver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import fi.iki.elonen.NanoHTTPD;

import android.database.sqlite.*;
import android.database.Cursor;
import android.content.*;

import java.io.InputStream;
import java.io.IOException;
import java.util.*;

public class MainActivity extends Activity {

    private MyServer server;
    private DBHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("Server running on port 5000");
        setContentView(tv);

        dbHelper = new DBHelper(this);

        server = new MyServer();
        try { server.start(); } catch (IOException e) { e.printStackTrace(); }
    }

    // ================= DATABASE =================
    static class DBHelper extends SQLiteOpenHelper {
        public DBHelper(Context c){ super(c,"solar.db",null,1); }

        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE data(ts INTEGER,pv REAL,load REAL,soc REAL)");
        }

        public void onUpgrade(SQLiteDatabase db,int o,int n){}
    }

    // ================= SERVER =================
    class MyServer extends NanoHTTPD {
        public MyServer(){ super(5000); }

        public Response serve(IHTTPSession session){
            try{

                if(Method.OPTIONS.equals(session.getMethod())){
                    Response r = newFixedLengthResponse("");
                    addCors(r);
                    return r;
                }

                // ===== HTML (ГОЛОВНА СТОРІНКА) =====
                if(Method.GET.equals(session.getMethod()) &&
                   "/".equals(session.getUri())){

                    try {
                        InputStream is = getAssets().open("graph.html");
                        Scanner s = new Scanner(is).useDelimiter("\\A");
                        String html = s.hasNext() ? s.next() : "";

                        Response r = newFixedLengthResponse(Response.Status.OK, "text/html", html);
                        addCors(r);
                        return r;

                    } catch (Exception e) {
                        return newFixedLengthResponse("ERR loading HTML");
                    }
                }

                // ===== POST =====
                if(Method.POST.equals(session.getMethod()) &&
                   "/api/telemetry".equals(session.getUri())){

                    Map<String,String> files=new HashMap<>();
                    session.parseBody(files);

                    String body=files.get("postData");
                    if(body==null) body=session.getQueryParameterString();

                    double pv=get(body,"pv");
                    double load=get(body,"load");
                    double soc=get(body,"soc");

                    SQLiteDatabase db=dbHelper.getWritableDatabase();

                    ContentValues v=new ContentValues();
                    v.put("ts",System.currentTimeMillis()/1000);
                    v.put("pv",pv);
                    v.put("load",load);
                    v.put("soc",soc);

                    db.insert("data",null,v);
                    db.close();

                    Response r = newFixedLengthResponse("OK");
                    addCors(r);
                    return r;
                }

                // ===== GET HISTORY =====
                if(Method.GET.equals(session.getMethod()) &&
                   "/api/history".equals(session.getUri())){

                    Map<String,String> p = session.getParms();

                    long from = Long.parseLong(p.get("from"));
                    long to   = Long.parseLong(p.get("to"));

                    SQLiteDatabase db=dbHelper.getReadableDatabase();

                    Cursor c=db.rawQuery(
                        "SELECT * FROM data WHERE ts BETWEEN ? AND ? ORDER BY ts",
                        new String[]{String.valueOf(from), String.valueOf(to)}
                    );

                    StringBuilder json=new StringBuilder();
                    json.append("[");

                    boolean first=true;

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

                    Response r = newFixedLengthResponse(json.toString());
                    addCors(r);
                    return r;
                }

                Response r = newFixedLengthResponse("OK");
                addCors(r);
                return r;

            }catch(Exception e){
                Response r = newFixedLengthResponse("ERR "+e.getMessage());
                addCors(r);
                return r;
            }
        }

        private double get(String j,String k){
            try{
                String s="\""+k+"\"";
                int i=j.indexOf(s);
                if(i==-1) return 0;

                int c=j.indexOf(":",i);
                int e=j.indexOf(",",c);
                if(e==-1) e=j.indexOf("}",c);

                return Double.parseDouble(j.substring(c+1,e).trim());
            }catch(Exception ex){return 0;}
        }

        private void addCors(Response r){
            r.addHeader("Access-Control-Allow-Origin","*");
            r.addHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
            r.addHeader("Access-Control-Allow-Headers","*");
        }
    }
}
