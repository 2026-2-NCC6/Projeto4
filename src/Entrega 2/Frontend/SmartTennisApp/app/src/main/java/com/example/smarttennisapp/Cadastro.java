package com.example.smarttennisapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.smarttennisapp.backend.BackendApi;
import com.example.smarttennisapp.backend.SenhaLocalUtil;
import com.example.smarttennisapp.backend.SessaoRemota;
import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.Usuario;
import com.example.smarttennisapp.util.SessaoUsuario;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Cadastro extends BaseActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cadastro);

        AppDatabase db = DatabaseProvider.getInstance(getApplicationContext());
        TextInputEditText email = findViewById(R.id.editEmailCadastroInput);
        TextInputEditText senha = findViewById(R.id.editSenhaCadastroInput);
        TextInputEditText nome = findViewById(R.id.editNomeCadastroInput);
        TextInputEditText dataNascimento = findViewById(R.id.editNascimentoCadastroInput);
        Button btnCadastro = findViewById(R.id.btnCadastroCadastro);
        ImageButton btnVoltar = findViewById(R.id.btnVoltarCadastro);

        String emailRecebido = getIntent().getStringExtra("email");
        if (emailRecebido != null && !emailRecebido.isBlank()) email.setText(emailRecebido);

        dataNascimento.setOnClickListener(view -> {
            MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Selecione a data de nascimento")
                    .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                    .build();
            datePicker.addOnPositiveButtonClickListener(selectionInMillis -> {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                dataNascimento.setText(dateFormat.format(new Date(selectionInMillis)));
            });
            datePicker.show(getSupportFragmentManager(), "DATE_PICKER_TAG");
        });

        btnVoltar.setOnClickListener(view -> finish());
        btnCadastro.setOnClickListener(view -> {
            String emailText = Objects.requireNonNull(email.getText()).toString().trim().toLowerCase(Locale.ROOT);
            String senhaText = Objects.requireNonNull(senha.getText()).toString();
            String nomeText = Objects.requireNonNull(nome.getText()).toString().trim();
            String dataText = Objects.requireNonNull(dataNascimento.getText()).toString().trim();
            if (emailText.isBlank() || senhaText.isBlank() || nomeText.isBlank() || dataText.isBlank()) {
                Toast.makeText(this, "Preencha todos os campos!", Toast.LENGTH_SHORT).show();
                return;
            }
            int tamanhoSenhaBytes = senhaText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (senhaText.length() < 8 || tamanhoSenhaBytes > 72) {
                senha.setError("Use pelo menos 8 caracteres e no maximo 72 bytes em UTF-8.");
                return;
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
                email.setError("Informe um e-mail valido.");
                return;
            }

            btnCadastro.setEnabled(false);
            executor.execute(() -> {
                try {
                    if (db.usuarioDAO().getIdByEmail(emailText) > 0) {
                        concluirCadastro(btnCadastro, "Ja existe uma conta local com este e-mail. Entre com seu login.", false);
                        return;
                    }

                    JSONObject authRemota = null;
                    String aviso = null;
                    try {
                        authRemota = BackendApi.cadastrar(this, nomeText, emailText, senhaText, dataText);
                    } catch (BackendApi.ApiException e) {
                        if ("EMAIL_EXISTS".equals(e.code)) {
                            concluirCadastro(btnCadastro, "Este e-mail ja possui uma conta. Entre com seu login.", false);
                            return;
                        }
                        if (e.statusCode >= 400 && e.statusCode < 500) {
                            concluirCadastro(btnCadastro, e.getMessage(), false);
                            return;
                        }
                        aviso = "Servidor indisponivel; a conta foi criada somente neste dispositivo por enquanto.";
                    } catch (IOException e) {
                        aviso = "Sem conexao com o servidor; a conta foi criada somente neste dispositivo por enquanto.";
                    }

                    Usuario novoUsuario = new Usuario();
                    novoUsuario.nome = nomeText;
                    novoUsuario.senha = SenhaLocalUtil.gerarHash(senhaText);
                    novoUsuario.email = emailText;
                    novoUsuario.data = dataText;
                    db.usuarioDAO().insert(novoUsuario);
                    int idCriado = db.usuarioDAO().getIdByEmail(emailText);
                    if (idCriado <= 0) {
                        concluirCadastro(btnCadastro, "Nao foi possivel recuperar o usuario criado.", false);
                        return;
                    }
                    SessaoUsuario.salvarId(this, idCriado);
                    if (authRemota != null) {
                        SessaoRemota.salvar(this, authRemota, idCriado);
                        SyncManager.sincronizarEmSegundoPlano(this, idCriado);
                    }
                    concluirCadastro(btnCadastro, aviso, true);
                } catch (RuntimeException e) {
                    concluirCadastro(btnCadastro, "Nao foi possivel criar a conta local. Verifique se o e-mail ja esta cadastrado.", false);
                } catch (Exception e) {
                    concluirCadastro(btnCadastro, "Falha ao criar conta: " + (e.getMessage() == null ? "erro inesperado" : e.getMessage()), false);
                }
            });
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void concluirCadastro(Button btnCadastro, String aviso, boolean sucesso) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            btnCadastro.setEnabled(true);
            if (aviso != null && !aviso.isEmpty()) Toast.makeText(this, aviso, Toast.LENGTH_LONG).show();
            if (sucesso) {
                startActivity(new Intent(this, HomePage.class));
                finish();
            } else if (aviso == null || aviso.isEmpty()) {
                Toast.makeText(this, "Nao foi possivel concluir o cadastro.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
