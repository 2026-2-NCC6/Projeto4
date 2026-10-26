package com.example.smarttennisapp.jogo;

import com.example.smarttennisapp.BaseActivity;

import android.Manifest;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;

import com.example.smarttennisapp.VisaoComputacionalClient;
import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.util.SessaoUsuario;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/**
 * Jogos para treino em casa contra máquina de bolas + parede:
 *  - Rali de Combo: encadeie bolas na parede, mire nas zonas-alvo e suba o multiplicador.
 *  - Chefão: cada bola válida causa dano; acertar a zona-ponto-fraco dá dano dobrado.
 * A raquete (ESP32, sem alterações) dá tipo/força do golpe; a câmera (PC) confirma a bola e o impacto na parede.
 */
public class JogoActivity extends BaseActivity {

    private static final int REQ_BLUETOOTH = 4411;
    private static final long INTERVALO_TICK_MS = 250;
    private static final int COR_FUNDO = Color.parseColor("#0F1620");
    private static final int COR_TEXTO = Color.parseColor("#E6EDF3");
    private static final int COR_SUAVE = Color.parseColor("#A6B0BA");
    private static final int COR_ROXO = Color.parseColor("#B17AFF");
    private static final int COR_VERDE = Color.parseColor("#3DDC84");
    private static final int COR_AMARELO = Color.parseColor("#FFC857");
    private static final int COR_VERMELHO = Color.parseColor("#FF5C5C");

    // ---- serviços
    private final Handler principal = new Handler(Looper.getMainLooper());
    private RaqueteBluetooth raquete;
    private VisaoComputacionalClient visao;
    private MotorJogo motor;
    private Recordes recordes;
    private SharedPreferences prefsJogo;
    private String recordesPrefsKey;

    // ---- estado
    private boolean raqueteOk = false;
    private boolean estacaoOk = false;
    private boolean cameraAtiva = false;
    private boolean sessaoUsaCamera = false;
    private boolean destruindo = false;
    private int paredeCols = 0, paredeRows = 0;    // 0 = estação sem parede configurada
    private final ArrayDeque<String> historico = new ArrayDeque<>();

