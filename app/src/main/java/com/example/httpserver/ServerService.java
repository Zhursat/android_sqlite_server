package com.example.httpserver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class ServerService extends Service {

    private MyServer server;

    @Override
    public void onCreate() {
        super.onCreate();

        new Thread(() -> {
            try {
                server = new MyServer(getApplicationContext());
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
