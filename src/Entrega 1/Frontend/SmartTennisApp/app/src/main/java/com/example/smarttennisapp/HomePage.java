package com.example.smarttennisapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageButton;
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
        ExecutorService executor = Executors.newSingleThreadExecutor();

        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 0);

        executor.execute(()->{
            usuario = db.usuarioDAO().getUserById(id);
        });

        TextView welcome = findViewById(R.id.textNomePerfilHome);
        ImageButton perfilTop = findViewById(R.id.imageButtonPerfilHome);
        ImageButton exerciciosBtn = findViewById(R.id.imageButtonExerciciosHome);
        ImageButton estatistica = findViewById(R.id.imageButtonEstatisticaHome);
        ImageButton perfil = findViewById(R.id.imageButtonPerfilHome);

        String mensagemWelcome = getString(R.string.nomeUsuario) + usuario.nome;

        welcome.setText(mensagemWelcome);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}