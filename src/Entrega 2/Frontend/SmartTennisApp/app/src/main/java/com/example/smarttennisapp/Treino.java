package com.example.smarttennisapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;


import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.VisaoComputacionalClient;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.HistoricoTreinoEntity;
import com.example.smarttennisapp.database.TreinoDAO;
import com.example.smarttennisapp.util.BluetoothRaqueteUtil;
import com.example.smarttennisapp.jogo.ClasseGolpeRaquete;
import com.example.smarttennisapp.jogo.ParserRaquete;
import com.example.smarttennisapp.jogo.RegistroGolpesSessao;
import com.example.smarttennisapp.jogo.SwingEvento;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class Treino extends BaseActivity {

    // ============================================================
    // BLUETOOTH
    // ============================================================

    private static final int REQUEST_BLUETOOTH = 1001;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice dispositivoESP32;
    private BluetoothSocket bluetoothSocket;

    private InputStream bluetoothInput;
    private OutputStream bluetoothOutput;

    private Thread threadBluetooth;

    private volatile boolean bluetoothConectado = false;
    private volatile boolean encerrando = false;

    private final StringBuilder bufferRecepcao = new StringBuilder();

    // ============================================================
    // BANCO
    // ============================================================

    private TreinoDAO treinoDAO;

    private int usuarioId = -1;

    // ============================================================
    // UI
    // ============================================================

    private ImageButton btnVoltar;
    private Button btnComecar;
    private Button btnEncerrar;
    private InicioTreinoOverlay sinalInicioTreino;
    private boolean contagemInicioAtiva = false;

    private TextView txtOrigem;
    private TextView txtNome;
    private TextView txtDescricao;
    private TextView txtDuracao;
    private TextView txtDificuldade;
    private View cardStatusCamera;
    private TextView txtEstadoCamera;
    private TextView txtProgressoCamera;

    private LinearLayout containerSequenciaGolpes;

    private TextView txtInstrucoesGolpes;

    private TextView txtSemHistorico;
    private TextView txtMelhorTempo;
    private TextView txtMelhorPontuacao;
    private TextView txtQuantidadeExecucoes;
    private View containerMelhorResultado;

    private View indicadorConexao;
    private TextView txtEstadoConexao;

    private View cardSessaoAtual;
    private TextView txtCronometro;
    private TextView txtGolpeEsperado;
    private TextView txtUltimoGolpe;
    private TextView txtPontuacao;
    private TextView txtProgresso;
    private TextView txtComboTreino;
    private TextView txtNivelTreino;
    private TextView txtXpCarreira;
    private android.widget.ProgressBar progressXpNivel;

    private View cardResultadoFinal;
    private TextView txtResultadoTempo;
    private TextView txtResultadoPontuacao;
    private TextView txtResultadoResumo;

    // ============================================================
    // TREINO
    // ============================================================

    private String treinoId = "offline_fundamentos";
    private String treinoNome = "Fundamentos";
    private String treinoDescricao = "";
    private String treinoDificuldade = "Normal";
    private String treinoCategoria = "Fundamentos";
    private String treinoOrigem = "Offline";
    private int treinoDuracaoMinutos = 5;
    private String treinoSequenciaConfigurada = "";
    private boolean treinoUsaCamera = false;
    private boolean cameraConectada = false;
    private VisaoComputacionalClient cameraClient;
    private String cameraSessionId;
    private int impactosCamera = 0;
    private final int[] impactosPorQuadrante = new int[6];


    private final ArrayList<String> sequenciaGolpes = new ArrayList<>();

    private int indiceGolpeAtual = 0;

    private boolean treinoAtivo = false;
    private boolean sessaoConcluida = false;

    private long inicioTreino = 0;
    private long tempoDecorrido = 0;

    private int pontuacao = 0;
    private int comboAtual = 0;
    private int melhorCombo = 0;
    private int xpSessao = 0;
    private int xpCarreira = 0;
    private int nivelJogador = 1;
    private static final int XP_POR_NIVEL = 500;

    private int totalGolpes = 0;
    private int golpesCorretos = 0;

    private int saques = 0;
    private int forehands = 0;
    private int backhands = 0;

    private int golpesRecebidos = 0;
    private final RegistroGolpesSessao registroGolpes = new RegistroGolpesSessao();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2);

    private Runnable cronometroRunnable;

    // ============================================================
    // ON CREATE
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_treino);

        sinalInicioTreino = new InicioTreinoOverlay(
                findViewById(R.id.overlayInicioTreino),
                findViewById(R.id.txtStartSignalNumero),
                findViewById(R.id.txtStartSignalMensagem)
        );
        inicializarViews();

        configurarBanco();

        receberDadosTreino();

        configurarBotoes();

        prepararTela();
        if (treinoUsaCamera) configurarCameraTreino();
        carregarXPdaCarreira();
        carregarMelhorResultado();

        verificarBluetooth();
    }

    // ============================================================
    // VIEWS
    // ============================================================

    private void inicializarViews() {

        btnVoltar = findViewById(R.id.btnVoltarTreino);
        btnComecar = findViewById(R.id.btnComecarTreino);
        btnEncerrar = findViewById(R.id.btnEncerrarTreino);

        txtOrigem = findViewById(R.id.txtOrigemTreino);
        txtNome = findViewById(R.id.txtNomeTreino);
        txtDescricao = findViewById(R.id.txtDescricaoTreino);
        txtDuracao = findViewById(R.id.txtDuracaoTreino);
        txtDificuldade = findViewById(R.id.txtDificuldadeTreino);
        cardStatusCamera = findViewById(R.id.cardStatusCamera);
        txtEstadoCamera = findViewById(R.id.txtEstadoCameraTreino);
        txtProgressoCamera = findViewById(R.id.txtProgressoCameraTreino);

        txtInstrucoesGolpes =
                findViewById(R.id.txtInstrucoesGolpes);

        containerSequenciaGolpes =
                findViewById(R.id.containerSequenciaGolpes);

        txtSemHistorico =
                findViewById(R.id.txtSemHistorico);

        txtMelhorTempo =
                findViewById(R.id.txtMelhorTempo);

        txtMelhorPontuacao =
                findViewById(R.id.txtMelhorPontuacao);

        txtQuantidadeExecucoes = findViewById(R.id.txtQuantidadeExecucoes);
        containerMelhorResultado = findViewById(R.id.containerMelhorResultado);

        indicadorConexao =
                findViewById(R.id.indicadorConexaoRaquete);

        txtEstadoConexao =
                findViewById(R.id.txtEstadoConexaoRaquete);


        cardSessaoAtual =
                findViewById(R.id.cardSessaoAtual);

        txtCronometro =
                findViewById(R.id.txtCronometroTreino);

        txtGolpeEsperado =
                findViewById(R.id.txtGolpeEsperado);

        txtUltimoGolpe =
                findViewById(R.id.txtUltimoGolpeDetectado);

        txtPontuacao =
                findViewById(R.id.txtPontuacaoAtual);

        txtProgresso = findViewById(R.id.txtProgressoGolpes);
        txtComboTreino = findViewById(R.id.txtComboTreino);
        txtNivelTreino = findViewById(R.id.txtNivelTreino);
        txtXpCarreira = findViewById(R.id.txtXpCarreira);
        progressXpNivel = findViewById(R.id.progressXpNivel);

        cardResultadoFinal =
                findViewById(R.id.cardResultadoFinal);

        txtResultadoTempo =
                findViewById(R.id.txtResultadoTempo);

        txtResultadoPontuacao =
                findViewById(R.id.txtResultadoPontuacao);

        txtResultadoResumo =
                findViewById(R.id.txtResultadoResumoGolpes);
    }

    // ============================================================
    // BANCO
    // ============================================================

    private void configurarBanco() {
        treinoDAO = DatabaseProvider.getInstance(getApplicationContext()).treinoDAO();
        usuarioId = lerUsuarioId();
    }

    private int lerUsuarioId() {
        return com.example.smarttennisapp.util.SessaoUsuario.obterId(this);
    }

    // ============================================================
    // DADOS DO TREINO
    // ============================================================

    private void receberDadosTreino() {

        Intent intent = getIntent();

        if (intent == null) {
            criarSequenciaPadrao();
            return;
        }

        treinoId = valorString(
                intent.getStringExtra("treino_id"),
                "offline_fundamentos"
        );

        treinoNome = valorString(
                intent.getStringExtra("treino_nome"),
                "Fundamentos"
        );

        treinoDescricao = valorString(
                intent.getStringExtra("treino_descricao"),
                "Treino guiado de fundamentos."
        );

        treinoDificuldade = valorString(
                intent.getStringExtra("treino_dificuldade"),
                "Normal"
        );

        treinoCategoria = valorString(
                intent.getStringExtra("treino_categoria"),
                "Fundamentos"
        );

        treinoOrigem = valorString(
                intent.getStringExtra("treino_origem"),
                "Offline"
        );

        treinoDuracaoMinutos = Math.max(1,
                intent.getIntExtra("treino_duracao", 5));
        treinoSequenciaConfigurada = valorString(
                intent.getStringExtra("treino_sequencia"), "");
        treinoUsaCamera = intent.getBooleanExtra("treino_usar_camera", false);

        criarSequenciaPadrao();
    }

    private String valorString(String valor, String padrao) {

        if (valor == null || valor.trim().isEmpty()) {
            return padrao;
        }

        return valor;
    }

    // ============================================================
    // SEQUÊNCIA
    // ============================================================

    private void criarSequenciaPadrao() {

        sequenciaGolpes.clear();

        // Desafios personalizados carregam a sequência persistida, não uma sequência genérica.
        if (treinoSequenciaConfigurada != null && !treinoSequenciaConfigurada.trim().isEmpty()) {
            for (String item : treinoSequenciaConfigurada.split(",")) {
                String golpe = ClasseGolpeRaquete.normalizarToken(item);
                if (golpe != null) sequenciaGolpes.add(golpe);
            }
            if (!sequenciaGolpes.isEmpty()) {
                totalGolpes = sequenciaGolpes.size();
                return;
            }
        }

        String categoria =
                treinoCategoria.toUpperCase(Locale.ROOT);

        if (categoria.contains("SAQUE")) {

            adicionarGolpes("SAQUE", 5);

        } else if (categoria.contains("FOREHAND")) {

            adicionarGolpes("FOREHAND", 5);

        } else if (categoria.contains("BACKHAND")) {

            adicionarGolpes("BACKHAND", 5);

        } else {

            sequenciaGolpes.add("SAQUE");
            sequenciaGolpes.add("FOREHAND");
            sequenciaGolpes.add("BACKHAND");

            sequenciaGolpes.add("FOREHAND");
            sequenciaGolpes.add("BACKHAND");

            sequenciaGolpes.add("SAQUE");
        }

        totalGolpes = sequenciaGolpes.size();
    }

    private void adicionarGolpes(String golpe, int quantidade) {

        for (int i = 0; i < quantidade; i++) {
            sequenciaGolpes.add(golpe);
        }

        totalGolpes = sequenciaGolpes.size();
    }

    // ============================================================
    // TELA
    // ============================================================

    private void prepararTela() {

        txtNome.setText(treinoNome);
        txtDescricao.setText(treinoDescricao);
        txtDificuldade.setText(treinoDificuldade);

        txtDuracao.setText(
                treinoDuracaoMinutos + " min"
        );

        txtOrigem.setText(treinoOrigem);

        cardSessaoAtual.setVisibility(View.GONE);
        cardResultadoFinal.setVisibility(View.GONE);
        if (cardStatusCamera != null) cardStatusCamera.setVisibility(treinoUsaCamera ? View.VISIBLE : View.GONE);

        btnEncerrar.setVisibility(View.GONE);

        btnComecar.setEnabled(false);

        atualizarSequenciaVisual();

        txtEstadoConexao.setText(
                "Conectando à raquete..."
        );
    }

    private void atualizarSequenciaVisual() {
        containerSequenciaGolpes.removeAllViews();
        for (int i = 0; i < sequenciaGolpes.size(); i++) {
            TextView item = new TextView(this);
            String nome = nomeGolpe(sequenciaGolpes.get(i));
            if (i < indiceGolpeAtual) item.setText("✓  " + (i + 1) + ". " + nome);
            else if (i == indiceGolpeAtual && treinoAtivo) item.setText("▶  " + (i + 1) + ". " + nome);
            else item.setText(String.format(Locale.getDefault(), "%02d  ·  %s", i + 1, nome));
            item.setTextSize(14);
            item.setTextColor(i < indiceGolpeAtual ? ColorPalette.VERDE
                    : (i == indiceGolpeAtual && treinoAtivo ? ColorPalette.BRANCO : ColorPalette.CINZA));
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackgroundResource(i == indiceGolpeAtual && treinoAtivo
                    ? R.drawable.bg_item_golpe : R.drawable.bg_card_treino_execucao);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(7);
            containerSequenciaGolpes.addView(item, params);
        }
        txtInstrucoesGolpes.setText("Execute os golpes na ordem indicada. O ESP32 reconhece os movimentos da raquete por Bluetooth. "
                + "Cada acerto vale 100 pontos; sequências de 3 acertos seguidos rendem bônus de combo.");
    }

    private String nomeGolpe(String golpe) {
        return ClasseGolpeRaquete.nomeExibicao(golpe);
    }

    private int dp(int valor) {
        return (int) (valor * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Paleta local simples para deixar os itens de sequência consistentes com os layouts. */
    private static final class ColorPalette {
        static final int VERDE = android.graphics.Color.rgb(53, 200, 120);
        static final int BRANCO = android.graphics.Color.WHITE;
        static final int CINZA = android.graphics.Color.rgb(166, 176, 186);
    }

    // ============================================================
    // BOTÕES
    // ============================================================

    private void configurarBotoes() {

        btnVoltar.setOnClickListener(v -> {

            if (treinoAtivo) {

                Toast.makeText(
                        this,
                        "Encerre o treino antes de voltar.",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            finish();
        });

        btnComecar.setOnClickListener(v -> iniciarTreino());

        btnEncerrar.setOnClickListener(v -> {

            if (treinoAtivo) {
                finalizarTreino(false);
            }
        });
    }

    // ============================================================
    // BLUETOOTH
    // ============================================================

    private void verificarBluetooth() {

        bluetoothAdapter =
                BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {

            atualizarConexaoUI(
                    false,
                    "Bluetooth não disponível neste celular."
            );

            return;
        }

        if (!BluetoothRaqueteUtil.temPermissaoConectar(this)) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH
            );
            return;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                atualizarConexaoUI(false, "Ative o Bluetooth do celular e tente novamente.");
                return;
            }
        } catch (SecurityException e) {
            atualizarConexaoUI(false, "O Android não concedeu acesso ao Bluetooth.");
            return;
        }

        conectarESP32();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQUEST_BLUETOOTH) {

            boolean permitido = grantResults.length > 0;

            for (int resultado : grantResults) {

                if (resultado != PackageManager.PERMISSION_GRANTED) {
                    permitido = false;
                    break;
                }
            }

            if (permitido) {
                verificarBluetooth();
            } else {

                atualizarConexaoUI(
                        false,
                        "Permissão Bluetooth negada."
                );
            }
        }
    }

    private void conectarESP32() {

        executor.execute(() -> {

            try {

                if (!BluetoothRaqueteUtil.temPermissaoConectar(this)) {

                    runOnUiThread(() ->
                            atualizarConexaoUI(
                                    false,
                                    "Permissão Bluetooth não concedida."
                            )
                    );

                    return;
                }

                dispositivoESP32 =
                        BluetoothRaqueteUtil.encontrarDispositivoPareado(
                                this,
                                bluetoothAdapter
                        );

                if (dispositivoESP32 == null) {

                    runOnUiThread(() ->
                            atualizarConexaoUI(
                                    false,
                                    "Raquete não encontrada. " +
                                            "Pareie 'RAQUETE-TENIS' primeiro."
                            )
                    );

                    return;
                }

                runOnUiThread(() ->
                        atualizarConexaoUI(
                                false,
                                "Conectando à RAQUETE-TENIS..."
                        )
                );

                fecharBluetooth();

                bluetoothSocket =
                        BluetoothRaqueteUtil.criarSocket(dispositivoESP32);

                bluetoothSocket.connect();

                bluetoothInput =
                        bluetoothSocket.getInputStream();

                bluetoothOutput =
                        bluetoothSocket.getOutputStream();

                bluetoothConectado = true;

                runOnUiThread(() -> {

                    atualizarConexaoUI(
                            true,
                            "Raquete conectada"
                    );

                    btnComecar.setEnabled(true);
                });

                iniciarLeituraBluetooth();

            } catch (Exception e) {

                bluetoothConectado = false;

                runOnUiThread(() -> {

                    btnComecar.setEnabled(false);

                    atualizarConexaoUI(
                            false,
                            "Falha na conexão: " +
                                    mensagemErro(e)
                    );
                });
            }
        });
    }

    private String mensagemErro(Exception e) {

        if (e.getMessage() == null) {
            return e.getClass().getSimpleName();
        }

        return e.getMessage();
    }

    // ============================================================
    // LEITURA BLUETOOTH
    // ============================================================

    private void iniciarLeituraBluetooth() {

        if (threadBluetooth != null &&
                threadBluetooth.isAlive()) {

            return;
        }

        threadBluetooth = new Thread(() -> {

            byte[] buffer = new byte[1024];

            while (bluetoothConectado &&
                    !encerrando) {

                try {

                    int quantidade =
                            bluetoothInput.read(buffer);

                    if (quantidade <= 0) {
                        continue;
                    }

                    String recebido =
                            new String(
                                    buffer,
                                    0,
                                    quantidade,
                                    StandardCharsets.UTF_8
                            );

                    processarDadosBluetooth(recebido);

                } catch (IOException e) {

                    bluetoothConectado = false;

                    runOnUiThread(() -> {

                        btnComecar.setEnabled(false);

                        atualizarConexaoUI(
                                false,
                                "Conexão com a raquete perdida."
                        );
                    });

                    break;
                }
            }
        });

        threadBluetooth.start();
    }

    // ============================================================
    // BUFFER / LINHAS
    // ============================================================

    private synchronized void processarDadosBluetooth(
            String dados) {

        bufferRecepcao.append(dados);

        int indice;

        while ((indice =
                bufferRecepcao.indexOf("\n")) >= 0) {

            String linha =
                    bufferRecepcao
                            .substring(0, indice)
                            .trim();

            bufferRecepcao.delete(
                    0,
                    indice + 1
            );

            if (!linha.isEmpty()) {

                processarLinhaESP32(linha);
            }
        }

        /*
         * Alguns firmwares podem mandar CR sem LF.
         * Trata também esse caso.
         */
        if (bufferRecepcao.length() > 512) {

            String resto =
                    bufferRecepcao.toString().trim();

            bufferRecepcao.setLength(0);

            if (!resto.isEmpty()) {
                processarLinhaESP32(resto);
            }
        }
    }

    // ============================================================
    // INTERPRETAÇÃO DO ESP32
    // ============================================================

    private void processarLinhaESP32(String linha) {
        if (ehErroDoClassificador(linha)) {
            abortarTreinoSemModelo();
            return;
        }
        // Única fonte de verdade para o formato de saída do firmware v2.
        SwingEvento evento = ParserRaquete.parse(linha, System.currentTimeMillis());
        if (evento == null || !treinoAtivo) return;
        receberGolpe(evento);
    }


    private boolean ehErroDoClassificador(String linha) {
        if (linha == null) return false;
        String normalizada = linha.toLowerCase(Locale.ROOT);
        return normalizada.contains("erro: preciso de >= 2 classes")
                || normalizada.contains("# sem modelo")
                || normalizada.contains("# sem classes ativas")
                || normalizada.contains("dados insuficientes: modelo invalidado");
    }

    /** Cancela apenas a tentativa de início; não gera histórico nem XP para uma sessão inválida. */
    private void abortarTreinoSemModelo() {
        mainHandler.post(() -> {
            if (!treinoAtivo) return;
            treinoAtivo = false;
            sessaoConcluida = false;
            tempoDecorrido = 0L;
            mainHandler.removeCallbacks(cronometroRunnable);
            enviarComando("q");
            cardSessaoAtual.setVisibility(View.GONE);
            cardResultadoFinal.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.GONE);
            btnComecar.setVisibility(View.VISIBLE);
            btnComecar.setEnabled(bluetoothConectado);
            txtUltimoGolpe.setText("Classificador indisponível");
            Toast.makeText(this,
                    "A raquete precisa de pelo menos 5 exemplos em cada uma de 2 classes para classificar golpes.",
                    Toast.LENGTH_LONG).show();
        });
    }

    // ============================================================
    // GOLPE RECEBIDO
    // ============================================================

    private void receberGolpe(SwingEvento evento) {
        runOnUiThread(() -> {
            if (!treinoAtivo || evento == null) return;

            golpesRecebidos++;
            registroGolpes.registrar(evento);

            if (!evento.temClasseIdentificada()) {
                comboAtual = 0;
                txtUltimoGolpe.setText("Golpe não identificado");
                atualizarGamificacaoUI();
                atualizarProgresso();
                return; // A rejeição não avança a sequência nem conta como acerto.
            }

            registrarTipoGolpe(evento.golpe);
            String detalhes = evento.nomeClasse;
            if (evento.confiancaPct > 0) detalhes += String.format(Locale.getDefault(), " · %d%%", evento.confiancaPct);
            if (evento.velRaqueteKmh > 0) detalhes += String.format(Locale.getDefault(), " · %.0f km/h", evento.velRaqueteKmh);
            txtUltimoGolpe.setText(detalhes);
            processarGolpeTreino(evento);
        });
    }

    private void registrarTipoGolpe(String familia) {
        switch (familia) {
            case "SAQUE": saques++; break;
            case "FOREHAND": forehands++; break;
            case "BACKHAND": backhands++; break;
            default: break; // Smash, amortida, lob e ruído são preservados por classe no JSON.
        }
    }

    // ============================================================
    // REGRAS DO TREINO
    // ============================================================

    private void processarGolpeTreino(SwingEvento evento) {
        if (indiceGolpeAtual >= sequenciaGolpes.size()) return;

        String esperado = sequenciaGolpes.get(indiceGolpeAtual);
        if (ClasseGolpeRaquete.corresponde(esperado, evento.codigoClasse)) {
            golpesCorretos++;
            comboAtual++;
            melhorCombo = Math.max(melhorCombo, comboAtual);
            int bonusCombo = comboAtual > 0 && comboAtual % 3 == 0 ? 50 : 0;
            pontuacao += 100 + bonusCombo;
            if (bonusCombo > 0) {
                Toast.makeText(this, "🔥 Combo x" + comboAtual + "! +50 pontos bônus", Toast.LENGTH_SHORT).show();
            }
            indiceGolpeAtual++;
            atualizarProgresso();
            atualizarSequenciaVisual();

            if (indiceGolpeAtual >= sequenciaGolpes.size()) {
                verificarConclusaoObjetivos();
                return;
            }
            atualizarGolpeEsperado();
        } else {
            comboAtual = 0;
            atualizarGamificacaoUI();
            atualizarProgresso();
            Toast.makeText(this,
                    "Golpe detectado: " + evento.nomeClasse + "\nEsperado: " + nomeGolpe(esperado),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // VISÃO COMPUTACIONAL (OPCIONAL)
    // ============================================================

    private void configurarCameraTreino() {
        cameraClient = new VisaoComputacionalClient();
        txtEstadoCamera.setText("Procurando automaticamente o servidor Python na rede local…");
        cameraClient.discoverAndConnect(getApplicationContext(), new VisaoComputacionalClient.Listener() {
            @Override
            public void onStatus(boolean conectado, JSONObject status, String mensagem) {
                if (isFinishing() || isDestroyed()) return;
                cameraConectada = conectado && cameraClient != null && cameraClient.isCameraActive();
                txtEstadoCamera.setText(cameraConectada
                        ? "✓ " + mensagem + " · pronto para iniciar"
                        : mensagem + (conectado ? " · aguarde a webcam" : ""));
                txtEstadoCamera.setTextColor(cameraConectada
                        ? android.graphics.Color.rgb(61, 220, 132)
                        : android.graphics.Color.rgb(255, 193, 7));
            }

            @Override
            public void onBallFlight(JSONObject evento) {
                if (!treinoAtivo || evento == null || !evento.optBoolean("wall_hit", false)) return;
                registrarImpactoCamera(evento);
            }
        });
    }

    private String obterModoCamera() {
        String categoria = treinoCategoria == null ? "" : treinoCategoria.toUpperCase(Locale.ROOT);
        if (categoria.contains("PRECIS") || categoria.contains("CONTROLE")) return "PRECISAO";
        if (categoria.contains("VARREDURA")) return "QUADRANTES";
        if (categoria.contains("RALI") || categoria.contains("COMBO") || categoria.contains("CONSIST")) return "RALI";
        if (categoria.contains("SAQUE")) return "SAQUE";
        return "IMPACTOS";
    }

    private int metaCamera() {
        switch (obterModoCamera()) {
            case "PRECISAO": return 3;
            case "RALI": return 8;
            case "QUADRANTES": return 4;
            case "SAQUE": return 5;
            default: return Math.max(5, sequenciaGolpes.size());
        }
    }

    private void registrarImpactoCamera(JSONObject evento) {
        impactosCamera++;
        JSONObject impacto = evento.optJSONObject("impact");
        int quadrante = impacto == null ? 0 : impacto.optInt("quadrant", 0);
        if (quadrante >= 1 && quadrante <= impactosPorQuadrante.length) {
            impactosPorQuadrante[quadrante - 1]++;
        }
        atualizarProgressoCamera();
        // Quando a sequência ESP32 já terminou, a câmera fecha a missão assim que
        // a meta complementar selecionada for alcançada.
        verificarConclusaoObjetivos();
    }

    private int quadrantesVisitados() {
        int total = 0;
        for (int quantidade : impactosPorQuadrante) if (quantidade > 0) total++;
        return total;
    }

    private int impactosCentrais() {
        // Grade 3x2: Q2 e Q5 são as regiões centrais da parede.
        return impactosPorQuadrante[1] + impactosPorQuadrante[4];
    }

    private boolean objetivoCameraConcluido() {
        if (!treinoUsaCamera) return false;
        switch (obterModoCamera()) {
            case "PRECISAO": return impactosCentrais() >= metaCamera();
            case "QUADRANTES": return quadrantesVisitados() >= metaCamera();
            default: return impactosCamera >= metaCamera();
        }
    }

    private String descricaoObjetivoCamera() {
        switch (obterModoCamera()) {
            case "PRECISAO": return "impactos nas zonas centrais";
            case "QUADRANTES": return "quadrantes diferentes visitados";
            case "RALI": return "impactos válidos na parede";
            case "SAQUE": return "impactos confirmados dos saques";
            default: return "impactos confirmados";
        }
    }

    private void atualizarProgressoCamera() {
        if (txtProgressoCamera == null) return;
        String progresso;
        if ("PRECISAO".equals(obterModoCamera())) {
            progresso = impactosCentrais() + "/" + metaCamera() + " impactos nas zonas centrais · " + impactosCamera + " no total";
        } else if ("QUADRANTES".equals(obterModoCamera())) {
            progresso = quadrantesVisitados() + "/" + metaCamera() + " quadrantes visitados · " + impactosCamera + " impactos";
        } else {
            progresso = impactosCamera + "/" + metaCamera() + " " + descricaoObjetivoCamera();
        }
        txtProgressoCamera.setText(progresso);
    }

    private void iniciarSessaoCameraEContinuar() {
        if (!treinoUsaCamera) {
            iniciarTreinoAgora();
            return;
        }
        if (cameraClient == null || !cameraConectada) {
            contagemInicioAtiva = false;
            btnComecar.setEnabled(bluetoothConectado);
            Toast.makeText(this, "A webcam/servidor Python ainda não está pronto. Toque em começar novamente para buscar a estação.", Toast.LENGTH_LONG).show();
            if (cameraClient == null) configurarCameraTreino();
            else {
                cameraClient.close();
                configurarCameraTreino();
            }
            return;
        }
        resetarMetricasCamera();
        cameraSessionId = UUID.randomUUID().toString();
        btnComecar.setEnabled(false);
        cameraClient.startSession(cameraSessionId, treinoId, 0f, metaCamera(), obterModoCamera(), (ok, resposta, mensagem) -> {
            if (isFinishing() || isDestroyed()) return;
            if (!ok) {
                contagemInicioAtiva = false;
                btnComecar.setEnabled(bluetoothConectado);
                Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show();
                return;
            }
            iniciarTreinoAgora();
            txtEstadoCamera.setText("✓ Câmera em sessão · " + descricaoObjetivoCamera());
        });
    }

    private void resetarMetricasCamera() {
        impactosCamera = 0;
        for (int i = 0; i < impactosPorQuadrante.length; i++) impactosPorQuadrante[i] = 0;
        atualizarProgressoCamera();
    }

    // ============================================================
    // INICIAR TREINO
    // ============================================================

    private void iniciarTreino() {
        if (treinoAtivo || contagemInicioAtiva) return;
        if (!bluetoothConectado) {
            Toast.makeText(this, "Conecte a raquete antes de iniciar.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (treinoUsaCamera && !cameraConectada) {
            Toast.makeText(this, "A câmera foi selecionada para este desafio. Aguarde o servidor Python e a webcam ficarem prontos.", Toast.LENGTH_LONG).show();
            if (cameraClient == null) configurarCameraTreino();
            else {
                cameraClient.close();
                configurarCameraTreino();
            }
            return;
        }
        if (sequenciaGolpes.isEmpty()) {
            Toast.makeText(this, "Este treino não possui golpes configurados.", Toast.LENGTH_SHORT).show();
            return;
        }

        contagemInicioAtiva = true;
        btnComecar.setEnabled(false);
        sinalInicioTreino.start(() -> {
            contagemInicioAtiva = false;
            if (!bluetoothConectado || encerrando) {
                btnComecar.setEnabled(bluetoothConectado);
                Toast.makeText(this, "A conexão com a raquete foi perdida. Conecte novamente para iniciar.", Toast.LENGTH_LONG).show();
                return;
            }
            iniciarSessaoCameraEContinuar();
        });
    }

    private void iniciarTreinoAgora() {
        if (treinoAtivo) return;
        if (!bluetoothConectado) {
            btnComecar.setEnabled(false);
            Toast.makeText(this, "Conecte a raquete antes de iniciar.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sequenciaGolpes.isEmpty()) {
            btnComecar.setEnabled(true);
            Toast.makeText(this, "Este treino não possui golpes configurados.", Toast.LENGTH_SHORT).show();
            return;
        }

        treinoAtivo = true;
        sessaoConcluida = false;
        comboAtual = 0;
        melhorCombo = 0;
        pontuacao = 0;
        golpesCorretos = 0;
        saques = 0;
        forehands = 0;
        backhands = 0;
        golpesRecebidos = 0;
        registroGolpes.limpar();
        if (!treinoUsaCamera) resetarMetricasCamera();
        xpSessao = 0;
        indiceGolpeAtual = 0;
        tempoDecorrido = 0;
        inicioTreino = System.currentTimeMillis();
        cardSessaoAtual.setVisibility(View.VISIBLE);
        cardResultadoFinal.setVisibility(View.GONE);
        btnComecar.setVisibility(View.GONE);
        btnEncerrar.setVisibility(View.VISIBLE);
        atualizarGamificacaoUI();
        atualizarProgresso();
        atualizarSequenciaVisual();
        atualizarGolpeEsperado();
        iniciarCronometro();
        enviarComando("k");
        Toast.makeText(this, "🎾 Desafio iniciado! Vamos buscar um combo!", Toast.LENGTH_SHORT).show();
    }

    private void verificarConclusaoObjetivos() {
        if (!treinoAtivo || indiceGolpeAtual < sequenciaGolpes.size()) return;
        if (!treinoUsaCamera || objetivoCameraConcluido()) {
            finalizarTreino(true);
            return;
        }
        txtGolpeEsperado.setText("Sequência concluída · " + descricaoObjetivoCamera());
    }

    // ============================================================
    // COMUNICAÇÃO COM ESP32
    // ============================================================

    private synchronized void enviarComando(
            String comando) {

        if (!bluetoothConectado ||
                bluetoothOutput == null) {

            return;
        }

        executor.execute(() -> {

            try {

                String mensagem =
                        comando.trim() + "\n";

                bluetoothOutput.write(
                        mensagem.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

                bluetoothOutput.flush();

            } catch (IOException e) {

                runOnUiThread(() -> {

                    bluetoothConectado = false;

                    atualizarConexaoUI(
                            false,
                            "Erro ao enviar comando."
                    );
                });
            }
        });
    }

    // ============================================================
    // CRONÔMETRO
    // ============================================================

    private void iniciarCronometro() {

        cronometroRunnable =
                new Runnable() {

                    @Override
                    public void run() {

                        if (!treinoAtivo) {
                            return;
                        }

                        tempoDecorrido =
                                System.currentTimeMillis()
                                        - inicioTreino;

                        long segundos =
                                tempoDecorrido / 1000;

                        long minutos =
                                segundos / 60;

                        segundos %= 60;

                        txtCronometro.setText(
                                String.format(
                                        Locale.getDefault(),
                                        "%02d:%02d",
                                        minutos,
                                        segundos
                                )
                        );

                        /*
                         * Duração máxima configurada
                         */

                        long limite =
                                treinoDuracaoMinutos
                                        * 60L
                                        * 1000L;

                        if (tempoDecorrido >= limite) {

                            finalizarTreino(false);

                            return;
                        }

                        mainHandler.postDelayed(
                                this,
                                500
                        );
                    }
                };

        mainHandler.post(
                cronometroRunnable
        );
    }

    // ============================================================
    // INTERFACE DURANTE TREINO
    // ============================================================

    private void atualizarGolpeEsperado() {

        if (indiceGolpeAtual >=
                sequenciaGolpes.size()) {

            txtGolpeEsperado.setText(
                    "Concluído!"
            );

            return;
        }

        txtGolpeEsperado.setText(nomeGolpe(
                sequenciaGolpes.get(indiceGolpeAtual)
        ));
    }

    private void atualizarProgresso() {
        txtPontuacao.setText(String.valueOf(pontuacao));
        txtProgresso.setText(indiceGolpeAtual + "/" + totalGolpes + " golpes · "
                + Math.round(totalGolpes == 0 ? 0 : (indiceGolpeAtual * 100f / totalGolpes)) + "%");
        atualizarGamificacaoUI();
    }

    private void carregarXPdaCarreira() {
        xpCarreira = getSharedPreferences("SmartTennisGamificacao", MODE_PRIVATE)
                .getInt("xp_total_" + Math.max(usuarioId, 0), 0);
        atualizarGamificacaoUI();
    }

    private void registrarXPdaSessao() {
        xpCarreira += xpSessao;
        getSharedPreferences("SmartTennisGamificacao", MODE_PRIVATE).edit()
                .putInt("xp_total_" + Math.max(usuarioId, 0), xpCarreira).apply();
        atualizarGamificacaoUI();
    }

    private void atualizarGamificacaoUI() {
        nivelJogador = Math.max(1, xpCarreira / XP_POR_NIVEL + 1);
        int xpNoNivel = xpCarreira % XP_POR_NIVEL;
        if (txtNivelTreino != null) txtNivelTreino.setText("NÍVEL " + nivelJogador);
        if (txtXpCarreira != null) txtXpCarreira.setText(xpNoNivel + "/" + XP_POR_NIVEL + " XP para o próximo nível");
        if (progressXpNivel != null) {
            progressXpNivel.setMax(XP_POR_NIVEL);
            progressXpNivel.setProgress(xpNoNivel);
        }
        if (txtComboTreino != null) txtComboTreino.setText("🔥 Combo x" + comboAtual + "   ·   melhor x" + melhorCombo);
    }

    // ============================================================
    // FINALIZAR
    // ============================================================

    private void finalizarTreino(
            boolean concluidoPorObjetivo) {

        if (!treinoAtivo) {
            return;
        }


        treinoAtivo = false;
        sessaoConcluida = concluidoPorObjetivo;

        tempoDecorrido =
                System.currentTimeMillis()
                        - inicioTreino;

        enviarComando("q");
        if (treinoUsaCamera && cameraClient != null) cameraClient.stopSession();

        mainHandler.removeCallbacks(
                cronometroRunnable
        );

        cardSessaoAtual.setVisibility(View.GONE);

        btnEncerrar.setVisibility(View.GONE);

        btnComecar.setVisibility(View.VISIBLE);

        txtResultadoTempo.setText(
                formatarTempo(tempoDecorrido)
        );

        boolean perfeito = concluidoPorObjetivo && golpesCorretos == totalGolpes && golpesRecebidos == totalGolpes;
        int bonusConclusao = concluidoPorObjetivo ? 100 : 0;
        int bonusPerfeito = perfeito ? 150 : 0;
        int bonusCamera = treinoUsaCamera && concluidoPorObjetivo && objetivoCameraConcluido() ? 100 : 0;
        pontuacao += bonusConclusao + bonusPerfeito + bonusCamera;
        xpSessao = Math.max(0, pontuacao / 10) + (perfeito ? 25 : (concluidoPorObjetivo ? 10 : 0));
        registrarXPdaSessao();
        txtResultadoPontuacao.setText(String.valueOf(pontuacao));
        txtResultadoResumo.setText(
                calcularRank(concluidoPorObjetivo, perfeito) +
                (perfeito ? "  ·  DESAFIO PERFEITO!\n" : (concluidoPorObjetivo ? "  ·  DESAFIO CONCLUÍDO\n" : "  ·  CONTINUE EVOLUINDO\n")) +
                "Acertos: " + golpesCorretos + "/" + totalGolpes +
                "\nSaques: " + saques + "  ·  Forehands: " + forehands + "  ·  Backhands: " + backhands +
                "\nClasses detectadas: " + registroGolpes.resumoPorClasse() +
                "\nMelhor combo: x" + melhorCombo +
                (treinoUsaCamera ? "\nCâmera: " + impactosCamera + " impactos · " + quadrantesVisitados() + "/6 quadrantes" +
                        "\nMeta de câmera: " + (objetivoCameraConcluido() ? "concluída (+" + bonusCamera + " pontos)" : "não concluída") : "") +
                "\nXP desta sessão: +" + xpSessao +
                "\nXP total: " + xpCarreira + "  ·  Nível " + nivelJogador
        );

        cardResultadoFinal.setVisibility(
                View.VISIBLE
        );

        salvarHistorico();

        if (concluidoPorObjetivo) {

            Toast.makeText(
                    this,
                    "Treino concluído!",
                    Toast.LENGTH_LONG
            ).show();

        } else {

            Toast.makeText(
                    this,
                    "Tempo de treino encerrado.",
                    Toast.LENGTH_LONG
            ).show();
        }

    }

    private int paraIntSeguro(long valor) {
        if (valor > Integer.MAX_VALUE || valor < Integer.MIN_VALUE) {
            throw new ArithmeticException("Valor fora do limite de inteiro: " + valor);
        }
        return (int) valor;
    }

    private String calcularRank(boolean concluido, boolean perfeito) {
        if (perfeito) return "🏆 RANK S";
        if (concluido) return "🥇 RANK A";
        int precisao = totalGolpes == 0 ? 0 : Math.round(golpesCorretos * 100f / totalGolpes);
        if (precisao >= 75) return "🥈 RANK B";
        if (precisao >= 40) return "🥉 RANK C";
        return "🎾 RANK D";
    }

    private String formatarTempo(
            long tempoMillis) {

        long segundos =
                tempoMillis / 1000;

        long minutos =
                segundos / 60;

        segundos %= 60;

        return String.format(
                Locale.getDefault(),
                "%02d:%02d",
                minutos,
                segundos
        );
    }

    // ============================================================
    // HISTÓRICO
    // ============================================================

    private void salvarHistorico() {
        if (usuarioId <= 0) {
            runOnUiThread(() -> txtSemHistorico.setVisibility(View.VISIBLE));
            return;
        }

        final long tempoSegundos = tempoDecorrido / 1000;
        executor.execute(() -> {
            try {
                HistoricoTreinoEntity historico = new HistoricoTreinoEntity();
                historico.usuarioId = paraIntSeguro(usuarioId);
                historico.treinoId = treinoId;
                historico.dataConclusao = System.currentTimeMillis();
                historico.tempoSegundos = paraIntSeguro(tempoSegundos);
                historico.pontuacao = pontuacao;
                historico.saques = saques;
                historico.forehands = forehands;
                historico.backhands = backhands;
                historico.golpesCorretos = golpesCorretos;
                historico.totalGolpes = totalGolpes;
                historico.golpesRecebidos = golpesRecebidos;
                historico.concluido = sessaoConcluida;
                historico.metricasGolpesJson = registroGolpes.paraJson("TREINO_GUIADO");
                historico.bolasDetectadas = treinoUsaCamera ? impactosCamera : 0;
                historico.bolasAcimaMeta = treinoUsaCamera && objetivoCameraConcluido() ? 1 : 0;
                try {
                    JSONObject extras = new JSONObject();
                    extras.put("origem", "TREINO_GUIADO");
                    extras.put("metricasGolpes", new JSONObject(historico.metricasGolpesJson));
                    if (treinoUsaCamera) {
                        extras.put("cameraAtiva", true);
                        extras.put("modoCamera", obterModoCamera());
                        extras.put("impactosDetectados", impactosCamera);
                        extras.put("metaCamera", metaCamera());
                        extras.put("metaCameraConcluida", objetivoCameraConcluido());
                        extras.put("quadrantesVisitados", quadrantesVisitados());
                        org.json.JSONArray quadrantes = new org.json.JSONArray();
                        for (int i = 0; i < impactosPorQuadrante.length; i++) quadrantes.put(impactosPorQuadrante[i]);
                        extras.put("impactosPorQuadrante", quadrantes);
                    }
                    historico.metricasCameraJson = extras.toString();
                } catch (Exception ignorada) {
                    historico.metricasCameraJson = "{\"origem\":\"TREINO_GUIADO\"}";
                }

                // O resultado só é consultado depois de a inserção ter terminado.
                treinoDAO.salvarHistorico(historico);
                SyncManager.sincronizarEmSegundoPlano(this, usuarioId);
                int idUsuario = paraIntSeguro(usuarioId);
                HistoricoTreinoEntity melhor = treinoDAO.buscarMelhorResultado(idUsuario, treinoId);
                int quantidade = treinoDAO.contarExecucoes(idUsuario, treinoId);
                runOnUiThread(() -> exibirMelhorResultado(melhor, quantidade));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "A sessão terminou, mas não foi possível salvar o histórico.",
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    // ============================================================
    // MELHOR RESULTADO
    // ============================================================

    private void carregarMelhorResultado() {
        if (usuarioId <= 0) {
            txtSemHistorico.setVisibility(View.VISIBLE);
            containerMelhorResultado.setVisibility(View.GONE);
            txtQuantidadeExecucoes.setVisibility(View.GONE);
            return;
        }

        executor.execute(() -> {
            try {
                int idUsuario = paraIntSeguro(usuarioId);
                HistoricoTreinoEntity melhor = treinoDAO.buscarMelhorResultado(idUsuario, treinoId);
                int quantidade = treinoDAO.contarExecucoes(idUsuario, treinoId);
                runOnUiThread(() -> exibirMelhorResultado(melhor, quantidade));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> txtSemHistorico.setVisibility(View.VISIBLE));
            }
        });
    }

    private void exibirMelhorResultado(HistoricoTreinoEntity melhor, int quantidade) {
        if (isFinishing() || isDestroyed()) return;
        if (melhor == null) {
            txtSemHistorico.setVisibility(View.VISIBLE);
            containerMelhorResultado.setVisibility(View.GONE);
            txtQuantidadeExecucoes.setVisibility(View.GONE);
            return;
        }

        txtSemHistorico.setVisibility(View.GONE);
        containerMelhorResultado.setVisibility(View.VISIBLE);
        txtQuantidadeExecucoes.setVisibility(View.VISIBLE);
        txtQuantidadeExecucoes.setText(
                quantidade + (quantidade == 1 ? " sessão registrada" : " sessões registradas"));
        txtMelhorTempo.setText(formatarTempo(melhor.tempoSegundos * 1000L));
        txtMelhorPontuacao.setText(String.valueOf(melhor.pontuacao));
    }

    // ============================================================
    // INDICADOR BLUETOOTH
    // ============================================================

    private void atualizarConexaoUI(
            boolean conectado,
            String mensagem) {

        runOnUiThread(() -> {

            txtEstadoConexao.setText(
                    mensagem
            );

            if (conectado) {

                indicadorConexao.setBackgroundResource(
                        R.drawable.bg_indicador_conectado
                );

            } else {

                indicadorConexao.setBackgroundResource(
                        R.drawable.bg_indicador_desconectado
                );
            }
        });
    }

    // ============================================================
    // FECHAR BLUETOOTH
    // ============================================================

    private synchronized void fecharBluetooth() {

        bluetoothConectado = false;

        try {

            if (bluetoothInput != null) {
                bluetoothInput.close();
            }

        } catch (Exception ignored) {
        }

        try {

            if (bluetoothOutput != null) {
                bluetoothOutput.close();
            }

        } catch (Exception ignored) {
        }

        try {

            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }

        } catch (Exception ignored) {
        }

        bluetoothInput = null;
        bluetoothOutput = null;
        bluetoothSocket = null;
    }

    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        if (sinalInicioTreino != null) sinalInicioTreino.cancel();
        contagemInicioAtiva = false;
        encerrando = true;

        if (treinoAtivo && bluetoothOutput != null) {
            // Tenta interromper o modo classificação antes de fechar o socket.
            try {
                synchronized (this) {
                    bluetoothOutput.write("q\n".getBytes(StandardCharsets.UTF_8));
                    bluetoothOutput.flush();
                }
            } catch (IOException ignored) {
            }
        }

        treinoAtivo = false;

        mainHandler.removeCallbacksAndMessages(null);

        fecharBluetooth();

        // Não cancela tarefas pendentes: a gravação do histórico deve terminar mesmo
        // se o usuário sair da tela logo após concluir a sessão.
        executor.shutdown();
        if (cameraClient != null) cameraClient.close();

        super.onDestroy();
    }
}