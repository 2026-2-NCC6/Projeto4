package com.example.smarttennisapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
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

public class HomePage extends AppCompatActivity {

    Usuario usuario = new Usuario();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home_page);

        AppDatabase db = Room.databaseBuilder(
                getApplicationContext(),
                AppDatabase.class,
                "SmartTennisApp"
        ).build();

        //Essencial para buscar os dados ;)
        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        TextView welcome = findViewById(R.id.textNomePerfilHome);
        executor.execute(() -> {
            Usuario usuario = db.usuarioDAO().getUserById(id);
            runOnUiThread(() -> {
                if (usuario != null) {
                    String mensagemWelcome =
                            getString(R.string.nomeUsuario) + usuario.nome;
                    welcome.setText(mensagemWelcome);
                }
            });
        });
        ImageView perfilTop = findViewById(R.id.imageButtonPerfilHome);
        ImageView exerciciosBtn = findViewById(R.id.imageButtonExerciciosHome);
        ImageView estatistica = findViewById(R.id.imageButtonEstatisticaHome);
        ImageView perfil = findViewById(R.id.imageButtonPerfilHome);

        estatistica.setOnClickListener(view->{
            Intent intent = new Intent(this, Estatiscas.class);
            startActivity(intent);
        });

        perfil.setOnClickListener(view->{
            Intent intent = new Intent(this, MeuPerfil.class);
            startActivity(intent);
        });

        perfilTop.setOnClickListener(view->{
            Intent intent = new Intent(this, MeuPerfil.class);
            startActivity(intent);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 440);
        if(id == 440){
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
        }
    }
}