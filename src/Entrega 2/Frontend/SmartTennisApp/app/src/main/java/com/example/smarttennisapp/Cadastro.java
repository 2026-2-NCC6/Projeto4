package com.example.smarttennisapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.view.View;
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
import com.example.smarttennisapp.util.AvatarCatalogo;
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
    private String avatarSelecionadoId = AvatarCatalogo.DEFAULT_ID;
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
        LinearLayout gridAvatares = findViewById(R.id.gridAvataresCadastro);
        ImageView avatarPreview = findViewById(R.id.imgAvatarSelecionadoCadastro);
        TextView avatarNome = findViewById(R.id.txtAvatarSelecionadoCadastro);
        configurarAvatares(gridAvatares, avatarPreview, avatarNome);

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
            final String avatarIdCadastro = avatarSelecionadoId;
            executor.execute(() -> {
                try {
                    if (db.usuarioDAO().getIdByEmail(emailText) > 0) {
                        concluirCadastro(btnCadastro, "Ja existe uma conta local com este e-mail. Entre com seu login.", false);
                        return;
                    }

                    JSONObject authRemota = null;
                    String aviso = null;
                    try {
                        authRemota = BackendApi.cadastrar(this, nomeText, emailText, senhaText, dataText, avatarIdCadastro);
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
                        // O cadastro pode ter sido confirmado pela API mesmo se a resposta
                        // se perdeu/expirou. Tente autenticar antes de criar uma conta offline.
                        try {
                            authRemota = BackendApi.login(this, emailText, senhaText);
                            aviso = null;
                        } catch (IOException falhaLoginAposCadastro) {
                            aviso = "Nao foi possivel confirmar a resposta do servidor. A conta ficou salva neste aparelho; tente novamente quando o backend Azure estiver disponível.";
                        }
                    }

                    Usuario novoUsuario = new Usuario();
                    novoUsuario.nome = nomeText;
                    novoUsuario.senha = SenhaLocalUtil.gerarHash(senhaText);
                    novoUsuario.email = emailText;
                    novoUsuario.data = dataText;
                    novoUsuario.avatarId = avatarIdCadastro;
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

    private void configurarAvatares(LinearLayout grid, ImageView preview, TextView nomeSelecionado) {
        if (grid == null || preview == null) return;

        grid.removeAllViews();
        String[] avatarIds = AvatarCatalogo.ids();
        preview.setImageResource(AvatarCatalogo.drawableId(avatarSelecionadoId));
        atualizarNomeAvatar(nomeSelecionado);

        // Três linhas com quatro opções: cada linha centraliza seu próprio conteúdo.
        final int colunas = 4;
        final int margemHorizontal = dp(4);
        final int margemVertical = dp(5);
        // Ajusta o tamanho em telas estreitas para manter os quatro itens dentro da linha.
        int larguraTelaDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        int larguraUtilDp = larguraTelaDp - 40 - 36; // padding do ScrollView e do cartão.
        int tamanhoAvatar = Math.max(dp(42), Math.min(dp(58),
                dp(Math.max(42, (larguraUtilDp - 32) / colunas))));

        for (int inicio = 0; inicio < avatarIds.length; inicio += colunas) {
            LinearLayout linha = new LinearLayout(this);
            linha.setOrientation(LinearLayout.HORIZONTAL);
            linha.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams linhaParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            grid.addView(linha, linhaParams);

            int fim = Math.min(inicio + colunas, avatarIds.length);
            for (int i = inicio; i < fim; i++) {
                final String avatarId = avatarIds[i];
                ImageView opcao = new ImageView(this);
                opcao.setImageResource(AvatarCatalogo.drawableId(avatarId));
                opcao.setScaleType(ImageView.ScaleType.CENTER_CROP);
                opcao.setContentDescription("Selecionar avatar " + avatarId.substring(avatarId.length() - 2));
                opcao.setBackgroundResource(avatarId.equals(avatarSelecionadoId)
                        ? R.drawable.bg_avatar_selecionado : R.drawable.bg_avatar_nao_selecionado);
                opcao.setClipToOutline(true);
                opcao.setPadding(dp(3), dp(3), dp(3), dp(3));
                opcao.setFocusable(true);
                opcao.setClickable(true);
                opcao.setTag(avatarId);

                LinearLayout.LayoutParams opcaoParams = new LinearLayout.LayoutParams(
                        tamanhoAvatar, tamanhoAvatar);
                opcaoParams.setMargins(margemHorizontal, margemVertical,
                        margemHorizontal, margemVertical);
                linha.addView(opcao, opcaoParams);

                opcao.setOnClickListener(v -> {
                    avatarSelecionadoId = avatarId;
                    preview.setImageResource(AvatarCatalogo.drawableId(avatarSelecionadoId));
                    atualizarNomeAvatar(nomeSelecionado);
                    atualizarDestaqueAvatar(grid, avatarSelecionadoId);
                });
            }
        }
    }

    private void atualizarNomeAvatar(TextView nomeSelecionado) {
        if (nomeSelecionado == null) return;
        String numero = avatarSelecionadoId.substring(avatarSelecionadoId.length() - 2);
        nomeSelecionado.setText("Avatar " + numero);
    }

    private void atualizarDestaqueAvatar(LinearLayout grid, String selecionadoId) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View linhaView = grid.getChildAt(i);
            if (!(linhaView instanceof LinearLayout)) continue;
            LinearLayout linha = (LinearLayout) linhaView;
            for (int j = 0; j < linha.getChildCount(); j++) {
                View item = linha.getChildAt(j);
                if (!(item instanceof ImageView)) continue;
                String id = (String) item.getTag();
                item.setBackgroundResource(selecionadoId.equals(id)
                        ? R.drawable.bg_avatar_selecionado : R.drawable.bg_avatar_nao_selecionado);
            }
        }
    }

    private int dp(int valor) {
        return (int) (valor * getResources().getDisplayMetrics().density + 0.5f);
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
