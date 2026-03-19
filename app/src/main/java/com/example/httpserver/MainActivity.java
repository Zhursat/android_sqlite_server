package com.example.httpserver;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UI
        setContentView(R.layout.activity_main);

        // 🔥 запуск сервера
        startService(new Intent(this, ServerService.class));

        // кнопка переходу в ControlActivity
        findViewById(R.id.btnControl).setOnClickListener(v -> {
            startActivity(new Intent(this, ControlActivity.class));
        });
    }
}
