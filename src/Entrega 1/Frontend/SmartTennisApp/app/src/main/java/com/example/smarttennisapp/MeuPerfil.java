package com.example.smarttennisapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.room.Room;

import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.Usuario;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeuPerfil extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_meu_perfil);

        AppDatabase db = Room.databaseBuilder(
                getApplicationContext(),
                AppDatabase.class,
                "SmartTennisApp"
        ).build();

        //Essencial para buscar os dados ;)
        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        TextView nome = findViewById(R.id.textNomePerfil);
        TextView email = findViewById(R.id.textEmailPerfil);
        TextView nascimento = findViewById(R.id.textNascimentoPerfil);

        executor.execute(() -> {
            Usuario usuario = db.usuarioDAO().getUserById(id);
            runOnUiThread(() -> {
                if (usuario != null) {
                    nome.setText(usuario.nome);
                    email.setText(usuario.email);
                    nascimento.setText(usuario.data);
                }
            });
        });

        Button btnVoltar = findViewById(R.id.btnVoltarPerfil);
        Button btnSair = findViewById(R.id.btnSairPerfil);

        btnVoltar.setOnClickListener(view->{
            finish();
        });

        btnSair.setOnClickListener(view->{
            prefs.edit().clear().apply();
            finish();
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}