    // ---- views
    private TextView txtNivel, txtRaquete, txtEstacao, txtPontos, txtCombo, txtTempo, txtBoss, txtAlvo, txtMensagem, txtHistorico;
    private ProgressBar barraBoss;
    private RadioGroup grupoModo;
    private RadioButton radioRali, radioChefao;
    private CheckBox chkSemCamera, chkEspelhar;
    private EditText edtEndereco, edtToken;
    private Button btnIniciar;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (motor != null && motor.ativo()) {
                motor.tick(System.currentTimeMillis());
                atualizarHud();
                principal.postDelayed(this, INTERVALO_TICK_MS);
            }
        }
    };

    // ==================================================================== ciclo de vida
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefsJogo = getSharedPreferences("JogoPrefs", MODE_PRIVATE);
        recordesPrefsKey = "recordes_" + SessaoUsuario.obterId(this);
        String registroLocal = prefsJogo.getString(recordesPrefsKey, null);
        if (registroLocal == null) {
            // Migra uma unica vez o registro global de versoes antigas para o perfil ativo.
            registroLocal = prefsJogo.getString("recordes", "");
            if (!registroLocal.isEmpty()) {
                prefsJogo.edit().putString(recordesPrefsKey, registroLocal).remove("recordes").apply();
            }
        }
        recordes = Recordes.desserializar(registroLocal);
        setContentView(montarTela());

        raquete = new RaqueteBluetooth(this, new RaqueteBluetooth.Ouvinte() {
            @Override public void aoEstado(boolean conectado, String mensagem) {
                raqueteOk = conectado;
                txtRaquete.setText("🏸 " + mensagem);
                txtRaquete.setTextColor(conectado ? COR_VERDE : COR_SUAVE);
                atualizarBotao();
            }
            @Override public void aoLinha(String linha) {
                if (motor == null || !motor.ativo()) return;
                long agora = System.currentTimeMillis();
                SwingEvento swing = ParserRaquete.parse(linha, agora);
                if (swing != null) motor.aoReceberSwing(swing, agora);
            }
        });

        visao = new VisaoComputacionalClient();
        SharedPreferences vp = getSharedPreferences("VisionBridgePrefs", MODE_PRIVATE);
        edtEndereco.setText(vp.getString("address", ""));
        edtToken.setText(vp.getString("token", ""));
        if (!edtEndereco.getText().toString().trim().isEmpty() && !edtToken.getText().toString().trim().isEmpty()) {
            principal.postDelayed(this::conectarEstacao, 250);
        }
        atualizarNivel();
        pedirPermissaoEConectarRaquete();
    }

    @Override
    protected void onDestroy() {
        destruindo = true;
        principal.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (motor != null && motor.ativo()) motor.encerrar(System.currentTimeMillis());
        if (raquete != null) raquete.fechar();
        if (visao != null) visao.close();
        super.onDestroy();
    }

    // ==================================================================== conexões
    private void pedirPermissaoEConectarRaquete() {
        if (raquete.temPermissao()) {
            raquete.conectar();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                raquete.conectar();
            } else {
                txtRaquete.setText("🏸 Permissão Bluetooth negada.");
                txtRaquete.setTextColor(COR_VERMELHO);
            }
        }
    }

    private void conectarEstacao() {
        String endereco = edtEndereco.getText().toString().trim();
        String token = edtToken.getText().toString().trim();
        if (endereco.isEmpty() || token.isEmpty()) {
            edtEndereco.setError(endereco.isEmpty() ? "Informe o IP e a porta exibidos no computador." : null);
            edtToken.setError(token.isEmpty() ? "Informe o token da estação." : null);
            return;
        }
        getSharedPreferences("VisionBridgePrefs", MODE_PRIVATE).edit()
                .putString("address", endereco).putString("token", token).apply();
        txtEstacao.setText("💻 Conectando à estação...");
        txtEstacao.setTextColor(COR_SUAVE);
        visao.connect(endereco, token, new VisaoComputacionalClient.Listener() {
            @Override public void onStatus(boolean conectado, JSONObject status, String mensagem) {
                estacaoOk = conectado;
                cameraAtiva = conectado && visao.isCameraActive();
                JSONObject parede = status != null ? status.optJSONObject("wall") : null;
                if (parede != null) {
                    paredeCols = Math.max(1, parede.optInt("cols", 3));
                    paredeRows = Math.max(1, parede.optInt("rows", 1));
                    if (motor != null) motor.informarParede(paredeCols, paredeRows);
                } else {
                    paredeCols = 0;
                    paredeRows = 0;
                    if (motor != null) motor.informarSemParede();
                }
                String extra = !conectado ? "" : (paredeCols > 0 ? " · parede " + paredeCols + "×" + paredeRows : " · parede NÃO configurada");
                txtEstacao.setText("💻 " + mensagem + extra);
                txtEstacao.setTextColor(cameraAtiva ? COR_VERDE : (conectado ? COR_AMARELO : COR_SUAVE));
                atualizarBotao();
            }
            @Override public void onBallFlight(JSONObject ev) {
                if (motor == null || !motor.ativo()) return;
                motor.aoReceberBola(converterBola(ev), System.currentTimeMillis());
            }
        });
    }

    /** Converte o evento JSON da estação e leva os tempos do relógio do PC para o do celular. */
    private BolaEvento converterBola(JSONObject ev) {
        long offset = visao.getClockOffsetMs();   // relógio do PC - relógio do celular
        long ini = ev.optLong("start_epoch_ms") - offset;
        long fim = ev.optLong("end_epoch_ms") - offset;
        boolean configurada = ev.optBoolean("wall_configured", false);
        boolean acertou = configurada && ev.optBoolean("wall_hit", false);
        JSONObject grade = ev.optJSONObject("wall_grid");
        JSONObject impacto = ev.optJSONObject("impact");
        int cols = grade != null ? grade.optInt("cols", 0) : 0;
        int rows = grade != null ? grade.optInt("rows", 0) : 0;
        int col = impacto != null ? impacto.optInt("col", -1) : -1;
        int row = impacto != null ? impacto.optInt("row", -1) : -1;
        return new BolaEvento(ini, fim, ev.optDouble("max_speed_kmh", 0), ev.optDouble("average_speed_kmh", 0),
                configurada, acertou, cols, rows, col, row);
    }

    // ==================================================================== jogo
    private void aoClicarIniciar() {
        if (motor != null && motor.ativo()) {
            motor.encerrar(System.currentTimeMillis());
            return;
        }
        if (!raqueteOk) {
            Toast.makeText(this, "Conecte a raquete primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean usaCamera = !chkSemCamera.isChecked();
        final MotorJogo.Modo modo = radioChefao.isChecked() ? MotorJogo.Modo.CHEFAO : MotorJogo.Modo.RALI_COMBO;
        if (!usaCamera) {
            comecar(modo, false);
            return;
        }
        if (!estacaoOk) {
            Toast.makeText(this, "Conecte à estação do computador (ou marque 'Jogar sem câmera').", Toast.LENGTH_LONG).show();
            return;
        }
        btnIniciar.setEnabled(false);
        // Sessão sem metas de velocidade/bolas: o jogo calcula tudo a partir dos eventos de voo.
        visao.startSession(UUID.randomUUID().toString(), "JOGO_" + modo.name(), 0f, 0, (ok, resposta, mensagem) -> {
            btnIniciar.setEnabled(true);
            if (ok) {
                comecar(modo, true);
            } else {
                Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void comecar(MotorJogo.Modo modo, boolean usaCamera) {
        MotorJogo.Config cfg = new MotorJogo.Config();
        cfg.modo = modo;
        cfg.usaCamera = usaCamera;
        cfg.espelharLados = chkEspelhar.isChecked();
        cfg.nivelJogador = Progressao.nivel(recordes.xp);
        cfg.duracaoMs = 180_000;
        sessaoUsaCamera = usaCamera;
        motor = new MotorJogo(cfg, recordes, new MotorJogo.Ouvinte() {
            @Override public void aoSwing(SwingEvento s) { aoSwingDetectado(s); }
            @Override public void aoResolver(MotorJogo.Resolucao r) { aoGolpeResolvido(r); }
            @Override public void aoNovoAlvo(int zona) { atualizarHud(); }
            @Override public void aoFim(MotorJogo.Resumo r) { aoFimDeJogo(r); }
        });
        if (usaCamera && paredeCols > 0) motor.informarParede(paredeCols, paredeRows);
        historico.clear();
        motor.iniciar(System.currentTimeMillis());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mensagem("Valendo! Bata na bola.", COR_ROXO);
        btnIniciar.setText("Parar");
        principal.removeCallbacks(ticker);
        principal.post(ticker);
        atualizarHud();
    }

    private void aoSwingDetectado(SwingEvento s) {
        String desc;
        if ("RUI".equals(s.codigoClasse)) {
            desc = "Movimento classificado como ruído/outro";
        } else if (!s.temClasseIdentificada()) {
            desc = "golpe não identificado";
        } else {
            String medida = s.velRaqueteKmh > 0
                    ? String.format(Locale.US, "%.0f km/h", s.velRaqueteKmh)
                    : (s.velAngularDps > 0 ? String.format(Locale.US, "%.0f°/s", s.velAngularDps) : "velocidade indisponível");
            desc = String.format(Locale.US, "%s · clareza %d%% · %s", s.nomeClasse, s.confiancaPct, medida);
        }
        mensagem("🎾 " + desc + (sessaoUsaCamera ? "\naguardando a câmera…" : ""), COR_SUAVE);
    }

    private void aoGolpeResolvido(MotorJogo.Resolucao r) {
        StringBuilder sb = new StringBuilder();
        int cor;
        switch (r.resultado) {
            case VALIDADO:
                sb.append(motor.alvoDisponivel() || r.zona >= 0 ? "✅ NA PAREDE!" : "✅ Boa!");
                cor = COR_VERDE;
                break;
            case BOLA_SEM_PAREDE:
                sb.append("⚠️ Bola não chegou à parede");
                cor = COR_AMARELO;
                break;
            default:
                sb.append(r.swing.reconhecido() ? "👀 Sem leitura da câmera" : "❔ Golpe não identificado");
                cor = COR_SUAVE;
        }
        sb.append("  +").append(r.pontos);
        if (r.acertouAlvo) sb.append("\n🎯 ALVO ACERTADO ×2");
        else if (r.zona >= 0) sb.append("\nzona: ").append(MotorJogo.nomeZona(r.zona, motor.cols(), motor.rows()));
        if (r.critico) sb.append("\n⚡ CRÍTICO (bola rápida) +").append(MotorJogo.BONUS_CRITICO);
        if (r.chefaoDerrotado) sb.append("\n👑 CHEFÃO DERROTADO! +").append(MotorJogo.BONUS_CHEFAO);
        if (r.velBolaKmh > 0 && r.velBolaKmh <= MotorJogo.LIMITE_VEL_PLAUSIVEL_KMH) {
            sb.append(String.format(Locale.US, "\nbola ~%.0f km/h", r.velBolaKmh));
        }
        mensagem(sb.toString(), cor);
        if (r.resultado == MotorJogo.Resultado.VALIDADO) {
            getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        historico.addFirst(String.format(Locale.US, "%s %s %+d  (combo %d)",
                r.swing.temClasseIdentificada() ? r.swing.nomeClasse : "?", r.resultado == MotorJogo.Resultado.VALIDADO ? "✔" : (r.resultado == MotorJogo.Resultado.BOLA_SEM_PAREDE ? "✖" : "·"), r.pontos, r.combo));
        while (historico.size() > 6) historico.removeLast();
        atualizarHud();
    }

    private void aoFimDeJogo(MotorJogo.Resumo r) {
        principal.removeCallbacks(ticker);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        visao.stopSession();
        prefsJogo.edit().putString(recordesPrefsKey, recordes.serializar()).apply();
        SyncManager.sincronizarEmSegundoPlano(this, SessaoUsuario.obterId(this));
        if (destruindo) return;   // a tela está sendo fechada: só salva, sem diálogo
        btnIniciar.setText("Iniciar");
        atualizarNivel();
        atualizarHud();

        StringBuilder sb = new StringBuilder();
        sb.append("Pontos: ").append(r.pontos).append('\n');
        sb.append("Bolas na parede: ").append(r.validados).append(" de ").append(r.golpes).append(" golpes\n");
        sb.append("Maior combo: ").append(r.maiorCombo).append(r.novoRecordeCombo ? "  🏆 recorde!" : "").append('\n');
        if (r.alvosAcertados > 0) sb.append("Alvos acertados: ").append(r.alvosAcertados).append('\n');
        if (r.criticos > 0) sb.append("Críticos: ").append(r.criticos).append('\n');
        if (r.chefoesDerrotados > 0) sb.append("Chefões derrotados: ").append(r.chefoesDerrotados).append('\n');
        if (r.velMaxBolaKmh > 0) {
            sb.append(String.format(Locale.US, "Bola mais rápida: ~%.0f km/h", r.velMaxBolaKmh)).append(r.novoRecordeVelocidade ? "  🏆 recorde!" : "").append('\n');
        }
        if (r.forcaRelativaMedia > 0) sb.append(String.format(Locale.US, "Velocidade média da raquete: %.0f%% do seu normal", r.forcaRelativaMedia * 100)).append(r.novoRecordeForca ? "  🏆 recorde!" : "").append('\n');
        sb.append("\n+").append(r.xpGanho).append(" XP");
        if (r.nivelDepois > r.nivelAntes) sb.append("  ·  SUBIU PARA O NÍVEL ").append(r.nivelDepois).append("! 🎉");
        new AlertDialog.Builder(this)
                .setTitle("Fim de jogo")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // ==================================================================== HUD
    private void mensagem(String texto, int cor) {
        txtMensagem.setText(texto);
        txtMensagem.setTextColor(cor);
    }

    private void atualizarBotao() {
        boolean podeIniciar = raqueteOk && (chkSemCamera.isChecked() || estacaoOk);
        if (motor == null || !motor.ativo()) btnIniciar.setEnabled(podeIniciar);
    }

    private void atualizarNivel() {
        int n = Progressao.nivel(recordes.xp);
        long ini = Progressao.xpInicioDoNivel(n), fim = Progressao.xpInicioDoNivel(n + 1);
        txtNivel.setText(String.format(Locale.US, "Nível %d · XP %d/%d · recordes: bola %.0f km/h · raquete %.0f km/h · combo %d",
                n, recordes.xp - ini, fim - ini, recordes.prVelBolaKmh, recordes.prVelRaqueteKmh, recordes.maiorCombo));
    }

    private void atualizarHud() {
        if (motor == null) return;
        long agora = System.currentTimeMillis();
        txtPontos.setText(motor.pontos() + " pts");
        txtCombo.setText(String.format(Locale.US, "COMBO %d  ×%.1f", motor.combo(), motor.multComboAtual()));
        long s = motor.tempoRestanteMs(agora) / 1000;
        txtTempo.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
        boolean chefao = motor.modo() == MotorJogo.Modo.CHEFAO;
        barraBoss.setVisibility(chefao ? View.VISIBLE : View.GONE);
        txtBoss.setVisibility(chefao ? View.VISIBLE : View.GONE);
        if (chefao) {
            barraBoss.setMax((int) motor.chefaoHpMax());
            barraBoss.setProgress((int) motor.chefaoHp());
            txtBoss.setText(String.format(Locale.US, "👹 CHEFÃO #%d   %.0f / %.0f HP", motor.chefaoNumero(), motor.chefaoHp(), motor.chefaoHpMax()));
        }
        if (motor.alvoDisponivel()) {
            txtAlvo.setText("🎯 " + (chefao ? "PONTO FRACO: " : "ALVO: ") + MotorJogo.nomeZona(motor.alvoZona(), motor.cols(), motor.rows()) + "  (×2)");
        } else {
            txtAlvo.setText(chkSemCamera.isChecked() ? "Modo sem câmera: vale golpe com clareza ≥ 60%" : "Sem alvos: configure a parede no computador");
        }
        StringBuilder sb = new StringBuilder();
        for (String h : historico) sb.append(h).append('\n');
        txtHistorico.setText(sb.toString().trim());
    }

    // ==================================================================== construção da tela
    private View montarTela() {
        ScrollView rolagem = new ScrollView(this);
        rolagem.setBackgroundColor(COR_FUNDO);
        rolagem.setFillViewport(true);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(18), dp(18), dp(18), dp(24));
        rolagem.addView(raiz, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cabecalho = new LinearLayout(this);
        cabecalho.setOrientation(LinearLayout.HORIZONTAL);
        cabecalho.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton btnVoltarRali = new ImageButton(this);
        btnVoltarRali.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnVoltarRali.setImageResource(com.example.smarttennisapp.R.drawable.setavoltar);
        btnVoltarRali.setBackgroundResource(com.example.smarttennisapp.R.drawable.bg_back_button);
        btnVoltarRali.setPadding(dp(11), dp(11), dp(11), dp(11));
        btnVoltarRali.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        btnVoltarRali.setColorFilter(COR_TEXTO);
        btnVoltarRali.setContentDescription("Voltar");
        btnVoltarRali.setOnClickListener(v -> finish());
        cabecalho.addView(btnVoltarRali);
        TextView tituloRali = texto("🏆 Rali na Parede", 26, COR_ROXO, true);
        LinearLayout.LayoutParams tituloParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tituloParams.leftMargin = dp(12);
        cabecalho.addView(tituloRali, tituloParams);
        raiz.addView(cabecalho);
        txtNivel = texto("", 13, COR_SUAVE, false);
        raiz.addView(txtNivel);

        grupoModo = new RadioGroup(this);
        grupoModo.setOrientation(LinearLayout.HORIZONTAL);
        radioRali = new RadioButton(this);
        radioRali.setId(View.generateViewId());
        radioRali.setText("Rali de Combo");
        radioRali.setTextColor(COR_TEXTO);
        radioChefao = new RadioButton(this);
        radioChefao.setId(View.generateViewId());
        radioChefao.setText("Chefão");
        radioChefao.setTextColor(COR_TEXTO);
        grupoModo.addView(radioRali);
        grupoModo.addView(radioChefao);
        radioRali.setChecked(true);
        raiz.addView(grupoModo, margem(14, 0));

        chkSemCamera = new CheckBox(this);
        chkSemCamera.setText("Jogar sem câmera (só raquete)");
        chkSemCamera.setTextColor(COR_SUAVE);
        chkSemCamera.setOnCheckedChangeListener((b, marcado) -> { atualizarBotao(); atualizarHud(); });
        raiz.addView(chkSemCamera);
        chkEspelhar = new CheckBox(this);
        chkEspelhar.setText("Inverter esquerda/direita (se a câmera estiver de frente)");
        chkEspelhar.setTextColor(COR_SUAVE);
        raiz.addView(chkEspelhar);

        txtRaquete = texto("🏸 Raquete: desconectada", 14, COR_SUAVE, false);
        raiz.addView(txtRaquete, margem(10, 0));
        Button btnRaquete = botao("Reconectar raquete");
        btnRaquete.setOnClickListener(v -> pedirPermissaoEConectarRaquete());
        raiz.addView(btnRaquete);

        txtEstacao = texto("💻 Estação: desconectada", 14, COR_SUAVE, false);
        raiz.addView(txtEstacao, margem(10, 0));
        edtEndereco = new EditText(this);
        edtEndereco.setHint("IP:porta do computador (ex.: 192.168.0.10:8765)");
        edtEndereco.setTextColor(COR_TEXTO);
        edtEndereco.setHintTextColor(COR_SUAVE);
        edtEndereco.setSingleLine(true);
        raiz.addView(edtEndereco);
        edtToken = new EditText(this);
        edtToken.setHint("Token da estação");
        edtToken.setTextColor(COR_TEXTO);
        edtToken.setHintTextColor(COR_SUAVE);
        edtToken.setSingleLine(true);
        raiz.addView(edtToken);
        Button btnEstacao = botao("Conectar à estação");
        btnEstacao.setOnClickListener(v -> conectarEstacao());
        raiz.addView(btnEstacao);

        btnIniciar = botao("Iniciar");
        btnIniciar.setEnabled(false);
        btnIniciar.setOnClickListener(v -> aoClicarIniciar());
        raiz.addView(btnIniciar, margem(16, 0));

        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        txtPontos = texto("0 pts", 30, COR_TEXTO, true);
        txtTempo = texto("03:00", 30, COR_SUAVE, true);
        txtTempo.setGravity(Gravity.END);
        linha.addView(txtPontos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        linha.addView(txtTempo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        raiz.addView(linha, margem(12, 0));

        txtCombo = texto("COMBO 0  ×1.0", 20, COR_AMARELO, true);
        raiz.addView(txtCombo);
        txtBoss = texto("", 15, COR_VERMELHO, true);
        raiz.addView(txtBoss, margem(8, 0));
        barraBoss = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        raiz.addView(barraBoss, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        barraBoss.setVisibility(View.GONE);
        txtBoss.setVisibility(View.GONE);

        txtAlvo = texto("", 17, COR_VERDE, true);
        raiz.addView(txtAlvo, margem(10, 0));
        txtMensagem = texto("Conecte a raquete e a estação, escolha o modo e toque em Iniciar.", 20, COR_SUAVE, true);
        txtMensagem.setMinLines(4);
        raiz.addView(txtMensagem, margem(10, 0));
        txtHistorico = texto("", 14, COR_SUAVE, false);
        txtHistorico.setTypeface(Typeface.MONOSPACE);
        raiz.addView(txtHistorico, margem(8, 0));
        return rolagem;
    }

    private TextView texto(String conteudo, int sp, int cor, boolean negrito) {
        TextView t = new TextView(this);
        t.setText(conteudo);
        t.setTextSize(sp);
        t.setTextColor(cor);
        if (negrito) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button botao(String rotulo) {
        Button b = new Button(this);
        b.setText(rotulo);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams margem(int topoDp, int baixoDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topoDp);
        lp.bottomMargin = dp(baixoDp);
        return lp;
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }
}
