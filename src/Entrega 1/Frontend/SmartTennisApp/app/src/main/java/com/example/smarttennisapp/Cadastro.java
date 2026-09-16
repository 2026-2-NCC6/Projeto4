package com.example.smarttennisapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.room.Room;

import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.Usuario;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Cadastro extends AppCompatActivity {

    Usuario usuario = new Usuario();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cadastro);

        AppDatabase db = Room.databaseBuilder(
                getApplicationContext(),
                AppDatabase.class,
                "SmartTennisApp"
        ).build();

        TextInputEditText email = findViewById(R.id.editEmailCadastroInput);
        TextInputEditText senha = findViewById(R.id.editSenhaCadastroInput);
        TextInputEditText nome = findViewById(R.id.editNomeCadastroInput);
        TextInputEditText dataNascimento = findViewById(R.id.editNascimentoCadastroInput);
        Button btnCadastro = findViewById(R.id.btnCadastroCadastro);
        Button btnVoltar = findViewById(R.id.btnVoltarCadastro);

        btnVoltar.setOnClickListener(view->{
            finish();
        });

        btnCadastro.setOnClickListener(view->{
            String emailText = Objects.requireNonNull(email.getText()).toString();
            String senhaText = Objects.requireNonNull(senha.getText()).toString();
            String nomeText = Objects.requireNonNull(nome.getText()).toString();
            String dataNascimentoText = Objects.requireNonNull(dataNascimento.getText()).toString();


            if(emailText.isBlank() || senhaText.isBlank() || nomeText.isBlank() || dataNascimentoText.isBlank()){
                Toast.makeText(this, "Preencha todos os campos!", Toast.LENGTH_SHORT).show();
            }else{
                ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.execute(() -> {
                    usuario = new Usuario();
                    usuario.nome = nomeText;
                    usuario.senha = senhaText;
                    usuario.email = emailText;
                    usuario.data = dataNascimentoText;
                    db.usuarioDAO().insert(usuario);
                    int id = db.usuarioDAO().getIdByEmail(emailText);
                    SharedPreferences prefs =
                            getSharedPreferences("UserData", MODE_PRIVATE);
                    prefs.edit()
                            .putInt("id", id)
                            .apply();
                    runOnUiThread(() -> {
                        Intent intent = new Intent(this, HomePage.class);
                        startActivity(intent);
                        finish();
                    });
                });
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}