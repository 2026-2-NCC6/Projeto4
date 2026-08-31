package com.example.smarttennisapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.room.Room;

import com.example.smarttennisapp.database.AppDatabase;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 440);
        if(id != 440){
            Intent intent = new Intent(this, HomePage.class);
            startActivity(intent);
        }

        VideoView videoTennisBall = findViewById(R.id.videoTennisBall);
        String caminhoVideo = "android.resource://" + getPackageName() + "/" + R.raw.placeholder;
        Uri uri = Uri.parse(caminhoVideo);

        videoTennisBall.setVideoURI(uri);
        videoTennisBall.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            videoTennisBall.start();
        });

        Button btnEntrar = findViewById(R.id.btnEntrar);
        btnEntrar.setOnClickListener(view->{
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}