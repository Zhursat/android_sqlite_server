
package com.example.httpserver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import fi.iki.elonen.NanoHTTPD;
import android.database.sqlite.*;
import android.content.*;
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

    static class DBHelper extends SQLiteOpenHelper {
        public DBHelper(Context c){ super(c,"solar.db",null,1);}
        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE data(ts INTEGER,pv REAL,load REAL,soc REAL)");
        }
        public void onUpgrade(SQLiteDatabase db,int o,int n){}
    }

    class MyServer extends NanoHTTPD {
        public MyServer(){ super(5000); }

        public Response serve(IHTTPSession session){
            try{
                if(Method.POST.equals(session.getMethod())){
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
                return newFixedLengthResponse("Server running");
            }catch(Exception e){
                return newFixedLengthResponse("ERR "+e.getMessage());
            }
        }

        private double get(String j,String k){
            try{
                String s="\"" + k + "\"";
                int i=j.indexOf(s);
                int c=j.indexOf(":",i);
                int e=j.indexOf(",",c);
                if(e==-1)e=j.indexOf("}",c);
                return Double.parseDouble(j.substring(c+1,e).trim());
            }catch(Exception ex){return 0;}
        }
    }
}
