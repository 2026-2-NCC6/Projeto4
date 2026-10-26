package com.example.smarttennisapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.DesafioEntity;
import com.example.smarttennisapp.database.HistoricoTreinoEntity;
import com.example.smarttennisapp.util.SessaoUsuario;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Estatísticas reais de Treino Livre e Treino Guiado, calculadas a partir do Room. */
public class Estatiscas extends BaseActivity {
    private static final int XP_POR_NIVEL = 500;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView txtTotalSessoes, txtTempoTotal, txtTotalGolpes, txtPontosTotais;
    private TextView txtNivel, txtXp, txtPrecisao, txtMelhorPontuacao;
    private TextView txtSessoesGuiadas, txtSessoesLivres, txtEstadoVazio, txtModalidadeMaisUsada;
    private TextView txtSaques, txtForehands, txtBackhands;
    private ProgressBar barraXp, barraSaques, barraForehands, barraBackhands;
    private LinearLayout containerSemana, containerInsights, containerRecentes;
    private boolean carregamentoEmCurso;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_estatiscas);
        vincularViews();
        configurarNavegacao();
    }

    @Override
    protected void onResume() {
        super.onResume();
        carregarDados();
    }

    private void vincularViews() {
        txtTotalSessoes = findViewById(R.id.txtTotalSessoes);
        txtTempoTotal = findViewById(R.id.txtTempoTotal);
        txtTotalGolpes = findViewById(R.id.txtTotalGolpesStats);
        txtPontosTotais = findViewById(R.id.txtPontosTotaisStats);
        txtNivel = findViewById(R.id.txtNivelStats);
        txtXp = findViewById(R.id.txtXpStats);
        barraXp = findViewById(R.id.barraXpStats);
        txtPrecisao = findViewById(R.id.txtPrecisaoStats);
        txtMelhorPontuacao = findViewById(R.id.txtMelhorPontuacaoStats);
        txtSessoesGuiadas = findViewById(R.id.txtSessoesGuiadas);
        txtSessoesLivres = findViewById(R.id.txtSessoesLivres);
        txtModalidadeMaisUsada = findViewById(R.id.txtModalidadeMaisUsadaStats);
        txtSaques = findViewById(R.id.txtSaquesStats);
        txtForehands = findViewById(R.id.txtForehandsStats);
        txtBackhands = findViewById(R.id.txtBackhandsStats);
        barraSaques = findViewById(R.id.barraSaquesStats);
        barraForehands = findViewById(R.id.barraForehandsStats);
        barraBackhands = findViewById(R.id.barraBackhandsStats);
        containerSemana = findViewById(R.id.containerSemanaStats);
        containerInsights = findViewById(R.id.containerInsightsStats);
        containerRecentes = findViewById(R.id.containerRecentesStats);
        txtEstadoVazio = findViewById(R.id.txtEstadoVazioStats);
    }

    private void configurarNavegacao() {
        findViewById(R.id.btnVoltarEstatisticas).setOnClickListener(v -> finish());
        findViewById(R.id.navTreinosStats).setOnClickListener(v -> finish());
        findViewById(R.id.navEstatisticasStats).setOnClickListener(v -> carregarDados());
        findViewById(R.id.navPerfilStats).setOnClickListener(v ->
                startActivity(new Intent(this, MeuPerfil.class)));
    }

    private void carregarDados() {
        if (carregamentoEmCurso) return;
        int usuarioId = SessaoUsuario.obterId(this);
        if (usuarioId <= 0) {
            mostrarEstadoVazio("Entre na sua conta para consultar suas estatísticas.");
            return;
        }
        carregamentoEmCurso = true;
        executor.execute(() -> {
            try {
                AppDatabase db = DatabaseProvider.getInstance(getApplicationContext());
                SyncManager.sincronizarAgora(this, usuarioId);
                List<HistoricoTreinoEntity> historico = db.treinoDAO().listarHistoricoUsuario(usuarioId);
                List<DesafioEntity> desafios = db.desafioDAO().listarPorUsuario(usuarioId);
                Dados dados = calcularDados(historico, desafios, usuarioId);
                runOnUiThread(() -> {
                    carregamentoEmCurso = false;
                    if (!isFinishing() && !isDestroyed()) renderizar(dados);
                });
            } catch (Exception erro) {
                runOnUiThread(() -> {
                    carregamentoEmCurso = false;
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Não foi possível carregar as estatísticas.", Toast.LENGTH_LONG).show();
                        mostrarEstadoVazio("O histórico não pôde ser carregado. Tente abrir esta tela novamente.");
                    }
                });
            }
        });
    }

    private Dados calcularDados(List<HistoricoTreinoEntity> historico,
                                List<DesafioEntity> desafios, int usuarioId) {
        Dados d = new Dados();
        d.nomesDesafios = new HashMap<>();
        Set<String> idsDesafiosAtivos = new HashSet<>();
        if (desafios != null) {
            for (DesafioEntity desafio : desafios) {
                if (desafio != null && desafio.id != null) {
                    idsDesafiosAtivos.add(desafio.id);
                    d.nomesDesafios.put(desafio.id, desafio.nome);
                }
            }
        }

        if (historico != null) {
            for (HistoricoTreinoEntity h : historico) {
                if (h != null && pertenceAoTreinoGuiadoOuLivre(h.treinoId, idsDesafiosAtivos)) {
                    d.historico.add(h);
                }
            }
        }
        Collections.sort(d.historico, (a, b) -> Long.compare(b.dataConclusao, a.dataConclusao));

        long agora = System.currentTimeMillis();
        double acertos = 0, tentativas = 0;
        for (HistoricoTreinoEntity h : d.historico) {
            d.sessoes++;
            d.tempoSegundos += Math.max(0, h.tempoSegundos);
            d.saques += Math.max(0, h.saques);
            d.forehands += Math.max(0, h.forehands);
            d.backhands += Math.max(0, h.backhands);
            acumularClassesDetalhadas(d, h);

            boolean livre = ehTreinoLivre(h.treinoId);
            if (livre) {
                d.sessoesLivres++;
            } else {
                d.sessoesGuiadas++;
                d.pontosTotais += Math.max(0, h.pontuacao);
                d.melhorPontuacao = Math.max(d.melhorPontuacao, h.pontuacao);
                if (h.concluido) d.desafiosConcluidos++;
                if (h.pontuacao > 0) d.sessoesPontuadas.add(h);

                int tentativasSessao = h.golpesRecebidos > 0 ? h.golpesRecebidos : h.totalGolpes;
                if (tentativasSessao > 0) {
                    tentativas += tentativasSessao;
                    acertos += Math.min(Math.max(0, h.golpesCorretos), tentativasSessao);
                }
            }

            int diasAtras = diasEntreDatas(h.dataConclusao, agora);
            if (diasAtras >= 0 && diasAtras < 7) {
                d.atividadeUltimosSeteDias++;
                d.sessoesPorDia[6 - diasAtras]++;
            } else if (diasAtras >= 7 && diasAtras < 14) {
                d.atividadeSeteDiasAnteriores++;
            }
        }

        d.totalGolpes = d.saques + d.forehands + d.backhands + d.smashes + d.amortidas + d.lobs;
        d.precisao = tentativas > 0 ? (int) Math.round(acertos * 100.0 / tentativas) : -1;
        d.sequenciaDias = calcularSequenciaDias(d.historico, agora);
        SharedPreferences xpPrefs = getSharedPreferences("SmartTennisGamificacao", MODE_PRIVATE);
        d.xp = xpPrefs.getInt("xp_total_" + usuarioId, 0);
        d.nivel = (int) Math.max(1L, d.xp / XP_POR_NIVEL + 1L);
        d.xpNoNivel = (int) (d.xp % XP_POR_NIVEL);
        return d;
    }

    /** Whitelist do que esta tela cobre: treino livre, catálogo offline e desafios guiados. */
    private boolean pertenceAoTreinoGuiadoOuLivre(String treinoId, Set<String> idsDesafiosAtivos) {
        if (treinoId == null) return false;
        if (ehTreinoLivre(treinoId)) return true;
        if (treinoId.startsWith("offline_") || "offline_fundamentos".equalsIgnoreCase(treinoId)) return true;
        if (idsDesafiosAtivos.contains(treinoId)) return true;
        // UUIDs são usados pelos desafios personalizados; mantém histórico mesmo se o desafio for excluído.
        return UUID_PATTERN.matcher(treinoId).matches();
    }

    private boolean ehTreinoLivre(String treinoId) {
        return "TREINO_LIVRE".equalsIgnoreCase(treinoId);
    }

    private int diasEntreDatas(long data, long referencia) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(referencia);
        zerarHorario(a);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(data);
        zerarHorario(b);
        return (int) ((a.getTimeInMillis() - b.getTimeInMillis()) / (24L * 60 * 60 * 1000));
    }

    private void zerarHorario(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int calcularSequenciaDias(List<HistoricoTreinoEntity> historico, long agora) {
        if (historico.isEmpty()) return 0;
        List<Long> dias = new ArrayList<>();
        for (HistoricoTreinoEntity h : historico) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(h.dataConclusao);
            zerarHorario(c);
            long dia = c.getTimeInMillis();
            if (!dias.contains(dia)) dias.add(dia);
        }
        Collections.sort(dias, Collections.reverseOrder());
        if (dias.isEmpty()) return 0;
        int atrasoUltimaSessao = diasEntreDatas(dias.get(0), agora);
        if (atrasoUltimaSessao < 0 || atrasoUltimaSessao > 1) return 0;

        int sequencia = 1;
        for (int i = 1; i < dias.size(); i++) {
            int diferenca = diasEntreDatas(dias.get(i), dias.get(i - 1));
            if (diferenca == 1) sequencia++;
            else if (diferenca > 1) break;
        }
        return sequencia;
    }

    private void renderizar(Dados d) {
        txtEstadoVazio.setVisibility(d.sessoes == 0 ? View.VISIBLE : View.GONE);
        if (d.sessoes == 0) {
            txtEstadoVazio.setText("Seu painel está pronto! Faça um treino guiado ou livre com a raquete conectada para começar a formar seu histórico.");
        }
        txtTotalSessoes.setText(String.valueOf(d.sessoes));
        txtTempoTotal.setText(formatarDuracao(d.tempoSegundos));
        txtTotalGolpes.setText(String.format(Locale.getDefault(), "%,d", d.totalGolpes));
        txtPontosTotais.setText(String.format(Locale.getDefault(), "%,d", d.pontosTotais));
        txtNivel.setText("NÍVEL " + d.nivel);
        txtXp.setText(d.xpNoNivel + " / " + XP_POR_NIVEL + " XP para o próximo nível · " + d.xp + " XP no total");
        barraXp.setMax(XP_POR_NIVEL);
        barraXp.setProgress(d.xpNoNivel);
        txtPrecisao.setText(d.precisao >= 0 ? d.precisao + "%" : "—");
        txtMelhorPontuacao.setText(d.sessoesPontuadas.isEmpty() ? "—" : String.valueOf(d.melhorPontuacao));
        txtSessoesGuiadas.setText(String.valueOf(d.sessoesGuiadas));
        txtSessoesLivres.setText(String.valueOf(d.sessoesLivres));
        txtModalidadeMaisUsada.setText(d.sessoes == 0 ? "—" : d.sessoesGuiadas > d.sessoesLivres ? "Guiado" : d.sessoesLivres > d.sessoesGuiadas ? "Livre" : "Empate");
        txtSaques.setText(String.valueOf(d.saques));
        txtForehands.setText(String.valueOf(d.forehands));
        txtBackhands.setText(String.valueOf(d.backhands));
        int maxGolpes = Math.max(1, Math.max(d.saques, Math.max(d.forehands, d.backhands)));
        configurarBarra(barraSaques, d.saques, maxGolpes, R.color.st_accent);
        configurarBarra(barraForehands, d.forehands, maxGolpes, R.color.st_success);
        configurarBarra(barraBackhands, d.backhands, maxGolpes, R.color.st_warning);
        renderizarSemana(d);
        renderizarInsights(d);
        renderizarRecentes(d);
    }

    private void configurarBarra(ProgressBar barra, int valor, int maximo, int cor) {
        barra.setMax(Math.max(1, maximo));
        barra.setProgress(Math.max(0, valor));
        barra.setProgressTintList(ColorStateList.valueOf(getColor(cor)));
    }

    private void renderizarSemana(Dados d) {
        containerSemana.removeAllViews();
        String[] nomes = {"Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"};
        Calendar hoje = Calendar.getInstance();
        int indiceHoje = (hoje.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int max = 1;
        for (int n : d.sessoesPorDia) max = Math.max(max, n);
        for (int i = 0; i < 7; i++) {
            int pos = (indiceHoje - 6 + i + 7) % 7;
            LinearLayout linha = new LinearLayout(this);
            linha.setGravity(Gravity.CENTER_VERTICAL);
            linha.setOrientation(LinearLayout.HORIZONTAL);
            linha.setPadding(0, dp(4), 0, dp(4));
            linha.addView(criarTexto(nomes[pos], 12, R.color.st_text_secondary, false),
                    new LinearLayout.LayoutParams(dp(35), ViewGroup.LayoutParams.WRAP_CONTENT));
            ProgressBar barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            barra.setMax(max);
            barra.setProgress(d.sessoesPorDia[i]);
            barra.setProgressTintList(ColorStateList.valueOf(getColor(R.color.st_accent)));
            barra.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.st_surface_alt)));
            LinearLayout.LayoutParams lpBarra = new LinearLayout.LayoutParams(0, dp(8), 1f);
            lpBarra.setMargins(dp(8), 0, dp(10), 0);
            linha.addView(barra, lpBarra);
            TextView valor = criarTexto(String.valueOf(d.sessoesPorDia[i]), 12, R.color.st_text_primary, true);
            valor.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            linha.addView(valor, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            containerSemana.addView(linha);
        }
    }

    private void renderizarInsights(Dados d) {
        containerInsights.removeAllViews();
        if (d.sessoes == 0) {
            adicionarInsight("🎾", "Comece pelo primeiro treino", "Conclua uma sessão com a raquete conectada. O SmartTennis vai guardar seus golpes e formar comparações reais ao longo do tempo.");
            return;
        }
        if (d.precisao >= 0) {
            String corpo = d.precisao >= 85
                    ? "Excelente controle: " + d.precisao + "% dos golpes tentados nos desafios foram validados. Aumente gradualmente o tamanho das sequências."
                    : d.precisao >= 65
                    ? "Você validou " + d.precisao + "% dos golpes tentados. Mantenha a técnica e busque aumentar a sequência de acertos sem acelerar demais."
                    : "A validação está em " + d.precisao + "%. Diminua o ritmo e priorize executar o golpe esperado antes de tentar sequências longas.";
            adicionarInsight("🎯", "Precisão nos desafios", corpo);
        }
        if (d.totalGolpes >= 6) {
            int[] contagens = {d.saques, d.forehands, d.backhands};
            String[] nomes = {"saque", "forehand", "backhand"};
            int maior = 0, menor = 0;
            for (int i = 1; i < contagens.length; i++) {
                if (contagens[i] > contagens[maior]) maior = i;
                if (contagens[i] < contagens[menor]) menor = i;
            }
            adicionarInsight("⚖️", "Equilíbrio técnico", "Seu golpe mais praticado é o " + nomes[maior] + " (" + contagens[maior] + "). O " + nomes[menor] + " aparece menos; inclua uma missão curta focada nele para equilibrar seu repertório.");
        }
        if (d.atividadeUltimosSeteDias > 0 || d.atividadeSeteDiasAnteriores > 0) {
            String comparacao;
            if (d.atividadeUltimosSeteDias > d.atividadeSeteDiasAnteriores) comparacao = "mais sessões que no período anterior";
            else if (d.atividadeUltimosSeteDias < d.atividadeSeteDiasAnteriores) comparacao = "menos sessões que no período anterior";
            else comparacao = "o mesmo número de sessões do período anterior";
            adicionarInsight("📅", "Ritmo semanal", "Você fez " + d.atividadeUltimosSeteDias + " sessão(ões) nos últimos 7 dias, com " + comparacao + " de 7 dias. Procure manter uma rotina sustentável.");
        }
        if (d.sequenciaDias > 0) {
            adicionarInsight("🔥", "Sequência ativa", "Você treinou em " + d.sequenciaDias + (d.sequenciaDias == 1 ? " dia seguido" : " dias seguidos") + ". Uma sessão curta conta: priorize consistência sem deixar a recuperação de lado.");
        }
        if (d.sessoesGuiadas >= 3) {
            int taxaConclusao = (int) Math.round(d.desafiosConcluidos * 100.0 / d.sessoesGuiadas);
            adicionarInsight("🏁", "Conclusão dos desafios", "Você concluiu " + taxaConclusao + "% dos treinos guiados registrados (" + d.desafiosConcluidos + " de " + d.sessoesGuiadas + "). Se a taxa estiver baixa, experimente desafios mais curtos antes dos avançados.");
        }
        String repertorio = textoRepertorioDetalhado(d);
        if (!repertorio.isEmpty()) adicionarInsight("🎾", "Variações de golpe", repertorio);
        String evolucao = textoEvolucaoPontuacao(d.sessoesPontuadas);
        if (!evolucao.isEmpty()) adicionarInsight("📈", "Evolução da pontuação", evolucao);
        String volumeLivre = textoEvolucaoVolumeLivre(d.historico);
        if (!volumeLivre.isEmpty()) adicionarInsight("🎾", "Volume no Treino Livre", volumeLivre);
        if (containerInsights.getChildCount() == 0) {
            adicionarInsight("🚀", "Próximo passo", "Continue alternando treinos livres e guiados. Com mais sessões, será possível comparar melhor a precisão e a evolução do desempenho.");
        }
    }

    private String textoEvolucaoPontuacao(List<HistoricoTreinoEntity> pontuadas) {
        // A comparação usa dois blocos de 3 sessões (6 no total).
        // Com 4 ou 5 sessões, acessar as 3 anteriores causava IndexOutOfBoundsException.
        if (pontuadas == null || pontuadas.size() < 6) return "";
        int bloco = 3;
        double recente = mediaPontuacao(pontuadas, 0, bloco);
        double anterior = mediaPontuacao(pontuadas, bloco, bloco);
        if (anterior <= 0) return "";
        double variacao = (recente - anterior) * 100.0 / anterior;
        if (Math.abs(variacao) < 3) {
            return String.format(Locale.getDefault(), "A média das 3 sessões pontuadas mais recentes está estável: %.0f pontos.", recente);
        }
        return String.format(Locale.getDefault(), "A média das 3 sessões pontuadas mais recentes está %s %.0f%% em relação às 3 anteriores (%.0f contra %.0f pontos).",
                variacao > 0 ? "acima" : "abaixo", Math.abs(variacao), recente, anterior);
    }

    private double mediaPontuacao(List<HistoricoTreinoEntity> lista, int inicio, int quantidade) {
        // Guarda defensiva: nenhuma chamada pode ultrapassar os limites da lista.
        if (lista == null || inicio < 0 || quantidade <= 0 || inicio + quantidade > lista.size()) {
            return 0;
        }
        double soma = 0;
        for (int i = inicio; i < inicio + quantidade; i++) {
            HistoricoTreinoEntity sessao = lista.get(i);
            if (sessao != null) soma += sessao.pontuacao;
        }
        return soma / quantidade;
    }

    private String textoEvolucaoVolumeLivre(List<HistoricoTreinoEntity> historico) {
        List<HistoricoTreinoEntity> livres = new ArrayList<>();
        for (HistoricoTreinoEntity h : historico) if (ehTreinoLivre(h.treinoId)) livres.add(h);
        if (livres.size() < 6) return "";
        double recente = mediaGolpes(livres, 0, 3);
        double anterior = mediaGolpes(livres, 3, 3);
        if (anterior <= 0) return "";
        double variacao = (recente - anterior) * 100.0 / anterior;
        if (Math.abs(variacao) < 5) {
            return String.format(Locale.getDefault(), "Seu volume está consistente: média de %.0f golpes nas 3 sessões mais recentes.", recente);
        }
        return String.format(Locale.getDefault(), "O volume médio nas 3 sessões mais recentes está %s %.0f%% (%.0f contra %.0f golpes por sessão).",
                variacao > 0 ? "acima" : "abaixo", Math.abs(variacao), recente, anterior);
    }

    private double mediaGolpes(List<HistoricoTreinoEntity> lista, int inicio, int quantidade) {
        double soma = 0;
        for (int i = inicio; i < inicio + quantidade; i++) {
            HistoricoTreinoEntity h = lista.get(i);
            soma += contarGolpesClassificados(h);
        }
        return soma / quantidade;
    }

    private void acumularClassesDetalhadas(Dados d, HistoricoTreinoEntity h) {
        JSONObject raiz = lerMetricasGolpes(h);
        JSONObject classes = raiz.optJSONObject("classes");
        if (classes == null) return;
        for (String codigo : new String[]{"SPL", "SSL", "SKK", "SMA", "FHD", "FHS", "FHV", "BHD", "BHS", "BHV", "AMO", "LOB", "RUI"}) {
            JSONObject item = classes.optJSONObject(codigo);
            if (item == null) continue;
            int quantidade = Math.max(0, item.optInt("quantidade", 0));
            if (quantidade <= 0) continue;
            Integer atual = d.contagemPorClasse.get(codigo);
            d.contagemPorClasse.put(codigo, (atual == null ? 0 : atual) + quantidade);
            if ("SMA".equals(codigo)) d.smashes += quantidade;
            else if ("AMO".equals(codigo)) d.amortidas += quantidade;
            else if ("LOB".equals(codigo)) d.lobs += quantidade;
        }
    }

    private JSONObject lerMetricasGolpes(HistoricoTreinoEntity h) {
        try {
            JSONObject raiz = new JSONObject(h.metricasGolpesJson == null ? "{}" : h.metricasGolpesJson);
            if (raiz.optJSONObject("classes") != null) return raiz;
            // Recupera métricas vindas de uma versão de backend que manteve apenas o campo antigo.
            JSONObject antigas = new JSONObject(h.metricasCameraJson == null ? "{}" : h.metricasCameraJson);
            JSONObject espelho = antigas.optJSONObject("metricasGolpes");
            return espelho == null ? raiz : espelho;
        } catch (Exception ignorada) {
            return new JSONObject();
        }
    }

    private int contarGolpesClassificados(HistoricoTreinoEntity h) {
        return Math.max(0, h.saques) + Math.max(0, h.forehands) + Math.max(0, h.backhands)
                + quantidadeClasse(h, "SMA") + quantidadeClasse(h, "AMO") + quantidadeClasse(h, "LOB");
    }

    private int quantidadeClasse(HistoricoTreinoEntity h, String codigo) {
        JSONObject classes = lerMetricasGolpes(h).optJSONObject("classes");
        JSONObject item = classes == null ? null : classes.optJSONObject(codigo);
        return item == null ? 0 : Math.max(0, item.optInt("quantidade", 0));
    }

    private String textoRepertorioDetalhado(Dados d) {
        if (d.contagemPorClasse.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] codigos = {"SPL", "SSL", "SKK", "SMA", "FHD", "FHS", "FHV", "BHD", "BHS", "BHV", "AMO", "LOB", "RUI"};
        String[] nomes = {"Saque plano", "Saque slice", "Saque kick", "Smash", "FH drive", "FH slice", "FH voleio",
                "BH drive", "BH slice", "BH voleio", "Amortida", "Lob", "Ruído/outro"};
        for (int i = 0; i < codigos.length; i++) {
            Integer n = d.contagemPorClasse.get(codigos[i]);
            if (n == null || n <= 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(nomes[i]).append(": ").append(n);
        }
        return sb.toString();
    }

    private void adicionarInsight(String icone, String titulo, String corpo) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.bg_stat_tile);
        TextView cabecalho = criarTexto(icone + "  " + titulo, 14, R.color.st_accent, true);
        TextView texto = criarTexto(corpo, 13, R.color.st_text_primary, false);
        texto.setLineSpacing(dp(2), 1f);
        card.addView(cabecalho);
        LinearLayout.LayoutParams lpTexto = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTexto.topMargin = dp(6);
        card.addView(texto, lpTexto);
        LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCard.bottomMargin = dp(9);
        containerInsights.addView(card, lpCard);
    }

    private void renderizarRecentes(Dados d) {
        containerRecentes.removeAllViews();
        if (d.historico.isEmpty()) {
            TextView vazio = criarTexto("Suas próximas sessões vão aparecer aqui.", 13, R.color.st_text_secondary, false);
            vazio.setPadding(0, dp(8), 0, dp(8));
            containerRecentes.addView(vazio);
            return;
        }
        SimpleDateFormat dataFormatada = new SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault());
        int limite = Math.min(8, d.historico.size());
        for (int i = 0; i < limite; i++) {
            HistoricoTreinoEntity h = d.historico.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(13), dp(11), dp(13), dp(11));
            item.setBackgroundResource(R.drawable.bg_stat_tile);
            TextView nome = criarTexto(nomeTreino(h.treinoId, d.nomesDesafios), 14, R.color.st_text_primary, true);
            String detalhe = dataFormatada.format(h.dataConclusao) + "   ·   " + formatarDuracao(h.tempoSegundos)
                    + (h.pontuacao > 0 ? "   ·   " + h.pontuacao + " pts" : "")
                    + "   ·   " + contarGolpesClassificados(h) + " golpes";
            TextView subtitulo = criarTexto(detalhe, 12, R.color.st_text_secondary, false);
            item.addView(nome);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            item.addView(subtitulo, lp);
            LinearLayout.LayoutParams lpItem = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpItem.bottomMargin = dp(8);
            containerRecentes.addView(item, lpItem);
        }
    }

    private String nomeTreino(String id, Map<String, String> nomesDesafios) {
        if (id == null || id.trim().isEmpty()) return "Sessão de treino";
        if (ehTreinoLivre(id)) return "Treino Livre";
        switch (id) {
            case "offline_001": return "Consistência no Forehand";
            case "offline_002": return "Precisão no Backhand";
            case "offline_003": return "Rei do Saque";
            case "offline_004": return "Fundamentos do Tênis";
            case "offline_005": return "Escada de Combo";
            case "offline_006": return "Alternância de golpes";
            case "offline_007": return "Saque + Primeiro Ataque";
            case "offline_008": return "Desafio Grand Slam";
            case "offline_fundamentos": return "Fundamentos do Tênis";
            default:
                String nome = nomesDesafios.get(id);
                if (nome != null && !nome.trim().isEmpty()) return nome;
                if (UUID_PATTERN.matcher(id).matches()) return "Desafio personalizado";
                return id.replace('_', ' ').replace('-', ' ');
        }
    }

    private void mostrarEstadoVazio(String mensagem) {
        txtEstadoVazio.setVisibility(View.VISIBLE);
        txtEstadoVazio.setText(mensagem);
    }

    private TextView criarTexto(String texto, int tamanhoSp, int cor, boolean negrito) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setTextSize(tamanhoSp);
        tv.setTextColor(getColor(cor));
        if (negrito) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private int dp(float valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    private String formatarDuracao(long segundos) {
        long seguros = Math.max(0, segundos);
        long horas = seguros / 3600;
        long minutos = (seguros % 3600) / 60;
        if (horas > 0) return horas + "h " + minutos + "m";
        if (minutos > 0) return minutos + " min";
        return seguros + " s";
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class Dados {
        final List<HistoricoTreinoEntity> historico = new ArrayList<>();
        Map<String, String> nomesDesafios = new HashMap<>();
        final List<HistoricoTreinoEntity> sessoesPontuadas = new ArrayList<>();
        int sessoes, sessoesGuiadas, sessoesLivres, desafiosConcluidos;
        int saques, forehands, backhands, smashes, amortidas, lobs, totalGolpes, melhorPontuacao, pontosTotais;
        final Map<String, Integer> contagemPorClasse = new HashMap<>();
        int atividadeUltimosSeteDias, atividadeSeteDiasAnteriores;
        int[] sessoesPorDia = new int[7];
        int precisao = -1, sequenciaDias, xpNoNivel, nivel;
        long tempoSegundos, xp;
    }
}
