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

public class LoginActivity extends AppCompatActivity {

    Usuario usuario = new Usuario();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login2);

        //Essencial para buscar os dados ;)
        AppDatabase db = Room.databaseBuilder(
                getApplicationContext(),
                AppDatabase.class,
                "SmartTennisApp"
        ).build();

        ExecutorService executor = Executors.newSingleThreadExecutor();

        //Até aqui

        Button btnEntrar = findViewById(R.id.btnEntrarLogin);
        Button btnCadastroLogin = findViewById(R.id.btnCadastrarLogin);
        TextInputEditText emailLoginInput = findViewById(R.id.editEmailLoginInput);
        TextInputEditText senhaLoginInput = findViewById(R.id.editSenhaLoginInput);

        btnEntrar.setOnClickListener(view ->{
            String email = Objects.requireNonNull(emailLoginInput.getText()).toString();
            String senha = Objects.requireNonNull(senhaLoginInput.getText()).toString();
            if (email.isBlank() || senha.isBlank()){
                Toast.makeText(this, "Preencha todos os campos para continuar", Toast.LENGTH_SHORT).show();
            }else{
                //Autenticação com o backend aqui

                executor.execute(()->{
                    int id = db.usuarioDAO().getIdByName(email);
                    SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putInt("id", id);
                    editor.apply();
                    Intent intent = new Intent(this, HomePage.class);
                    startActivity(intent);
                    finish();
                });

            }
        });

        btnCadastroLogin.setOnClickListener(view->{
            Intent intent = new Intent(this, Cadastro.class);
            String email = Objects.requireNonNull(emailLoginInput.getText()).toString();
            if (!email.isBlank()){
                intent.putExtra("email", email);
            }
            startActivity(intent);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}