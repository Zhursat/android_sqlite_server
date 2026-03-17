package com.example.httpserver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.view.View;
import android.graphics.*;
import android.os.Handler;

import fi.iki.elonen.NanoHTTPD;

import android.database.sqlite.*;
import android.database.Cursor;
import android.content.*;

import java.io.*;
import java.util.*;
import android.os.StatFs;

public class MainActivity extends Activity {

    private MyServer server;
    private DBHelper dbHelper;

    private TextView tv;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dbHelper = new DBHelper(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        tv = new TextView(this);
        tv.setTextSize(16);

        Button btnClear = new Button(this);
        btnClear.setText("Очистити БД");

        Button btnExport = new Button(this);
        btnExport.setText("Експорт CSV");

        MiniGraphView graph = new MiniGraphView(this);

        layout.addView(tv);
        layout.addView(btnClear);
        layout.addView(btnExport);
        layout.addView(graph);

        setContentView(layout);

        // сервер
        new Thread(() -> {
            try {
                server = new MyServer();
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // кнопка очистки
        btnClear.setOnClickListener(v -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.execSQL("DELETE FROM data");
            db.close();
            updateInfo();
            graph.invalidate();
        });

        // кнопка експорту
        btnExport.setOnClickListener(v -> exportCSV());

        // автооновлення кожні 10 сек
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateInfo();
                graph.invalidate();
                handler.postDelayed(this, 10000);
            }
        }, 1000);
    }

    // ===== ІНФО =====
    private void updateInfo() {
        try {
            File dbFile = getDatabasePath("solar.db");
            long dbSize = dbFile.exists() ? dbFile.length() : 0;

            StatFs stat = new StatFs(getFilesDir().getPath());
            long free = stat.getAvailableBytes();
            long total = stat.getTotalBytes();

            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM data", null);

            int count = 0;
            if (c.moveToFirst()) count = c.getInt(0);

            c.close();
            db.close();

            tv.setText(
                    "Server: OK (5000)\n\n" +
                    "DB: " + format(dbSize) + "\n" +
                    "Records: " + count + "\n\n" +
                    "Free: " + format(free) + "\n" +
                    "Total: " + format(total)
            );

        } catch (Exception e) {
            tv.setText("ERR: " + e.getMessage());
        }
    }

    private String format(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024);
    }

    // ===== ЕКСПОРТ CSV =====
    private void exportCSV() {
        try {
            File file = new File(getExternalFilesDir(null), "export.csv");
            FileWriter fw = new FileWriter(file);

            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor c = db.rawQuery("SELECT * FROM data", null);

            fw.write("ts,pv,load,soc\n");

            while (c.moveToNext()) {
                fw.write(
                        c.getLong(0) + "," +
                        c.getDouble(1) + "," +
                        c.getDouble(2) + "," +
                        c.getDouble(3) + "\n"
                );
            }

            c.close();
            db.close();
            fw.close();

            Toast.makeText(this, "Saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "ERR export", Toast.LENGTH_SHORT).show();
        }
    }

    // ===== MINI GRAPH =====
    class MiniGraphView extends View {

        Paint p = new Paint();

        public MiniGraphView(Context c) {
            super(c);
            p.setStrokeWidth(3);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = getWidth();
            int h = getHeight();

            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT ts,pv FROM data ORDER BY ts DESC LIMIT 100",
                    null
            );

            List<Float> vals = new ArrayList<>();

            while (c.moveToNext()) {
                vals.add((float)c.getDouble(1));
            }

            c.close();
            db.close();

            if (vals.size() < 2) return;

            Collections.reverse(vals);

            float max = Collections.max(vals);

            for (int i = 1; i < vals.size(); i++) {

                float x1 = (i - 1) * w / vals.size();
                float x2 = i * w / vals.size();

                float y1 = h - (vals.get(i - 1) / max) * h;
                float y2 = h - (vals.get(i) / max) * h;

                p.setColor(Color.YELLOW);
                canvas.drawLine(x1, y1, x2, y2, p);
            }
        }
    }

    // ===== DATABASE =====
    static class DBHelper extends SQLiteOpenHelper {
        public DBHelper(Context c){ super(c,"solar.db",null,1); }

        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE data(ts INTEGER,pv REAL,load REAL,soc REAL)");
        }

        public void onUpgrade(SQLiteDatabase db,int o,int n){}
    }

    // ===== SERVER =====
    class MyServer extends NanoHTTPD {
        public MyServer(){ super(5000); }

        public Response serve(IHTTPSession session){
            try{

                if(Method.GET.equals(session.getMethod()) &&
                   "/".equals(session.getUri())){

                    InputStream is = getAssets().open("graph.html");
                    Scanner s = new Scanner(is).useDelimiter("\\A");
                    String html = s.hasNext() ? s.next() : "";

                    return newFixedLengthResponse(Response.Status.OK, "text/html", html);
                }

                if(Method.POST.equals(session.getMethod()) &&
                   "/api/telemetry".equals(session.getUri())){

                    Map<String,String> files=new HashMap<>();
                    session.parseBody(files);

                    String body=files.get("postData");

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

                    return newFixedLengthResponse("OK");
                }

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

                    return newFixedLengthResponse(json.toString());
                }

                return newFixedLengthResponse("OK");

            }catch(Exception e){
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
}
