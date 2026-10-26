package com.example.smarttennisapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
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
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends BaseActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login2);

        AppDatabase db = DatabaseProvider.getInstance(getApplicationContext());
        ImageButton btnVoltar = findViewById(R.id.btnVoltarLogin);
        btnVoltar.setOnClickListener(v -> voltarAoInicio());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { voltarAoInicio(); }
        });

        Button btnEntrar = findViewById(R.id.btnEntrarLogin);
        Button btnCadastroLogin = findViewById(R.id.btnCadastrarLogin);
        TextInputEditText emailLoginInput = findViewById(R.id.editEmailLoginInput);
        TextInputEditText senhaLoginInput = findViewById(R.id.editSenhaLoginInput);

        btnEntrar.setOnClickListener(view -> {
            String email = Objects.requireNonNull(emailLoginInput.getText()).toString().trim().toLowerCase(java.util.Locale.ROOT);
            String senha = Objects.requireNonNull(senhaLoginInput.getText()).toString();
            if (email.isBlank() || senha.isBlank()) {
                Toast.makeText(this, "Preencha todos os campos para continuar", Toast.LENGTH_SHORT).show();
                return;
            }

            btnEntrar.setEnabled(false);
            executor.execute(() -> {
                try {
                    JSONObject authRemota = null;
                    String erroServidor = null;
                    try {
                        authRemota = BackendApi.login(this, email, senha);
                    } catch (IOException e) {
                        erroServidor = e.getMessage();
                    }

                    int idLocal;
                    if (authRemota != null) {
                        JSONObject usuarioRemoto = authRemota.optJSONObject("user");
                        if (usuarioRemoto == null) throw new IllegalStateException("Resposta de login sem usuario.");
                        int idExistente = db.usuarioDAO().getIdByEmail(email);
                        Usuario usuario = idExistente > 0 ? db.usuarioDAO().getUserById(idExistente) : new Usuario();
                        usuario.nome = usuarioRemoto.optString("nome", "Jogador");
                        usuario.email = usuarioRemoto.optString("email", email);
                        usuario.data = usuarioRemoto.isNull("dataNascimento") ? "" : usuarioRemoto.optString("dataNascimento", "");
                        usuario.senha = SenhaLocalUtil.gerarHash(senha);
                        if (idExistente > 0) db.usuarioDAO().update(usuario);
                        else db.usuarioDAO().insert(usuario);
                        idLocal = db.usuarioDAO().getIdByEmail(email);
                        if (idLocal <= 0) throw new IllegalStateException("Nao foi possivel salvar a conta neste dispositivo.");
                        SessaoUsuario.salvarId(this, idLocal);
                        SessaoRemota.salvar(this, authRemota, idLocal);
                        SyncManager.sincronizarEmSegundoPlano(this, idLocal);
                        entrarNoApp(null);
                        return;
                    }

                    int idEncontrado = db.usuarioDAO().getIdByEmail(email);
                    Usuario usuarioLocal = idEncontrado > 0 ? db.usuarioDAO().getUserById(idEncontrado) : null;
                    if (usuarioLocal == null || !SenhaLocalUtil.verificar(senha, usuarioLocal.senha)) {
                        String mensagem;
                        if (erroServidor != null && (erroServidor.contains("HTTP 401") || erroServidor.contains("incorretos"))) {
                            mensagem = "E-mail ou senha incorretos.";
                        } else if (erroServidor != null && erroServidor.startsWith("Servidor SmartTennis indisponivel")) {
                            mensagem = "Sem conexao com o servidor e sem uma conta local valida. Conecte-se para entrar pela primeira vez.";
                        } else {
                            mensagem = "Usuário não encontrado ou senha incorreta.";
                        }
                        mostrarErroLogin(mensagem);
                        return;
                    }

                    // Compatibilidade: transforma uma senha antiga em texto puro em hash local.
                    if (!SenhaLocalUtil.eHash(usuarioLocal.senha)) {
                        usuarioLocal.senha = SenhaLocalUtil.gerarHash(senha);
                        db.usuarioDAO().update(usuarioLocal);
                    }
                    idLocal = usuarioLocal.id;
                    SessaoUsuario.salvarId(this, idLocal);

                    // Se a conta foi criada offline, tenta vinculá-la ao backend no próximo login online.
                    boolean vinculada = false;
                    try {
                        JSONObject novoLogin = BackendApi.cadastrar(this, usuarioLocal.nome, email, senha, usuarioLocal.data);
                        SessaoRemota.salvar(this, novoLogin, idLocal);
                        vinculada = true;
                    } catch (BackendApi.ApiException e) {
                        if ("EMAIL_EXISTS".equals(e.code)) {
                            try {
                                JSONObject loginExistente = BackendApi.login(this, email, senha);
                                SessaoRemota.salvar(this, loginExistente, idLocal);
                                vinculada = true;
                            } catch (IOException ignorada) {
                                // Credencial local pode ser de uma conta antiga; mantemos o modo offline.
                            }
                        }
                    } catch (IOException ignorada) {
                        // Sem internet: login local continua funcionando normalmente.
                    }
                    if (vinculada) SyncManager.sincronizarEmSegundoPlano(this, idLocal);
                    entrarNoApp(vinculada ? null : "Login local. Seus dados continuam disponiveis offline; a sincronizacao sera ativada quando a conta remota puder ser validada.");
                } catch (Exception e) {
                    mostrarErroLogin("Nao foi possivel concluir o login: " + (e.getMessage() == null ? "erro inesperado" : e.getMessage()));
                }
            });
        });

        btnCadastroLogin.setOnClickListener(view -> {
            Intent intent = new Intent(this, Cadastro.class);
            String email = Objects.requireNonNull(emailLoginInput.getText()).toString().trim();
            if (!email.isBlank()) intent.putExtra("email", email);
            startActivity(intent);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void mostrarErroLogin(String mensagem) {
        runOnUiThread(() -> {
            Button btnEntrar = findViewById(R.id.btnEntrarLogin);
            if (btnEntrar != null) btnEntrar.setEnabled(true);
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void entrarNoApp(String aviso) {
        runOnUiThread(() -> {
            Button btnEntrar = findViewById(R.id.btnEntrarLogin);
            if (btnEntrar != null) btnEntrar.setEnabled(true);
            if (isFinishing() || isDestroyed()) return;
            if (aviso != null) Toast.makeText(this, aviso, Toast.LENGTH_LONG).show();
            if (SessaoUsuario.temSessao(this)) {
                startActivity(new Intent(this, HomePage.class));
                finish();
            }
        });
    }

    private void voltarAoInicio() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
