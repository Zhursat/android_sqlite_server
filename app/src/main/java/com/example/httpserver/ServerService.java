package com.example.httpserver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;

public class ServerService extends Service {

    private NanoHTTPD server;

    @Override
    public void onCreate() {
        super.onCreate();

        new Thread(() -> {
            try {
                server = new MainActivity().new MyServer();
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}