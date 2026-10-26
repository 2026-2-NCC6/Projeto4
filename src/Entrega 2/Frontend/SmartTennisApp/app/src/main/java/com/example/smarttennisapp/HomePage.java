package com.example.smarttennisapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.Usuario;
import com.example.smarttennisapp.util.SessaoUsuario;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomePage extends BaseActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home_page);

        //Essencial para buscar os dados ;)
        AppDatabase db = DatabaseProvider.getInstance(getApplicationContext());

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
        ImageView perfilTop = findViewById(R.id.imagePerfilHomeBtn);
        LinearLayout estatistica = findViewById(R.id.imageButtonEstatisticaHome);
        LinearLayout perfil = findViewById(R.id.imageButtonPerfilHome);
        CardView treinoLivreCard = findViewById(R.id.cardTreinoLivre);
        CardView treinoGuiadoCard = findViewById(R.id.cardTreinoGuiado);
        CardView criarTreinoCard = findViewById(R.id.cardCriarTreino);

        treinoLivreCard.setOnClickListener(view->{
            Intent intent = new Intent(this, TreinoLivre.class);
            startActivity(intent);
        });

        treinoGuiadoCard.setOnClickListener(view->{
            Intent intent = new Intent(this, TreinoGuiadoLista.class);
            startActivity(intent);
        });

        criarTreinoCard.setOnClickListener(view -> {
            Intent intent = new Intent(this, CriarDesafioActivity.class);
            startActivity(intent);
        });

        CardView jogoParedeCard = findViewById(R.id.cardJogoParede);
        if (jogoParedeCard != null) {
            jogoParedeCard.setOnClickListener(view -> {
                Intent intent = new Intent(this, com.example.smarttennisapp.jogo.JogoActivity.class);
                startActivity(intent);
            });
        }

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
        int id = SessaoUsuario.obterId(this);
        if (id <= 0) {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        SyncManager.sincronizarEmSegundoPlano(this, id);
    }
}