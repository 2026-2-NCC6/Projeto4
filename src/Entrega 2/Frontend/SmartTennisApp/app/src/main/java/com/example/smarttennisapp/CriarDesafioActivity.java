package com.example.smarttennisapp;

import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;


import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.DesafioEntity;
import com.example.smarttennisapp.jogo.ClasseGolpeRaquete;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tela para criar e editar desafios personalizados, inclusive offline. */
public class CriarDesafioActivity extends BaseActivity {

    private static final int MAX_ETAPAS = 50;
    private static final int DURACAO_MAXIMA_MINUTOS = 90;

    // As três primeiras opções preservam a seleção genérica usada pelos desafios antigos.
    private static final String[] GOLPES = {
            "SAQUE", "FOREHAND", "BACKHAND",
            "SPL", "SSL", "SKK", "SMA", "FHD", "FHS", "FHV",
            "BHD", "BHS", "BHV", "AMO", "LOB", "RUI"
    };
    private static final String[] NOMES_GOLPES = {
            "Saque · qualquer tipo", "Forehand · qualquer tipo", "Backhand · qualquer tipo",
            "Saque plano", "Saque slice", "Saque kick", "Smash",
            "Forehand drive", "Forehand slice", "Forehand voleio",
            "Backhand drive", "Backhand slice", "Backhand voleio",
            "Amortida", "Lob", "Ruído / outro"
    };
    private static final String[] DIFICULDADES = {"Iniciante", "Intermediário", "Avançado"};

    private EditText edtNome;
    private EditText edtDescricao;
    private EditText edtDuracao;
    private Spinner spnDificuldade;
    private Spinner spnGolpe;
    private TextView txtContador;
    private TextView txtSequencia;
    private Button btnAdicionar;
    private Button btnRemover;
    private Button btnSalvar;
    private SwitchCompat switchUsarCamera;

