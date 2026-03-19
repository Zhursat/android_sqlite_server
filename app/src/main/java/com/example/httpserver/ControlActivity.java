package com.example.httpserver;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class ControlActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        findViewById(R.id.btnBackup).setOnClickListener(v -> backup());
        findViewById(R.id.btnRestart).setOnClickListener(v -> restart());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    private void backup() {
        try {
            File src = getDatabasePath("solar.db");
            File dst = new File(Environment.getExternalStorageDirectory(), "solar_backup.db");

            copyFile(src, dst);

            Toast.makeText(this, "Backup OK", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restart() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {

            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
