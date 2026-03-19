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
        findViewById(R.id.btnRestore).setOnClickListener(v -> restore());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearDb());
        findViewById(R.id.btnRestart).setOnClickListener(v -> restart());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    private File getBackupFile() {
        return new File(Environment.getExternalStorageDirectory(), "solar_backup.db");
    }

    private void backup() {
        try {
            File src = getDatabasePath("solar.db");
            File dst = getBackupFile();

            copyFile(src, dst);

            Toast.makeText(this, "Backup OK", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Backup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restore() {
        try {
            File src = getBackupFile();
            File dst = getDatabasePath("solar.db");

            if (!src.exists()) {
                Toast.makeText(this, "Backup not found!", Toast.LENGTH_LONG).show();
                return;
            }

            copyFile(src, dst);

            Toast.makeText(this, "Restore OK. Restarting...", Toast.LENGTH_SHORT).show();
            restart();

        } catch (Exception e) {
            Toast.makeText(this, "Restore error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearDb() {
        try {
            File db = getDatabasePath("solar.db");

            if (db.exists()) {
                db.delete();
            }

            Toast.makeText(this, "DB cleared. Restarting...", Toast.LENGTH_SHORT).show();
            restart();

        } catch (Exception e) {
            Toast.makeText(this, "Clear error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
