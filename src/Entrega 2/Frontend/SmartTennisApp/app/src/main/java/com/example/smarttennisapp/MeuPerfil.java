package com.example.smarttennisapp;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.smarttennisapp.backend.BackendApi;
import com.example.smarttennisapp.backend.SessaoRemota;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.Usuario;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeuPerfil extends BaseActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CountDownTimer timerCodigo;

    private LinearLayout conteudoPerfil;
    private LinearLayout conteudoAmigos;
    private LinearLayout listaAmigos;
    private TextView codigoAmizade;
    private TextView validadeCodigo;
    private TextView estadoAmigos;
    private Button btnGerarCodigo;
    private Button btnAdicionarAmigo;
    private Button btnAdicionarAmigoRede;
    private Button btnAtualizarAmigos;
    private Button btnAbaPerfil;
    private Button btnAbaAmigos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_meu_perfil);

        AppDatabase db = DatabaseProvider.getInstance(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("UserData", MODE_PRIVATE);
        int id = prefs.getInt("id", 0);

        TextView nome = findViewById(R.id.textNomePerfil);
        TextView email = findViewById(R.id.textEmailPerfil);
        TextView nascimento = findViewById(R.id.textNascimentoPerfil);
        ImageView avatar = findViewById(R.id.imageView);

        conteudoPerfil = findViewById(R.id.containerConteudoPerfil);
        conteudoAmigos = findViewById(R.id.containerConteudoAmigos);
        listaAmigos = findViewById(R.id.containerListaAmigos);
        codigoAmizade = findViewById(R.id.txtCodigoAmizade);
        validadeCodigo = findViewById(R.id.txtValidadeCodigoAmizade);
        estadoAmigos = findViewById(R.id.txtEstadoAmigos);
        btnGerarCodigo = findViewById(R.id.btnGerarCodigoAmizade);
        btnAdicionarAmigo = findViewById(R.id.btnAdicionarAmigo);
        btnAdicionarAmigoRede = findViewById(R.id.btnAdicionarAmigoRede);
        btnAtualizarAmigos = findViewById(R.id.btnAtualizarAmigos);
        btnAbaPerfil = findViewById(R.id.btnAbaPerfil);
        btnAbaAmigos = findViewById(R.id.btnAbaAmigos);

        executor.execute(() -> {
            Usuario usuario = db.usuarioDAO().getUserById(id);
            runOnUiThread(() -> {
                if (usuario != null && !isFinishing()) {
                    nome.setText(usuario.nome);
                    email.setText(usuario.email);
                    nascimento.setText(usuario.data);
                    avatar.setImageResource(com.example.smarttennisapp.util.AvatarCatalogo.drawableId(usuario.avatarId));
                }
            });
        });

        ImageButton btnVoltar = findViewById(R.id.btnVoltarPerfil);
        Button btnSair = findViewById(R.id.btnSairPerfil);
        btnVoltar.setOnClickListener(view -> finish());
        btnSair.setOnClickListener(view -> {
            prefs.edit().clear().apply();
            SessaoRemota.limpar(this);
            finish();
        });

        btnAbaPerfil.setOnClickListener(v -> selecionarAba(false));
        btnAbaAmigos.setOnClickListener(v -> {
            selecionarAba(true);
            carregarAmigos();
        });
        btnGerarCodigo.setOnClickListener(v -> gerarCodigoAmizade());
        btnAdicionarAmigo.setOnClickListener(v -> abrirDialogAdicionarAmigo());
        btnAdicionarAmigoRede.setOnClickListener(v -> abrirDialogAdicionarAmigo());
        codigoAmizade.setOnClickListener(v -> {
            String codigo = codigoAmizade.getText().toString();
            if (codigo.matches("\\d{8}")) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Código de amizade", codigo));
                    Toast.makeText(this, "Código copiado.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnAtualizarAmigos.setOnClickListener(v -> carregarAmigos());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void selecionarAba(boolean amigos) {
        conteudoPerfil.setVisibility(amigos ? View.GONE : View.VISIBLE);
        conteudoAmigos.setVisibility(amigos ? View.VISIBLE : View.GONE);
        btnAbaPerfil.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                amigos ? ContextCompat.getColor(this, R.color.surface_secondary) : ContextCompat.getColor(this, R.color.accent)));
        btnAbaPerfil.setTextColor(ContextCompat.getColor(this, amigos ? R.color.text_primary : R.color.accent_text));
        btnAbaAmigos.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                amigos ? ContextCompat.getColor(this, R.color.accent) : ContextCompat.getColor(this, R.color.surface_secondary)));
        btnAbaAmigos.setTextColor(ContextCompat.getColor(this, amigos ? R.color.accent_text : R.color.text_primary));
    }

    private String obterToken() {
        int localId = com.example.smarttennisapp.util.SessaoUsuario.obterId(this);
        return SessaoRemota.obterToken(this, localId);
    }

    private void gerarCodigoAmizade() {
        String token = obterToken();
        if (token == null) {
            Toast.makeText(this, "Entre na sua conta online para gerar um código de amizade.", Toast.LENGTH_LONG).show();
            return;
        }
        btnGerarCodigo.setEnabled(false);
        btnGerarCodigo.setText("Gerando código...");
        executor.execute(() -> {
            try {
                JSONObject resposta = BackendApi.gerarCodigoAmizade(getApplicationContext(), token);
                String codigo = resposta.optString("codigo", "");
                long validadeMs = resposta.optLong("expiraEm", System.currentTimeMillis() + 300000L);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnGerarCodigo.setEnabled(true);
                    btnGerarCodigo.setText("Gerar novo código · 5 minutos");
                    if (codigo.length() != 8) {
                        validadeCodigo.setText("O servidor não retornou um código válido.");
                        return;
                    }
                    codigoAmizade.setText(codigo);
                    iniciarContagemCodigo(Math.max(0L,
                            validadeMs - System.currentTimeMillis()));
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    btnGerarCodigo.setEnabled(true);
                    btnGerarCodigo.setText("Gerar código · 5 minutos");
                    Toast.makeText(this, mensagemErro(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void iniciarContagemCodigo(long duracaoMs) {
        if (timerCodigo != null) timerCodigo.cancel();
        if (duracaoMs <= 0) {
            codigoAmizade.setText("••••••••");
            validadeCodigo.setText("Código expirado. Gere outro para adicionar um amigo.");
            return;
        }
        timerCodigo = new CountDownTimer(duracaoMs, 1000L) {
            @Override public void onTick(long millisUntilFinished) {
                long segundos = Math.max(0, millisUntilFinished / 1000L);
                validadeCodigo.setText(String.format(Locale.ROOT,
                        "Compartilhe antes que expire · %02d:%02d restantes", segundos / 60, segundos % 60));
            }

            @Override public void onFinish() {
                codigoAmizade.setText("••••••••");
                validadeCodigo.setText("Código expirado. Gere outro para adicionar um amigo.");
            }
        }.start();
    }

    private void abrirDialogAdicionarAmigo() {
        EditText campo = new EditText(this);
        campo.setHint("Código de 8 dígitos");
        campo.setInputType(InputType.TYPE_CLASS_NUMBER);
        campo.setSingleLine(true);
        campo.setTextSize(20f);
        campo.setGravity(Gravity.CENTER);
        campo.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        int padding = dp(18);
        campo.setPadding(padding, dp(12), padding, dp(12));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(24), dp(8), dp(24), 0);
        wrapper.addView(campo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Adicionar amigo")
                .setMessage("Digite o código temporário que seu amigo gerou. Ele só pode ser resgatado uma vez.")
                .setView(wrapper)
                .setNegativeButton("Cancelar", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Adicionar", (dialog, which) -> resgatarCodigo(campo.getText().toString().trim()))
                .show();
    }

    private void resgatarCodigo(String codigo) {
        if (!codigo.matches("\\d{8}")) {
            Toast.makeText(this, "O código deve conter exatamente 8 números.", Toast.LENGTH_LONG).show();
            return;
        }
        String token = obterToken();
        if (token == null) {
            Toast.makeText(this, "Entre na sua conta online para adicionar amigos.", Toast.LENGTH_LONG).show();
            return;
        }
        btnAdicionarAmigo.setEnabled(false);
        executor.execute(() -> {
            try {
                JSONObject resposta = BackendApi.resgatarCodigoAmizade(getApplicationContext(), token, codigo);
                JSONObject amigo = resposta.optJSONObject("amigo");
                String nomeAmigo = amigo == null ? "jogador" : amigo.optString("nome", "jogador");
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    btnAdicionarAmigo.setEnabled(true);
                    Toast.makeText(this, nomeAmigo + " foi adicionado à sua lista de amigos!", Toast.LENGTH_LONG).show();
                    selecionarAba(true);
                    carregarAmigos();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    btnAdicionarAmigo.setEnabled(true);
                    Toast.makeText(this, mensagemErro(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void carregarAmigos() {
        String token = obterToken();
        if (token == null) {
            estadoAmigos.setText("Entre na conta online para carregar sua lista de amigos.");
            listaAmigos.removeAllViews();
            return;
        }
        btnAtualizarAmigos.setEnabled(false);
        estadoAmigos.setText("Buscando seus amigos...");
        listaAmigos.removeAllViews();
        executor.execute(() -> {
            try {
                JSONObject resposta = BackendApi.listarAmigos(getApplicationContext(), token);
                JSONArray amigos = resposta.optJSONArray("amigos");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnAtualizarAmigos.setEnabled(true);
                    renderizarAmigos(amigos == null ? new JSONArray() : amigos);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    btnAtualizarAmigos.setEnabled(true);
                    estadoAmigos.setText(mensagemErro(e));
                });
            }
        });
    }

    private void renderizarAmigos(JSONArray amigos) {
        listaAmigos.removeAllViews();
        if (amigos.length() == 0) {
            estadoAmigos.setText("Você ainda não tem amigos adicionados. Gere um código ou resgate o código de outro jogador.");
            return;
        }
        estadoAmigos.setText(amigos.length() + (amigos.length() == 1 ? " amigo conectado" : " amigos conectados"));
        for (int i = 0; i < amigos.length(); i++) {
            JSONObject amigo = amigos.optJSONObject(i);
            if (amigo == null) continue;
            listaAmigos.addView(criarCartaoAmigo(amigo));
        }
    }

    private View criarCartaoAmigo(JSONObject amigo) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.bg_card_perfil);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout topo = new LinearLayout(this);
        topo.setGravity(Gravity.CENTER_VERTICAL);
        topo.setOrientation(LinearLayout.HORIZONTAL);
        ImageView foto = new ImageView(this);
        LinearLayout.LayoutParams fotoParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        fotoParams.rightMargin = dp(12);
        foto.setLayoutParams(fotoParams);
        foto.setBackgroundResource(R.drawable.bg_avatar_perfil);
        foto.setClipToOutline(true);
        foto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String avatarId = amigo.optString("avatarId", "avatar_01");
        foto.setImageResource(com.example.smarttennisapp.util.AvatarCatalogo.drawableId(avatarId));
        topo.addView(foto);

        LinearLayout identificacao = new LinearLayout(this);
        identificacao.setOrientation(LinearLayout.VERTICAL);
        topo.addView(identificacao, new LinearLayout.LayoutParams(0, -2, 1));
        TextView nome = texto(amigo.optString("nome", "Jogador"), 17, R.color.text_primary, true);
        identificacao.addView(nome);
        TextView nivel = texto("NÍVEL " + amigo.optInt("nivel", 1) + "  ·  " + amigo.optInt("xp", 0) + " XP", 12, R.color.accent, true);
        LinearLayout.LayoutParams nivelParams = new LinearLayout.LayoutParams(-2, -2);
        nivelParams.topMargin = dp(4);
        identificacao.addView(nivel, nivelParams);
        card.addView(topo);

        TextView tituloRecentes = texto("ÚLTIMOS DESAFIOS", 10, R.color.text_muted, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(16);
        card.addView(tituloRecentes, titleParams);

        JSONArray recentes = amigo.optJSONArray("ultimosDesafios");
        if (recentes == null || recentes.length() == 0) {
            TextView vazio = texto("Ainda não concluiu desafios sincronizados.", 12, R.color.text_secondary, false);
            LinearLayout.LayoutParams vazioParams = new LinearLayout.LayoutParams(-1, -2);
            vazioParams.topMargin = dp(7);
            card.addView(vazio, vazioParams);
        } else {
            int limite = Math.min(5, recentes.length());
            for (int i = 0; i < limite; i++) {
                JSONObject desafio = recentes.optJSONObject(i);
                if (desafio == null) continue;
                String linha = desafio.optString("nome", "Desafio") + "\n"
                        + desafio.optInt("pontuacao", 0) + " pts  ·  "
                        + formatarTempo(desafio.optInt("tempoSegundos", 0));
                TextView resultado = texto(linha, 12, R.color.text_primary, false);
                resultado.setPadding(dp(10), dp(8), dp(10), dp(8));
                resultado.setBackgroundResource(R.drawable.bg_card_treino_execucao);
                LinearLayout.LayoutParams resultadoParams = new LinearLayout.LayoutParams(-1, -2);
                resultadoParams.topMargin = dp(6);
                card.addView(resultado, resultadoParams);
            }
        }
        return card;
    }

    private TextView texto(String valor, int tamanho, int cor, boolean negrito) {
        TextView view = new TextView(this);
        view.setText(valor);
        view.setTextSize(tamanho);
        view.setTextColor(ContextCompat.getColor(this, cor));
        if (negrito) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private String formatarTempo(int segundos) {
        int minutos = Math.max(0, segundos) / 60;
        int resto = Math.max(0, segundos) % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutos, resto);
    }

    private String mensagemErro(IOException erro) {
        return erro.getMessage() == null ? "Falha de comunicação com o servidor." : erro.getMessage();
    }

    private int dp(int valor) {
        return (int) (valor * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (timerCodigo != null) timerCodigo.cancel();
        executor.shutdown();
        super.onDestroy();
    }
}
