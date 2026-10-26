
package com.example.smarttennisapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.app.ActivityCompat;

import com.example.smarttennisapp.util.BluetoothRaqueteUtil;
import com.example.smarttennisapp.util.SessaoUsuario;
import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.HistoricoTreinoEntity;
import com.example.smarttennisapp.jogo.ParserRaquete;
import com.example.smarttennisapp.jogo.RegistroGolpesSessao;
import com.example.smarttennisapp.jogo.SwingEvento;

import org.json.JSONObject;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class TreinoLivre extends BaseActivity {

    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BufferedReader leitor;
    private OutputStream saida;

    private Thread threadConexao;
    private Thread threadLeitura;

    private volatile boolean conectado = false;
    private volatile boolean encerrando = false;
    private volatile boolean treinoAtivo = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView btnVoltar;
    private TextView txtEstadoConexao;
    private TextView txtEstadoTreino;
    private TextView txtCronometro;
    private TextView txtTotalGolpes;
    private TextView txtSaques;
    private TextView txtForehands;
    private TextView txtBackhands;
    private TextView txtUltimoGolpe;
    private TextView txtConfianca;
    private TextView txtAceleracao;
    private TextView txtVelocidadeAngular;
    private TextView txtGolpesNaoIdentificados;

    private Button btnIniciarTreino;
    private Button btnPararTreino;
    private InicioTreinoOverlay sinalInicioTreino;
    private boolean contagemInicioAtiva = false;

    private int totalSaques = 0;
    private int totalForehands = 0;
    private int totalBackhands = 0;
    private int totalNaoIdentificados = 0;
    private final RegistroGolpesSessao registroGolpes = new RegistroGolpesSessao();

    private long inicioTreinoMs = 0L;
    private long tempoAcumuladoMs = 0L;
    private boolean historicoSessaoSalvo = false;

    private final Runnable atualizarCronometro = new Runnable() {
        @Override
        public void run() {
            if (treinoAtivo) {
                long decorrido = tempoAcumuladoMs
                        + (System.currentTimeMillis() - inicioTreinoMs);

                long segundos = decorrido / 1000;
                long minutos = segundos / 60;

                txtCronometro.setText(String.format(
                        java.util.Locale.getDefault(),
                        "%02d:%02d",
                        minutos,
                        segundos % 60
                ));

                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_treino_livre);

        inicializarViews();
        sinalInicioTreino = new InicioTreinoOverlay(
                findViewById(R.id.overlayInicioTreino),
                findViewById(R.id.txtStartSignalNumero),
                findViewById(R.id.txtStartSignalMensagem)
        );
        configurarBotoes();
        atualizarEstadoConexao(false);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (view, insets) -> {
                    Insets systemBars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );

                    view.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            systemBars.bottom
                    );

                    return insets;
                }
        );

        if (bluetoothAdapter == null) {
            mostrarMensagem("Este celular não suporta Bluetooth.");
            return;
        }

        verificarPermissaoEConectar();
    }

    private void inicializarViews() {
        btnVoltar = findViewById(R.id.btnVoltar);

        txtEstadoConexao = findViewById(R.id.txtEstadoConexao);
        txtEstadoTreino = findViewById(R.id.txtEstadoTreino);
        txtCronometro = findViewById(R.id.txtCronometro);
        txtTotalGolpes = findViewById(R.id.txtTotalGolpes);

        txtSaques = findViewById(R.id.txtSaques);
        txtForehands = findViewById(R.id.txtForehands);
        txtBackhands = findViewById(R.id.txtBackhands);

        txtUltimoGolpe = findViewById(R.id.txtUltimoGolpe);
        txtConfianca = findViewById(R.id.txtConfianca);
        txtAceleracao = findViewById(R.id.txtAceleracao);
        txtVelocidadeAngular = findViewById(
                R.id.txtVelocidadeAngular
        );

        txtGolpesNaoIdentificados = findViewById(
                R.id.txtGolpesNaoIdentificados
        );

        btnIniciarTreino = findViewById(R.id.btnIniciarTreino);
        btnPararTreino = findViewById(R.id.btnPararTreino);
    }

    private void configurarBotoes() {
        btnVoltar.setOnClickListener(v -> finish());

        btnIniciarTreino.setOnClickListener(v -> iniciarTreino());

        btnPararTreino.setOnClickListener(v -> pararTreino());
    }

    private boolean temPermissaoBluetooth() {
        return BluetoothRaqueteUtil.temPermissaoConectar(this);
    }

    private void verificarPermissaoEConectar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !temPermissaoBluetooth()) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_BLUETOOTH_CONNECT
            );

            return;
        }

        conectarAoESP32();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                verificarPermissaoEConectar();
            } else {
                atualizarEstadoConexao(false);
                mostrarMensagem(
                        "A permissão Bluetooth é necessária para conectar à raquete."
                );
            }
        }
    }

    private void conectarAoESP32() {
        if (encerrando || conectado) {
            return;
        }

        if (!temPermissaoBluetooth()) {
            verificarPermissaoEConectar();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            atualizarEstadoConexao(false);
            mostrarMensagem(
                    "Ative o Bluetooth e abra novamente a tela de treino."
            );
            return;
        }

        btnIniciarTreino.setEnabled(false);
        txtEstadoConexao.setText("Conectando...");
        txtEstadoConexao.setBackgroundResource(R.drawable.bg_status_off);

        threadConexao = new Thread(() -> {
            BluetoothSocket socketTemporario = null;

            try {
                BluetoothDevice dispositivoESP32 =
                        BluetoothRaqueteUtil.encontrarDispositivoPareado(
                                this,
                                bluetoothAdapter
                        );

                if (dispositivoESP32 == null) {
                    executarNaUI(() -> {
                        txtEstadoConexao.setText("Não pareado");
                        txtEstadoConexao.setBackgroundResource(
                                R.drawable.bg_status_off
                        );

                        btnIniciarTreino.setEnabled(true);

                        mostrarMensagem(
                                "Pareie RAQUETE-TENIS nas configurações Bluetooth do celular."
                        );
                    });

                    return;
                }

                socketTemporario =
                        BluetoothRaqueteUtil.criarSocket(dispositivoESP32);

                socketTemporario.connect();

                if (encerrando) {
                    socketTemporario.close();
                    return;
                }

                bluetoothSocket = socketTemporario;
                leitor = new BufferedReader(
                        new InputStreamReader(
                                bluetoothSocket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );

                saida = bluetoothSocket.getOutputStream();
                conectado = true;

                executarNaUI(() -> {
                    atualizarEstadoConexao(true);
                    btnIniciarTreino.setEnabled(true);
                    mostrarMensagem("Conectado à raquete!");
                });

                iniciarLeituraDados();

            } catch (SecurityException e) {
                fecharSocketTemporario(socketTemporario);

                executarNaUI(() -> {
                    atualizarEstadoConexao(false);
                    btnIniciarTreino.setEnabled(true);
                    mostrarMensagem("Permissão Bluetooth não concedida.");
                });

            } catch (IOException e) {
                fecharSocketTemporario(socketTemporario);

                executarNaUI(() -> {
                    atualizarEstadoConexao(false);
                    btnIniciarTreino.setEnabled(true);
                    mostrarMensagem(
                            "Não foi possível conectar à raquete. Verifique se ela está ligada."
                    );
                });
            }
        });

        threadConexao.start();
    }

    private void fecharSocketTemporario(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void iniciarLeituraDados() {
        threadLeitura = new Thread(() -> {
            try {
                String linha;

                while (conectado && !encerrando
                        && (linha = leitor.readLine()) != null) {

                    final String mensagem = linha.trim();

                    if (!mensagem.isEmpty()) {
                        executarNaUI(() -> processarMensagem(mensagem));
                    }
                }

            } catch (IOException e) {
                if (!encerrando) {
                    executarNaUI(() -> {
                        conectado = false;
                        if (treinoAtivo) {
                            tempoAcumuladoMs += Math.max(
                                    0L, System.currentTimeMillis() - inicioTreinoMs
                            );
                            treinoAtivo = false;
                            salvarHistoricoTreinoLivre(false);
                        } else {
                            treinoAtivo = false;
                        }

                        handler.removeCallbacks(atualizarCronometro);

                        atualizarEstadoConexao(false);
                        atualizarEstadoTreino(false);

                        btnIniciarTreino.setEnabled(true);
                        btnPararTreino.setEnabled(false);

                        mostrarMensagem("A conexão com a raquete foi perdida.");
                    });
                }

            } finally {
                if (!encerrando) {
                    conectado = false;
                }
            }
        });

        threadLeitura.start();
    }

    private void enviarComando(String comando) {
        if (!conectado || saida == null) {
            mostrarMensagem("Conecte-se à raquete antes de iniciar.");
            return;
        }

        try {
            saida.write((comando + "\n").getBytes(StandardCharsets.UTF_8));
            saida.flush();

        } catch (IOException e) {
            conectado = false;
            atualizarEstadoConexao(false);
            mostrarMensagem("Falha ao enviar comando para a raquete.");
        }
    }

    private void iniciarTreino() {
        if (!conectado) {
            verificarPermissaoEConectar();
            mostrarMensagem("Aguardando conexão com a raquete.");
            return;
        }

        if (treinoAtivo || contagemInicioAtiva) {
            return;
        }

        // Sinaliza visualmente o início e só liga o cronômetro ao terminar a contagem.
        contagemInicioAtiva = true;
        btnIniciarTreino.setEnabled(false);
        sinalInicioTreino.start(() -> {
            contagemInicioAtiva = false;
            if (!conectado || encerrando) {
                atualizarEstadoConexao(false);
                mostrarMensagem("A conexão com a raquete foi perdida. Conecte novamente para iniciar.");
                return;
            }
            iniciarTreinoAgora();
        });
    }

    private void iniciarTreinoAgora() {
        if (!conectado) {
            atualizarEstadoConexao(false);
            mostrarMensagem("Conecte-se à raquete antes de iniciar.");
            return;
        }
        if (treinoAtivo) return;

        // Cada início cria uma sessão independente no histórico.
        totalSaques = 0;
        totalForehands = 0;
        totalBackhands = 0;
        totalNaoIdentificados = 0;
        registroGolpes.limpar();
        tempoAcumuladoMs = 0L;
        historicoSessaoSalvo = false;
        atualizarContadores();
        txtUltimoGolpe.setText("Aguardando golpe");
        txtConfianca.setText("Confiança: --");
        txtAceleracao.setText("-- g");
        txtVelocidadeAngular.setText("-- km/h");

        enviarComando("k");

        inicioTreinoMs = System.currentTimeMillis();
        treinoAtivo = true;

        atualizarEstadoTreino(true);

        btnIniciarTreino.setEnabled(false);
        btnPararTreino.setEnabled(true);

        handler.removeCallbacks(atualizarCronometro);
        handler.post(atualizarCronometro);
    }

    private void pararTreino() {
        if (!treinoAtivo) {
            return;
        }

        enviarComando("q");

        tempoAcumuladoMs += Math.max(0L, System.currentTimeMillis() - inicioTreinoMs);
        treinoAtivo = false;
        salvarHistoricoTreinoLivre(true);

        handler.removeCallbacks(atualizarCronometro);

        atualizarEstadoTreino(false);

        btnIniciarTreino.setEnabled(conectado);
        btnPararTreino.setEnabled(false);

        // Solicita ao ESP32 o resumo da sessão.
        if (conectado) {
            enviarComando("s");
        }
    }

    private void atualizarEstadoConexao(boolean estaConectado) {
        if (estaConectado) {
            txtEstadoConexao.setText("● Conectado");
            txtEstadoConexao.setTextColor(getColor(R.color.accent));
            txtEstadoConexao.setBackgroundResource(
                    R.drawable.bg_status_on
            );
        } else {
            txtEstadoConexao.setText("● Desconectado");
            txtEstadoConexao.setTextColor(getColor(R.color.danger));
            txtEstadoConexao.setBackgroundResource(
                    R.drawable.bg_status_off
            );
        }

        btnIniciarTreino.setEnabled(estaConectado && !treinoAtivo && !contagemInicioAtiva);
    }

    private void atualizarEstadoTreino(boolean ativo) {
        if (ativo) {
            txtEstadoTreino.setText("TREINO ATIVO");
            txtEstadoTreino.setTextColor(getColor(R.color.accent));
        } else {
            txtEstadoTreino.setText("TREINO PARADO");
            txtEstadoTreino.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void processarMensagem(String linha) {
        if (ehErroDoClassificador(linha)) {
            abortarInicioSemModelo();
            return;
        }

        // Confirmação de início enviada pelo firmware.
        if (linha.contains("# CLASSIFICANDO")) {
            treinoAtivo = true;
            atualizarEstadoTreino(true);
            return;
        }

        // O firmware informa quando não existe modelo de classificação.
        if (linha.contains("# sem modelo")) {
            treinoAtivo = false;
            tempoAcumuladoMs = 0L;
            inicioTreinoMs = 0L;
            handler.removeCallbacks(atualizarCronometro);
            atualizarEstadoTreino(false);
            btnIniciarTreino.setEnabled(conectado);
            btnPararTreino.setEnabled(false);

            mostrarMensagem(
                    "O ESP32 não possui um modelo de classificação válido."
            );
            return;
        }

        if (linha.contains("# parado")) {
            if (treinoAtivo) {
                tempoAcumuladoMs += Math.max(
                        0L, System.currentTimeMillis() - inicioTreinoMs
                );
                treinoAtivo = false;
                handler.removeCallbacks(atualizarCronometro);
                salvarHistoricoTreinoLivre(false);
                btnIniciarTreino.setEnabled(conectado);
                btnPararTreino.setEnabled(false);
            }
            atualizarEstadoTreino(false);
            return;
        }

        // O firmware v2 envia, por exemplo:
        // #12  >> FH DRIVE     85%  ~92km/h a=12.3g
        // #13  ???  nao identificado (perto de FHD, d=...) w=...
        SwingEvento evento = ParserRaquete.parse(linha, System.currentTimeMillis());
        if (evento != null) {
            registroGolpes.registrar(evento);
            if (!evento.temClasseIdentificada()) {
                totalNaoIdentificados++;
                txtUltimoGolpe.setText("Golpe não identificado");
                txtConfianca.setText("Confiança: --");
                txtAceleracao.setText(evento.aceleracaoG > 0
                        ? String.format(java.util.Locale.getDefault(), "%.1f g", evento.aceleracaoG) : "-- g");
                txtVelocidadeAngular.setText(evento.velRaqueteKmh > 0
                        ? String.format(java.util.Locale.getDefault(), "%.0f km/h", evento.velRaqueteKmh) : "-- km/h");
            } else {
                switch (evento.golpe) {
                    case "SAQUE": totalSaques++; break;
                    case "FOREHAND": totalForehands++; break;
                    case "BACKHAND": totalBackhands++; break;
                    default: break; // Smash, amortida, lob e ruído são detalhados no registro por classe.
                }
                txtUltimoGolpe.setText(evento.nomeClasse);
                txtConfianca.setText(evento.confiancaPct > 0
                        ? "Confiança: " + evento.confiancaPct + "%" : "Confiança: --");
                txtAceleracao.setText(evento.aceleracaoG > 0
                        ? String.format(java.util.Locale.getDefault(), "%.1f g", evento.aceleracaoG) : "-- g");
                txtVelocidadeAngular.setText(evento.velRaqueteKmh > 0
                        ? String.format(java.util.Locale.getDefault(), "%.0f km/h", evento.velRaqueteKmh) : "-- km/h");
            }
            atualizarContadores();
            return;
        }

        // O resumo do comando "s" no firmware v2 é diagnóstico; os contadores
        // locais já foram agregados evento a evento pelo parser compartilhado.
    }


    private boolean ehErroDoClassificador(String linha) {
        if (linha == null) return false;
        String normalizada = linha.toLowerCase(Locale.ROOT);
        return normalizada.contains("erro: preciso de >= 2 classes")
                || normalizada.contains("# sem modelo")
                || normalizada.contains("# sem classes ativas")
                || normalizada.contains("dados insuficientes: modelo invalidado");
    }

    /** Não registra sessão vazia quando o ESP32 não consegue ativar o modelo kNN. */
    private void abortarInicioSemModelo() {
        if (!treinoAtivo && !contagemInicioAtiva) return;
        treinoAtivo = false;
        contagemInicioAtiva = false;
        inicioTreinoMs = 0L;
        tempoAcumuladoMs = 0L;
        handler.removeCallbacks(atualizarCronometro);
        if (sinalInicioTreino != null) sinalInicioTreino.cancel();
        if (conectado) enviarComando("q");
        atualizarEstadoTreino(false);
        btnIniciarTreino.setEnabled(conectado);
        btnPararTreino.setEnabled(false);
        mostrarMensagem("Classificador indisponível. Cadastre pelo menos 5 exemplos em cada uma de 2 classes no ESP32 e tente novamente.");
    }

    private void atualizarContadores() {
        txtSaques.setText(String.valueOf(totalSaques));
        txtForehands.setText(String.valueOf(totalForehands));
        txtBackhands.setText(String.valueOf(totalBackhands));

        txtTotalGolpes.setText(registroGolpes.getTotalEventos() + "\nEVENTOS");

        int ruidoOutro = registroGolpes.getQuantidade("RUI");
        txtGolpesNaoIdentificados.setText(
                "Não identificados: " + totalNaoIdentificados + " · Ruído/outro: " + ruidoOutro
        );
    }

    /** Salva uma sessão do Treino Livre no histórico existente, sem bloquear a interface. */
    private void salvarHistoricoTreinoLivre(boolean concluido) {
        if (historicoSessaoSalvo || inicioTreinoMs <= 0 || tempoAcumuladoMs <= 0) {
            return;
        }
        historicoSessaoSalvo = true;

        int usuarioId = SessaoUsuario.obterId(getApplicationContext());
        if (usuarioId <= 0) {
            android.util.Log.w("SmartTennis", "Sessão livre não salva: usuário sem sessão válida.");
            return;
        }

        int totalDetectado = registroGolpes.getTotalEventos();
        int golpesIdentificados = Math.max(0, totalDetectado - totalNaoIdentificados);
        HistoricoTreinoEntity item = new HistoricoTreinoEntity();
        item.usuarioId = usuarioId;
        item.treinoId = "TREINO_LIVRE";
        item.dataConclusao = System.currentTimeMillis();
        item.tempoSegundos = (int) Math.min(Integer.MAX_VALUE, tempoAcumuladoMs / 1000L);
        item.pontuacao = 0; // Treino Livre mede volume; não inventa pontuação de jogo.
        item.saques = totalSaques;
        item.forehands = totalForehands;
        item.backhands = totalBackhands;
        item.golpesCorretos = golpesIdentificados;
        item.totalGolpes = totalDetectado;
        item.golpesRecebidos = totalDetectado;
        item.concluido = concluido;

        item.metricasGolpesJson = registroGolpes.paraJson("TREINO_LIVRE");
        try {
            JSONObject extras = new JSONObject();
            extras.put("origem", "TREINO_LIVRE");
            extras.put("golpesNaoIdentificados", totalNaoIdentificados);
            // Mantém um espelho no campo já sincronizado por versões anteriores do backend.
            extras.put("metricasGolpes", new JSONObject(item.metricasGolpesJson));
            item.metricasCameraJson = extras.toString();
        } catch (Exception ignorada) {
            item.metricasCameraJson = "{\"origem\":\"TREINO_LIVRE\"}";
        }

        new Thread(() -> {
            try {
                DatabaseProvider.getInstance(getApplicationContext())
                        .treinoDAO().salvarHistorico(item);
                SyncManager.sincronizarEmSegundoPlano(getApplicationContext(), usuarioId);
            } catch (Exception erro) {
                android.util.Log.e(
                        "SmartTennis",
                        "Falha ao salvar histórico do Treino Livre",
                        erro
                );
            }
        }, "salvar-historico-livre").start();
    }

    private void executarNaUI(Runnable acao) {
        handler.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                acao.run();
            }
        });
    }

    private void mostrarMensagem(String mensagem) {
        Toast.makeText(
                this,
                mensagem,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        if (sinalInicioTreino != null) sinalInicioTreino.cancel();
        if (treinoAtivo) {
            tempoAcumuladoMs += Math.max(
                    0L, System.currentTimeMillis() - inicioTreinoMs
            );
            if (conectado) enviarComando("q");
            treinoAtivo = false;
            salvarHistoricoTreinoLivre(false);
        }
        encerrando = true;
        conectado = false;

        handler.removeCallbacksAndMessages(null);

        if (threadConexao != null) {
            threadConexao.interrupt();
        }

        if (threadLeitura != null) {
            threadLeitura.interrupt();
        }

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException ignored) {
        }

        super.onDestroy();
    }
}
