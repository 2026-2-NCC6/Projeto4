package com.example.smarttennisapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.smarttennisapp.util.SessaoUsuario;

public class MainActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        if (SessaoUsuario.temSessao(this)) {
            startActivity(new Intent(this, HomePage.class));
            finish();
            return;
        }

        VideoView videoTennisBall = findViewById(R.id.videoTennisBall);
        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.placeholder);
        videoTennisBall.setVideoURI(videoUri);
        videoTennisBall.setOnPreparedListener(mediaPlayer -> {
            mediaPlayer.setLooping(true);
            // Preenche a tela com corte central, sem deformar o vídeo em telas ultrawide.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaPlayer.setVideoScalingMode(
                        android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                );
            }
            videoTennisBall.start();
        });

        Button btnEntrar = findViewById(R.id.btnEntrar);
        btnEntrar.setOnClickListener(view -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}