    private final ArrayList<String> sequencia = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AppDatabase db;
    private int usuarioId = -1;
    private String desafioId;
    private long criadoEm = 0L;
    private boolean carregando = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_criar_desafio);

        db = DatabaseProvider.getInstance(getApplicationContext());
        usuarioId = lerUsuarioId();
        inicializarViews();
        configurarSpinners();
        configurarAcoes();

        desafioId = getIntent().getStringExtra("desafio_id");
        if (!TextUtils.isEmpty(desafioId)) {
            setTitle("Editar desafio");
            ((TextView) findViewById(R.id.txtTituloCriarDesafio)).setText("Editar desafio");
            btnSalvar.setText("Salvar alterações");
            carregarDesafio();
        } else {
            edtDuracao.setText("10");
            atualizarVisualizacaoSequencia();
        }

        if (usuarioId <= 0) {
            Toast.makeText(this, "Não foi possível identificar o usuário. Entre novamente.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void inicializarViews() {
        edtNome = findViewById(R.id.edtNomeDesafio);
        edtDescricao = findViewById(R.id.edtDescricaoDesafio);
        edtDuracao = findViewById(R.id.edtDuracaoDesafio);
        spnDificuldade = findViewById(R.id.spnDificuldadeDesafio);
        spnGolpe = findViewById(R.id.spnGolpeDesafio);
        txtContador = findViewById(R.id.txtContadorEtapasDesafio);
        txtSequencia = findViewById(R.id.txtSequenciaDesafio);
        btnAdicionar = findViewById(R.id.btnAdicionarEtapaDesafio);
        btnRemover = findViewById(R.id.btnRemoverEtapaDesafio);
        btnSalvar = findViewById(R.id.btnSalvarDesafio);
        switchUsarCamera = findViewById(R.id.switchUsarCameraDesafio);
    }

    private void configurarSpinners() {
        // Não depender da cor de texto herdada do tema Android: a tela usa fundo escuro.
        spnDificuldade.setAdapter(criarAdapterVisivel(DIFICULDADES));
        spnGolpe.setAdapter(criarAdapterVisivel(NOMES_GOLPES));
        spnDificuldade.setPopupBackgroundDrawable(new ColorDrawable(Color.rgb(21, 27, 34)));
        spnGolpe.setPopupBackgroundDrawable(new ColorDrawable(Color.rgb(21, 27, 34)));
    }

    private ArrayAdapter<String> criarAdapterVisivel(String[] valores) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, Arrays.asList(valores)) {
            private TextView criarTexto(int position, boolean dropdown) {
                TextView texto = new TextView(CriarDesafioActivity.this);
                texto.setText(getItem(position));
                texto.setTextColor(Color.WHITE);
                texto.setTextSize(14);
                texto.setGravity(Gravity.CENTER_VERTICAL);
                int horizontal = (int) (14 * getResources().getDisplayMetrics().density);
                int vertical = (int) (10 * getResources().getDisplayMetrics().density);
                texto.setPadding(horizontal, vertical, horizontal, vertical);
                texto.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
                texto.setBackgroundColor(dropdown ? Color.rgb(21, 27, 34) : Color.TRANSPARENT);
                return texto;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return criarTexto(position, false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return criarTexto(position, true);
            }
        };
    }

    private void configurarAcoes() {
        btnAdicionar.setOnClickListener(v -> adicionarEtapa());
        btnRemover.setOnClickListener(v -> removerUltimaEtapa());
        btnSalvar.setOnClickListener(v -> salvarDesafio());
        findViewById(R.id.btnPresetRitmo).setOnClickListener(v -> aplicarPreset("RITMO"));
        findViewById(R.id.btnPresetRali).setOnClickListener(v -> aplicarPreset("RALI"));
        findViewById(R.id.btnPresetSaque).setOnClickListener(v -> aplicarPreset("SAQUE"));
        findViewById(R.id.btnCancelarDesafio).setOnClickListener(v -> finish());
    }

    private void aplicarPreset(String tipo) {
        if (carregando) return;
        sequencia.clear();
        if ("RITMO".equals(tipo)) {
            sequencia.addAll(Arrays.asList("SAQUE", "FOREHAND", "BACKHAND", "FOREHAND", "BACKHAND", "SAQUE"));
            Toast.makeText(this, "⚡ Preset Ritmo explosivo aplicado", Toast.LENGTH_SHORT).show();
        } else if ("RALI".equals(tipo)) {
            for (int i = 0; i < 4; i++) {
                sequencia.add("FOREHAND");
                sequencia.add("BACKHAND");
            }
            Toast.makeText(this, "🔥 Preset Rali alternado aplicado", Toast.LENGTH_SHORT).show();
        } else {
            for (int i = 0; i < 5; i++) sequencia.add("SAQUE");
            Toast.makeText(this, "🎯 Preset Rei do saque aplicado", Toast.LENGTH_SHORT).show();
        }
        atualizarVisualizacaoSequencia();
    }

    private void adicionarEtapa() {
        if (carregando) return;
        if (sequencia.size() >= MAX_ETAPAS) {
            Toast.makeText(this, "O limite é de " + MAX_ETAPAS + " golpes por desafio.", Toast.LENGTH_SHORT).show();
            return;
        }
        int posicao = spnGolpe.getSelectedItemPosition();
        if (posicao < 0 || posicao >= GOLPES.length) return;
        sequencia.add(GOLPES[posicao]);
        atualizarVisualizacaoSequencia();
    }

    private void removerUltimaEtapa() {
        if (sequencia.isEmpty()) {
            Toast.makeText(this, "Não há golpes para remover.", Toast.LENGTH_SHORT).show();
            return;
        }
        sequencia.remove(sequencia.size() - 1);
        atualizarVisualizacaoSequencia();
    }

    private void atualizarVisualizacaoSequencia() {
        txtContador.setText(sequencia.size() + "/" + MAX_ETAPAS + " golpes configurados");
        btnRemover.setEnabled(!sequencia.isEmpty());
        btnSalvar.setEnabled(!carregando);
        if (sequencia.isEmpty()) {
            txtSequencia.setText("Sua sequência aparecerá aqui.");
            return;
        }

        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < sequencia.size(); i++) {
            if (i > 0) texto.append("\n");
            texto.append(i + 1).append(". ").append(nomeAmigavel(sequencia.get(i)));
        }
        txtSequencia.setText(texto.toString());
    }

    private String nomeAmigavel(String golpe) {
        return ClasseGolpeRaquete.nomeExibicao(golpe);
    }

    private void carregarDesafio() {
        carregando = true;
        btnSalvar.setEnabled(false);
        btnAdicionar.setEnabled(false);
        btnRemover.setEnabled(false);
        executor.execute(() -> {
            final DesafioEntity desafio;
            try {
                desafio = db.desafioDAO().buscarPorId(desafioId, usuarioId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    carregando = false;
                    btnSalvar.setEnabled(true);
                    btnAdicionar.setEnabled(true);
                    Toast.makeText(this, "Não foi possível carregar o desafio para edição.", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            runOnUiThread(() -> {
                carregando = false;
                btnSalvar.setEnabled(true);
                btnAdicionar.setEnabled(true);
                if (isFinishing()) return;
                if (desafio == null) {
                    Toast.makeText(this, "Desafio não encontrado para este usuário.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                edtNome.setText(desafio.nome == null ? "" : desafio.nome);
                edtDescricao.setText(desafio.descricao == null ? "" : desafio.descricao);
                edtDuracao.setText(String.valueOf(desafio.duracaoMinutos));
                criadoEm = desafio.criadoEm;
                switchUsarCamera.setChecked(desafio.usaCamera);
                if (desafio.dificuldade != null) {
                    for (int i = 0; i < DIFICULDADES.length; i++) {
                        if (DIFICULDADES[i].equalsIgnoreCase(desafio.dificuldade)) {
                            spnDificuldade.setSelection(i);
                            break;
                        }
                    }
                }
                sequencia.clear();
                if (desafio.sequenciaGolpes != null) {
                    for (String golpe : desafio.sequenciaGolpes.split(",")) {
                        String normalizado = normalizarGolpe(golpe);
                        if (normalizado != null && sequencia.size() < MAX_ETAPAS) {
                            sequencia.add(normalizado);
                        }
                    }
                }
                atualizarVisualizacaoSequencia();
            });
        });
    }

    private void salvarDesafio() {
        if (carregando) return;

        String nome = edtNome.getText().toString().trim();
        String descricao = edtDescricao.getText().toString().trim();
        String duracaoTexto = edtDuracao.getText().toString().trim();

        if (nome.isEmpty()) {
            edtNome.setError("Informe um nome para o desafio.");
            edtNome.requestFocus();
            return;
        }
        if (duracaoTexto.isEmpty()) {
            edtDuracao.setError("Informe a duração máxima.");
            edtDuracao.requestFocus();
            return;
        }

        final int duracao;
        try {
            duracao = Integer.parseInt(duracaoTexto);
        } catch (NumberFormatException e) {
            edtDuracao.setError("Informe uma duração válida.");
            return;
        }
        if (duracao < 1 || duracao > DURACAO_MAXIMA_MINUTOS) {
            edtDuracao.setError("A duração deve ser entre 1 e " + DURACAO_MAXIMA_MINUTOS + " minutos.");
            return;
        }
        if (sequencia.isEmpty()) {
            Toast.makeText(this, "Adicione pelo menos um golpe à sequência.", Toast.LENGTH_LONG).show();
            return;
        }
        if (usuarioId <= 0) {
            Toast.makeText(this, "Sessão de usuário inválida. Entre novamente.", Toast.LENGTH_LONG).show();
            return;
        }

        String dificuldade = String.valueOf(spnDificuldade.getSelectedItem());
        String sequenciaSalva = TextUtils.join(",", sequencia);
        String id = TextUtils.isEmpty(desafioId) ? UUID.randomUUID().toString() : desafioId;
        long agora = System.currentTimeMillis();
        long criado = criadoEm > 0 ? criadoEm : agora;

        DesafioEntity desafio = new DesafioEntity(
                id,
                usuarioId,
                nome,
                descricao,
                duracao,
                dificuldade,
                "PERSONALIZADO",
                sequenciaSalva,
                criado,
                agora,
                0f,
                0,
                switchUsarCamera.isChecked()
        );

        btnSalvar.setEnabled(false);
        executor.execute(() -> {
            try {
                db.desafioDAO().salvar(desafio);
                SyncManager.sincronizarEmSegundoPlano(this, usuarioId);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, "Desafio salvo localmente. Quando houver conexao, sera sincronizado com sua conta.", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSalvar.setEnabled(true);
                    Toast.makeText(this, "Não foi possível salvar o desafio. Verifique o banco de dados.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String normalizarGolpe(String valor) {
        return ClasseGolpeRaquete.normalizarToken(valor);
    }

    private int lerUsuarioId() {
        return com.example.smarttennisapp.util.SessaoUsuario.obterId(this);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
