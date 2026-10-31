package com.example.smarttennisapp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import com.example.smarttennisapp.backend.BackendApi;
import com.example.smarttennisapp.backend.SessaoRemota;
import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.util.SessaoUsuario;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exibe o placar global ou a classificação da melhor execução de um desafio. */
public class RankingActivity extends BaseActivity {
    public static final String EXTRA_MODO = "ranking_modo";
    public static final String EXTRA_DESAFIO_ID = "ranking_desafio_id";
    public static final String EXTRA_DESAFIO_NOME = "ranking_desafio_nome";
    public static final String MODO_GLOBAL = "global";
    public static final String MODO_DESAFIO = "desafio";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean modoDesafio;
    private String desafioId = "";
    private String desafioNome = "";

    private TextView txtTitulo;
    private TextView txtSubtitulo;
    private TextView txtDestaqueNumero;
    private TextView txtDestaqueDescricao;
    private TextView txtTotal;
    private TextView txtVazio;
    private TextView txtPodioTitulo;
    private TextView txtListaTitulo;
    private TextView txtMinhaPosicaoTitulo;
    private LinearLayout containerPodio;
    private LinearLayout containerLista;
    private LinearLayout containerMinhaPosicao;
    private ProgressBar progresso;
    private Button btnAtualizar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ranking);

        modoDesafio = MODO_DESAFIO.equals(getIntent().getStringExtra(EXTRA_MODO));
        desafioId = safe(getIntent().getStringExtra(EXTRA_DESAFIO_ID));
        desafioNome = safe(getIntent().getStringExtra(EXTRA_DESAFIO_NOME));

        txtTitulo = findViewById(R.id.txtTituloRanking);
        txtSubtitulo = findViewById(R.id.txtSubtituloRanking);
        txtDestaqueNumero = findViewById(R.id.txtDestaqueNumeroRanking);
        txtDestaqueDescricao = findViewById(R.id.txtDestaqueDescricaoRanking);
        txtTotal = findViewById(R.id.txtTotalJogadoresRanking);
        txtVazio = findViewById(R.id.txtVazioRanking);
        txtPodioTitulo = findViewById(R.id.txtPodioTitulo);
        txtListaTitulo = findViewById(R.id.txtListaTituloRanking);
        txtMinhaPosicaoTitulo = findViewById(R.id.txtMinhaPosicaoTituloRanking);
        containerPodio = findViewById(R.id.containerPodioRanking);
        containerLista = findViewById(R.id.containerRankingLista);
        containerMinhaPosicao = findViewById(R.id.containerMinhaPosicaoRanking);
        progresso = findViewById(R.id.progressoRanking);
        btnAtualizar = findViewById(R.id.btnAtualizarRanking);

        ImageButton voltar = findViewById(R.id.btnVoltarRanking);
        voltar.setOnClickListener(v -> finish());
        btnAtualizar.setOnClickListener(v -> carregarRanking());

        if (modoDesafio) {
            txtTitulo.setText(desafioNome.isEmpty() ? "Ranking do desafio" : "Ranking do desafio");
            txtSubtitulo.setText(desafioNome.isEmpty()
                    ? "Compare as melhores pontuações deste desafio."
                    : "Desafio: " + desafioNome + ". Veja quem conseguiu a maior pontuação.");
            txtPodioTitulo.setText("🏆  MELHORES MARCAS");
            txtListaTitulo.setText("MELHORES RESULTADOS");
        } else {
            txtTitulo.setText("Ranking Global");
            txtSubtitulo.setText("O placar dos jogadores que evoluem a cada treino.");
            txtPodioTitulo.setText("🏆  PÓDIO GLOBAL");
            txtListaTitulo.setText("CLASSIFICAÇÃO GERAL");
        }
        carregarRanking();
    }

    private void carregarRanking() {
        progresso.setVisibility(View.VISIBLE);
        btnAtualizar.setEnabled(false);
        txtVazio.setVisibility(View.GONE);
        txtTotal.setText("Atualizando placar...");
        executor.execute(() -> {
            int localId = SessaoUsuario.obterId(getApplicationContext());
            String token = SessaoRemota.obterToken(getApplicationContext(), localId);
            if (localId <= 0 || token == null) {
                runOnUiThread(() -> mostrarFalha(
                        "Esta conta ainda não está conectada ao backend. Entre na conta online para vincular o perfil e habilitar o ranking."));
                return;
            }
            try {
                // Envie o histórico pendente antes de consultar o placar; o treino continua offline-first.
                SyncManager.sincronizarAgora(getApplicationContext(), localId);
                token = SessaoRemota.obterToken(getApplicationContext(), localId);
                JSONObject resposta = modoDesafio
                        ? BackendApi.obterRankingDesafio(getApplicationContext(), token, desafioId, desafioNome, 50)
                        : BackendApi.obterRankingGlobal(getApplicationContext(), token, 50);
                JSONObject finalResposta = resposta;
                runOnUiThread(() -> mostrarRanking(finalResposta, localId));
            } catch (IOException e) {
                String mensagem = e.getMessage() == null ? "Falha de comunicação com o servidor." : e.getMessage();
                runOnUiThread(() -> mostrarFalha(mensagem + " Verifique a conexão com a internet e tente novamente."));
            }
        });
    }

    private void mostrarRanking(JSONObject resposta, int localId) {
        if (isFinishing() || isDestroyed()) return;
        progresso.setVisibility(View.GONE);
        btnAtualizar.setEnabled(true);
        JSONArray rankingJson = resposta.optJSONArray("ranking");
        List<JSONObject> ranking = new ArrayList<>();
        if (rankingJson != null) {
            for (int i = 0; i < rankingJson.length(); i++) {
                JSONObject item = rankingJson.optJSONObject(i);
                if (item != null) ranking.add(item);
            }
        }
        JSONObject meuRanking = resposta.optJSONObject("meuRanking");
        int total = resposta.optInt("totalJogadores", ranking.size());
        txtTotal.setText(total + (total == 1 ? " jogador classificado" : " jogadores classificados"));

        if (meuRanking != null) {
            int posicao = meuRanking.optInt("posicao", 0);
            txtDestaqueNumero.setText(posicao > 0 ? "#" + posicao : "—");
            if (modoDesafio) {
                txtDestaqueDescricao.setText("Sua melhor marca: "
                        + formatarNumero(meuRanking.optInt("pontuacao", 0)) + " pts · "
                        + formatarTempo(meuRanking.optInt("tempoSegundos", 0)));
            } else {
                txtDestaqueDescricao.setText("Sua pontuação acumulada: "
                        + formatarNumero(meuRanking.optInt("pontuacaoTotal", 0)) + " pts");
            }
        } else {
            txtDestaqueNumero.setText("—");
            txtDestaqueDescricao.setText(modoDesafio
                    ? "Conclua este desafio para entrar na classificação."
                    : "Conclua um treino guiado para entrar na classificação global.");
        }

        renderizarPodio(ranking);
        containerLista.removeAllViews();
        containerMinhaPosicao.removeAllViews();
        txtMinhaPosicaoTitulo.setVisibility(View.GONE);
        txtVazio.setVisibility(ranking.isEmpty() ? View.VISIBLE : View.GONE);
        txtVazio.setText(modoDesafio
                ? "Ainda não há resultados sincronizados para este desafio. Complete uma sessão e sincronize para iniciar o ranking."
                : "Ainda não há sessões concluídas sincronizadas. Complete um treino guiado para inaugurar o ranking global.");
        txtPodioTitulo.setVisibility(ranking.isEmpty() ? View.GONE : View.VISIBLE);
        containerPodio.setVisibility(ranking.isEmpty() ? View.GONE : View.VISIBLE);
        txtListaTitulo.setVisibility(ranking.isEmpty() ? View.GONE : View.VISIBLE);

        int meuIdServidor = SessaoRemota.obterIdServidor(getApplicationContext(), localId);
        boolean euNoTop = false;
        for (JSONObject jogador : ranking) {
            boolean souEu = jogador.optInt("id", -1) == meuIdServidor;
            if (souEu) euNoTop = true;
            containerLista.addView(criarLinhaRanking(jogador, souEu));
        }
        if (meuRanking != null && !euNoTop) {
            txtMinhaPosicaoTitulo.setVisibility(View.VISIBLE);
            containerMinhaPosicao.addView(criarLinhaRanking(meuRanking, true));
        }
    }

    private void mostrarFalha(String mensagem) {
        if (isFinishing() || isDestroyed()) return;
        progresso.setVisibility(View.GONE);
        btnAtualizar.setEnabled(true);
        txtDestaqueNumero.setText("—");
        txtDestaqueDescricao.setText("Não foi possível carregar seu desempenho.");
        txtTotal.setText("Ranking indisponível");
        txtVazio.setText(mensagem);
        txtVazio.setVisibility(View.VISIBLE);
        txtPodioTitulo.setVisibility(View.GONE);
        containerPodio.setVisibility(View.GONE);
        txtListaTitulo.setVisibility(View.GONE);
        containerLista.removeAllViews();
        containerMinhaPosicao.removeAllViews();
        txtMinhaPosicaoTitulo.setVisibility(View.GONE);
    }

    private void renderizarPodio(List<JSONObject> jogadores) {
        containerPodio.removeAllViews();
        if (jogadores.isEmpty()) return;
        List<JSONObject> ordem = new ArrayList<>();
        if (jogadores.size() >= 2) ordem.add(jogadores.get(1));
        ordem.add(jogadores.get(0));
        if (jogadores.size() >= 3) ordem.add(jogadores.get(2));
        for (JSONObject jogador : ordem) {
            int posicao = jogador.optInt("posicao", 0);
            int altura = posicao == 1 ? 142 : (posicao == 2 ? 124 : 110);
            LinearLayout coluna = new LinearLayout(this);
            coluna.setOrientation(LinearLayout.VERTICAL);
            coluna.setGravity(Gravity.CENTER);
            coluna.setPadding(dp(7), dp(12), dp(7), dp(12));
            coluna.setMinimumHeight(dp(altura));
            int cor = posicao == 1 ? Color.parseColor("#47391D")
                    : (posicao == 2 ? Color.parseColor("#293744") : Color.parseColor("#44302A"));
            coluna.setBackground(fundoArredondado(cor, Color.parseColor("#344250"), 16));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(altura), 1f);
            params.setMargins(dp(4), 0, dp(4), 0);
            containerPodio.addView(coluna, params);

            TextView medalha = new TextView(this);
            medalha.setGravity(Gravity.CENTER);
            medalha.setText(posicao == 1 ? "🥇" : (posicao == 2 ? "🥈" : "🥉"));
            medalha.setTextSize(24);
            coluna.addView(medalha);

            TextView nome = new TextView(this);
            nome.setText(jogador.optString("nome", "Jogador"));
            nome.setTextColor(Color.WHITE);
            nome.setTextSize(12);
            nome.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            nome.setGravity(Gravity.CENTER);
            nome.setMaxLines(2);
            nome.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nomeP = new LinearLayout.LayoutParams(-1, -2);
            nomeP.topMargin = dp(6);
            coluna.addView(nome, nomeP);

            TextView pontos = new TextView(this);
            pontos.setText(formatarPontos(jogador));
            pontos.setTextColor(posicao == 1 ? Color.parseColor("#FFC857") : Color.parseColor("#E6EDF3"));
            pontos.setTextSize(12);
            pontos.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            pontos.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pontosP = new LinearLayout.LayoutParams(-1, -2);
            pontosP.topMargin = dp(4);
            coluna.addView(pontos, pontosP);
        }
    }

    private View criarLinhaRanking(JSONObject jogador, boolean souEu) {
        LinearLayout linha = new LinearLayout(this);
        linha.setGravity(Gravity.CENTER_VERTICAL);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setPadding(dp(13), dp(13), dp(13), dp(13));
        linha.setBackground(fundoArredondado(
                souEu ? Color.parseColor("#26233A") : Color.parseColor("#17212B"),
                souEu ? Color.parseColor("#B17AFF") : Color.parseColor("#283743"), 14));
        LinearLayout.LayoutParams linhaP = new LinearLayout.LayoutParams(-1, -2);
        linhaP.bottomMargin = dp(8);
        linha.setLayoutParams(linhaP);

        TextView pos = new TextView(this);
        int posicao = jogador.optInt("posicao", 0);
        pos.setText("#" + posicao);
        pos.setTextColor(souEu ? Color.parseColor("#B17AFF") : Color.parseColor("#A6B0BA"));
        pos.setTextSize(16);
        pos.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pos.setGravity(Gravity.CENTER);
        pos.setMinWidth(dp(43));
        linha.addView(pos);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoP = new LinearLayout.LayoutParams(0, -2, 1f);
        infoP.leftMargin = dp(9);
        infoP.rightMargin = dp(8);
        linha.addView(info, infoP);

        TextView nome = new TextView(this);
        nome.setText(jogador.optString("nome", "Jogador") + (souEu ? "  ·  VOCÊ" : ""));
        nome.setTextColor(Color.parseColor("#F4F7FA"));
        nome.setTextSize(14);
        nome.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nome.setMaxLines(1);
        nome.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(nome);

        TextView detalhes = new TextView(this);
        detalhes.setText(modoDesafio
                ? jogador.optInt("execucoes", 0) + " execuções · " + formatarTempo(jogador.optInt("tempoSegundos", 0))
                : jogador.optInt("sessoesConcluidas", 0) + " sessões · " + jogador.optInt("desafiosConcluidos", 0) + " desafios");
        detalhes.setTextColor(Color.parseColor("#A6B0BA"));
        detalhes.setTextSize(11);
        LinearLayout.LayoutParams detalhesP = new LinearLayout.LayoutParams(-1, -2);
        detalhesP.topMargin = dp(4);
        info.addView(detalhes, detalhesP);

        TextView score = new TextView(this);
        score.setText(formatarPontos(jogador));
        score.setTextColor(Color.parseColor("#B17AFF"));
        score.setTextSize(15);
        score.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        score.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        linha.addView(score);
        return linha;
    }

    private String formatarPontos(JSONObject jogador) {
        int pontos = modoDesafio
                ? jogador.optInt("pontuacao", 0)
                : jogador.optInt("pontuacaoTotal", 0);
        return formatarNumero(pontos) + " pts";
    }

    private String formatarNumero(int numero) {
        return String.format(Locale.getDefault(), "%,d", numero).replace(',', '.');
    }

    private String formatarTempo(int segundos) {
        int seguro = Math.max(0, segundos);
        return String.format(Locale.getDefault(), "%02d:%02d", seguro / 60, seguro % 60);
    }

    private GradientDrawable fundoArredondado(int cor, int borda, int raioDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(cor);
        drawable.setCornerRadius(dp(raioDp));
        drawable.setStroke(dp(1), borda);
        return drawable;
    }

    private int dp(int valor) {
        return (int) (valor * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
