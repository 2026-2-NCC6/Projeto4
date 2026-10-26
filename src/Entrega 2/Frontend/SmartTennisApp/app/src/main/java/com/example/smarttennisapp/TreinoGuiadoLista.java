package com.example.smarttennisapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smarttennisapp.backend.SyncManager;
import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.DesafioEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TreinoGuiadoLista extends BaseActivity {

    private EditText edtBuscarTreino;
    private RecyclerView recyclerTreinos;
    private TextView txtListaVazia;
    private View progressoTreinos;

    private Button btnFiltroTodos;
    private Button btnFiltroOffline;
    private ImageButton btnVoltarTreinos;
    private Button btnCriarDesafioLista;

    private TreinoAdapter adapter;

    private AppDatabase db;
    private int usuarioId = -1;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean recarregarAoRetornar = false;

    private final List<Treino> todosTreinos = new ArrayList<>();

    private String filtroOrigem = "TODOS";
    private String pesquisaAtual = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_treino_guiado_lista);

        View raiz = findViewById(R.id.main);
        if (raiz != null) {
            final int baseStart = raiz.getPaddingStart();
            final int baseTop = raiz.getPaddingTop();
            final int baseEnd = raiz.getPaddingEnd();
            final int baseBottom = raiz.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPaddingRelative(baseStart + bars.left, baseTop + bars.top,
                        baseEnd + bars.right, baseBottom + bars.bottom);
                return insets;
            });
        }

        db = DatabaseProvider.getInstance(getApplicationContext());
        usuarioId = lerUsuarioId();

        inicializarComponentes();
        configurarLista();
        carregarTreinosOffline();
        configurarFiltros();
        configurarBusca();
        configurarBotaoVoltar();
        btnCriarDesafioLista.setOnClickListener(v -> {
            recarregarAoRetornar = true;
            startActivity(new Intent(this, CriarDesafioActivity.class));
        });

        atualizarLista();
    }

    private void inicializarComponentes() {
        edtBuscarTreino = findViewById(R.id.edtBuscarTreino);
        recyclerTreinos = findViewById(R.id.recyclerTreinos);
        txtListaVazia = findViewById(R.id.txtListaVazia);
        progressoTreinos = findViewById(R.id.progressoTreinos);

        btnFiltroTodos = findViewById(R.id.btnFiltroTodos);
        btnFiltroOffline = findViewById(R.id.btnFiltroOffline);
        btnVoltarTreinos = findViewById(R.id.btnVoltarTreinos);
        btnCriarDesafioLista = findViewById(R.id.btnCriarDesafioLista);
    }

    private void configurarLista() {
        recyclerTreinos.setLayoutManager(
                new LinearLayoutManager(this)
        );

        adapter = new TreinoAdapter();
        recyclerTreinos.setAdapter(adapter);
    }

    // --------------------------------------------------
    // MODELO DE DADOS
    // --------------------------------------------------

    public static class Treino {

        private final String id;
        private final String nome;
        private final String descricao;
        private final int duracaoMinutos;
        private final String dificuldade;
        private final String categoria;
        private final String origem;
        private final String sequenciaGolpes;
        private final boolean personalizado;

        public Treino(
                String id,
                String nome,
                String descricao,
                int duracaoMinutos,
                String dificuldade,
                String categoria,
                String origem
        ) {
            this(id, nome, descricao, duracaoMinutos, dificuldade, categoria, origem, "", false);
        }

        public Treino(
                String id,
                String nome,
                String descricao,
                int duracaoMinutos,
                String dificuldade,
                String categoria,
                String origem,
                String sequenciaGolpes,
                boolean personalizado
        ) {
            this.id = id;
            this.nome = nome;
            this.descricao = descricao;
            this.duracaoMinutos = duracaoMinutos;
            this.dificuldade = dificuldade;
            this.categoria = categoria;
            this.origem = origem;
            this.sequenciaGolpes = sequenciaGolpes == null ? "" : sequenciaGolpes;
            this.personalizado = personalizado;
        }

        public String getId() {
            return id;
        }

        public String getNome() {
            return nome;
        }

        public String getDescricao() {
            return descricao;
        }

        public int getDuracaoMinutos() {
            return duracaoMinutos;
        }

        public String getDificuldade() {
            return dificuldade;
        }

        public String getCategoria() {
            return categoria;
        }

        public String getOrigem() {
            return origem;
        }

        public String getSequenciaGolpes() {
            return sequenciaGolpes;
        }

        public boolean isPersonalizado() {
            return personalizado;
        }

        public int getQuantidadeEtapas() {
            if (sequenciaGolpes.isEmpty()) return 0;
            return sequenciaGolpes.split(",").length;
        }
    }

    // --------------------------------------------------
    // TREINOS OFFLINE
    // --------------------------------------------------

    private void carregarTreinosOffline() {
        todosTreinos.clear();

        // Catálogo local: todas as missões funcionam sem internet e usam a raquete via Bluetooth.
        adicionarTreinoCatalogo("offline_001", "Consistência no Forehand",
                "Acerte cinco forehands na ordem sem quebrar seu combo. Cada golpe correto rende pontos.",
                10, "Iniciante", "FUNDAMENTOS", "FOREHAND,FOREHAND,FOREHAND,FOREHAND,FOREHAND");
        adicionarTreinoCatalogo("offline_002", "Precisão no Backhand",
                "Cinco backhands para treinar regularidade, precisão e controle do movimento.",
                12, "Intermediário", "PRECISÃO", "BACKHAND,BACKHAND,BACKHAND,BACKHAND,BACKHAND");
        adicionarTreinoCatalogo("offline_003", "Rei do Saque",
                "Complete cinco saques reconhecidos pelo ESP32 e conquiste bônus de conclusão.",
                10, "Intermediário", "SAQUE", "SAQUE,SAQUE,SAQUE,SAQUE,SAQUE");
        adicionarTreinoCatalogo("offline_004", "Fundamentos do Tênis",
                "A missão inicial: alterne saque, forehand e backhand na sequência indicada.",
                10, "Iniciante", "FUNDAMENTOS", "SAQUE,FOREHAND,BACKHAND,FOREHAND,BACKHAND,SAQUE");
        adicionarTreinoCatalogo("offline_005", "Escada de Combo",
                "A sequência fica mais longa a cada etapa. Mantenha a concentração para conquistar o Rank S.",
                12, "Avançado", "COMBO", "SAQUE,FOREHAND,BACKHAND,FOREHAND,FOREHAND,BACKHAND,SAQUE,FOREHAND,BACKHAND");
        adicionarTreinoCatalogo("offline_006", "Rali Alternado",
                "Alterne forehand e backhand dez vezes. O objetivo é consistência sem quebrar o ritmo.",
                15, "Intermediário", "RALI", "FOREHAND,BACKHAND,FOREHAND,BACKHAND,FOREHAND,BACKHAND,FOREHAND,BACKHAND,FOREHAND,BACKHAND");
        adicionarTreinoCatalogo("offline_007", "Saque + Primeiro Ataque",
                "Depois do saque, responda com forehand e backhand no padrão tático da missão.",
                12, "Intermediário", "TÁTICA", "SAQUE,FOREHAND,FOREHAND,BACKHAND,FOREHAND,SAQUE");
        adicionarTreinoCatalogo("offline_008", "Desafio Grand Slam",
                "Uma maratona de doze movimentos. Acerte todos para receber bônus de conclusão perfeita.",
                18, "Avançado", "MARATONA", "SAQUE,FOREHAND,BACKHAND,FOREHAND,BACKHAND,SAQUE,FOREHAND,BACKHAND,FOREHAND,SAQUE,BACKHAND,FOREHAND");
        adicionarTreinoCatalogo("offline_009", "Escada de Saques",
                "Alterne saque plano, slice e kick. A meta é reconhecer e executar cada variação na ordem.",
                12, "Avançado", "SAQUES VARIADOS", "SPL,SSL,SKK,SPL,SSL,SKK");
        adicionarTreinoCatalogo("offline_010", "Ataque e Jogo de Rede",
                "Combine smash, voleios e amortidas para treinar mudanças rápidas de intenção.",
                12, "Avançado", "REDE E ATAQUE", "SMA,FHV,BHV,AMO,FHV,BHV");
        adicionarTreinoCatalogo("offline_011", "Variações do Fundo",
                "Misture drive e slice nos dois lados para desenvolver variedade e leitura do movimento.",
                14, "Avançado", "VARIAÇÃO", "FHD,FHS,BHD,BHS,FHD,BHD");
        adicionarTreinoCatalogo("offline_012", "Controle de Altura",
                "Alterne lob e amortida para praticar trajetórias opostas e controle do toque.",
                10, "Intermediário", "CONTROLE", "LOB,AMO,LOB,AMO,LOB,AMO");

        if (usuarioId <= 0) {
            atualizarLista();
            return;
        }

        progressoTreinos.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            final List<DesafioEntity> desafios;
            try {
                SyncManager.sincronizarAgora(this, usuarioId);
                desafios = db.desafioDAO().listarPorUsuario(usuarioId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressoTreinos.setVisibility(View.GONE);
                    atualizarLista();
                    Toast.makeText(this, "Não foi possível carregar os desafios salvos.", Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                removerDesafiosPersonalizadosDaLista();
                for (DesafioEntity desafio : desafios) {
                    todosTreinos.add(new Treino(
                            desafio.id,
                            desafio.nome == null ? "Desafio sem nome" : desafio.nome,
                            desafio.descricao == null ? "" : desafio.descricao,
                            Math.max(1, desafio.duracaoMinutos),
                            desafio.dificuldade == null ? "Iniciante" : desafio.dificuldade,
                            "MEU DESAFIO",
                            "PERSONALIZADO",
                            desafio.sequenciaGolpes,
                            true
                    ));
                }
                progressoTreinos.setVisibility(View.GONE);
                atualizarLista();
            });
        });
    }

    private void adicionarTreinoCatalogo(String id, String nome, String descricao,
                                         int duracao, String dificuldade, String categoria,
                                         String sequencia) {
        todosTreinos.add(new Treino(id, nome, descricao, duracao, dificuldade,
                categoria, "OFFLINE", sequencia, false));
    }

    private void removerDesafiosPersonalizadosDaLista() {
        for (int i = todosTreinos.size() - 1; i >= 0; i--) {
            if (todosTreinos.get(i).isPersonalizado()) {
                todosTreinos.remove(i);
            }
        }
    }

    private int lerUsuarioId() {
        return com.example.smarttennisapp.util.SessaoUsuario.obterId(this);
    }

    // --------------------------------------------------
    // FILTROS DE ORIGEM
    // --------------------------------------------------

    private void configurarFiltros() {

        btnFiltroTodos.setOnClickListener(v -> {
            filtroOrigem = "TODOS";
            atualizarEstiloFiltros();
            atualizarLista();
        });

        btnFiltroOffline.setOnClickListener(v -> {
            filtroOrigem = "PERSONALIZADO";
            atualizarEstiloFiltros();
            atualizarLista();
        });


        atualizarEstiloFiltros();
    }

    private void atualizarEstiloFiltros() {

        estilizarFiltro(btnFiltroTodos, filtroOrigem.equals("TODOS"));
        estilizarFiltro(btnFiltroOffline, filtroOrigem.equals("PERSONALIZADO"));
    }

    private void estilizarFiltro(Button botao, boolean selecionado) {

        if (selecionado) {
            botao.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.rgb(53, 200, 120)
                    )
            );

            botao.setTextColor(
                    android.graphics.Color.rgb(8, 19, 12)
            );
        } else {
            botao.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.rgb(32, 40, 48)
                    )
            );

            botao.setTextColor(
                    android.graphics.Color.WHITE
            );
        }
    }

    // --------------------------------------------------
    // BUSCA
    // --------------------------------------------------

    private void configurarBusca() {

        edtBuscarTreino.addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {
                // Não é necessário executar nenhuma ação aqui.
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
                pesquisaAtual = s.toString()
                        .trim()
                        .toLowerCase(Locale.ROOT);

                atualizarLista();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // A lista já é atualizada em onTextChanged.
            }
        });
    }

    // --------------------------------------------------
    // ATUALIZAÇÃO DA LISTA
    // --------------------------------------------------

    private void atualizarLista() {

        List<Treino> treinosFiltrados = new ArrayList<>();

        for (Treino treino : todosTreinos) {

            boolean correspondeOrigem = filtroOrigem.equals("TODOS")
                    || (filtroOrigem.equals("PERSONALIZADO") && treino.isPersonalizado());

            String nome = treino.getNome().toLowerCase(Locale.ROOT);
            String descricao = treino.getDescricao().toLowerCase(Locale.ROOT);
            String dificuldade =
                    treino.getDificuldade().toLowerCase(Locale.ROOT);
            String categoria =
                    treino.getCategoria().toLowerCase(Locale.ROOT);

            boolean correspondeBusca =
                    pesquisaAtual.isEmpty()
                            || nome.contains(pesquisaAtual)
                            || descricao.contains(pesquisaAtual)
                            || dificuldade.contains(pesquisaAtual)
                            || categoria.contains(pesquisaAtual);

            if (correspondeOrigem && correspondeBusca) {
                treinosFiltrados.add(treino);
            }
        }

        adapter.atualizarTreinos(treinosFiltrados);

        boolean listaVazia = treinosFiltrados.isEmpty();

        txtListaVazia.setVisibility(
                listaVazia ? View.VISIBLE : View.GONE
        );

        recyclerTreinos.setVisibility(
                listaVazia ? View.GONE : View.VISIBLE
        );

        if (listaVazia) {
            txtListaVazia.setText(filtroOrigem.equals("PERSONALIZADO")
                    ? "Você ainda não criou desafios. Use um preset ou monte sua própria sequência."
                    : "Nenhum treino encontrado. Tente outra busca.");
        }
    }

    // --------------------------------------------------
    // ADAPTER DO RECYCLERVIEW
    // --------------------------------------------------

    private class TreinoAdapter
            extends RecyclerView.Adapter<TreinoAdapter.TreinoViewHolder> {

        private final List<Treino> lista = new ArrayList<>();

        void atualizarTreinos(List<Treino> novosTreinos) {
            lista.clear();
            lista.addAll(novosTreinos);
            notifyDataSetChanged();
        }

        @Override
        public TreinoViewHolder onCreateViewHolder(
                ViewGroup parent,
                int viewType
        ) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(
                            R.layout.item_treino_guiado,
                            parent,
                            false
                    );

            return new TreinoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(
                TreinoViewHolder holder,
                int position
        ) {
            Treino treino = lista.get(position);

            holder.txtNome.setText(treino.getNome());
            holder.txtDescricao.setText(treino.getDescricao());
            holder.txtDuracao.setText(
                    treino.getDuracaoMinutos() + " min"
            );
            holder.txtDificuldade.setText(treino.getDificuldade());
            holder.txtEtapas.setText("🎾 " + treino.getQuantidadeEtapas() + " etapas · IoT Bluetooth");
            holder.txtCategoria.setText(treino.getCategoria());
            holder.txtOrigem.setText(treino.isPersonalizado() ? "MEU DESAFIO" : treino.getOrigem());

            holder.acoesPersonalizadas.setVisibility(treino.isPersonalizado() ? View.VISIBLE : View.GONE);
            holder.btnEditar.setOnClickListener(v -> editarDesafio(treino));
            holder.btnExcluir.setOnClickListener(v -> confirmarExclusao(treino));

            int corDificuldade;

            switch (treino.getDificuldade().toLowerCase(Locale.ROOT)) {
                case "iniciante":
                    corDificuldade = android.graphics.Color.rgb(53, 200, 120);
                    break;

                case "intermediário":
                case "intermediario":
                    corDificuldade = android.graphics.Color.rgb(255, 193, 7);
                    break;

                case "avançado":
                case "avancado":
                    corDificuldade = android.graphics.Color.rgb(255, 105, 97);
                    break;

                default:
                    corDificuldade = android.graphics.Color.LTGRAY;
                    break;
            }

            holder.txtDificuldade.setTextColor(corDificuldade);

            holder.btnIniciar.setOnClickListener(v -> {
                abrirTreino(treino);
            });

            // Permite selecionar o cartão inteiro.
            holder.itemView.setOnClickListener(v -> {
                abrirTreino(treino);
            });
        }

        @Override
        public int getItemCount() {
            return lista.size();
        }

        class TreinoViewHolder extends RecyclerView.ViewHolder {

            TextView txtNome;
            TextView txtDescricao;
            TextView txtDuracao;
            TextView txtDificuldade;
            TextView txtEtapas;
            TextView txtCategoria;
            TextView txtOrigem;
            Button btnIniciar;
            View acoesPersonalizadas;
            Button btnEditar;
            Button btnExcluir;

            TreinoViewHolder(View itemView) {
                super(itemView);

                txtNome = itemView.findViewById(R.id.txtNomeTreino);
                txtDescricao = itemView.findViewById(R.id.txtDescricaoTreino);
                txtDuracao = itemView.findViewById(R.id.txtDuracaoTreino);
                txtDificuldade = itemView.findViewById(R.id.txtDificuldadeTreino);
                txtEtapas = itemView.findViewById(R.id.txtEtapasTreino);
                txtCategoria =
                        itemView.findViewById(R.id.txtCategoriaTreino);
                txtOrigem =
                        itemView.findViewById(R.id.txtOrigemTreino);
                btnIniciar = itemView.findViewById(R.id.btnIniciarTreinoGuiado);
                acoesPersonalizadas = itemView.findViewById(R.id.acoesDesafioPersonalizado);
                btnEditar = itemView.findViewById(R.id.btnEditarTreinoGuiado);
                btnExcluir = itemView.findViewById(R.id.btnExcluirTreinoGuiado);
            }
        }
    }

    // --------------------------------------------------
    // ABERTURA DA TELA DE EXECUÇÃO
    // --------------------------------------------------

    private void abrirTreino(Treino treino) {
        Intent intent = new Intent(this, com.example.smarttennisapp.Treino.class);

        intent.putExtra("treino_id", treino.getId());
        intent.putExtra("treino_nome", treino.getNome());
        intent.putExtra("treino_descricao", treino.getDescricao());
        intent.putExtra(
                "treino_duracao",
                treino.getDuracaoMinutos()
        );
        intent.putExtra("treino_dificuldade", treino.getDificuldade());
        intent.putExtra("treino_categoria", treino.getCategoria());
        intent.putExtra("treino_origem", treino.getOrigem());
        intent.putExtra("treino_sequencia", treino.getSequenciaGolpes());

        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(
                    this,
                    "Não foi possível abrir a tela de execução do desafio.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void editarDesafio(Treino treino) {
        if (!treino.isPersonalizado()) return;
        Intent intent = new Intent(this, CriarDesafioActivity.class);
        intent.putExtra("desafio_id", treino.getId());
        recarregarAoRetornar = true;
        startActivity(intent);
    }

    private void confirmarExclusao(Treino treino) {
        if (!treino.isPersonalizado()) return;
        new AlertDialog.Builder(this)
                .setTitle("Excluir desafio?")
                .setMessage("\"" + treino.getNome() + "\" será removido da lista. O histórico de execuções será mantido.")
                .setNegativeButton("Cancelar", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Excluir", (dialog, which) -> executor.execute(() -> {
                    int removidos = db.desafioDAO().excluir(treino.getId(), usuarioId);
                    if (removidos > 0) SyncManager.sincronizarAgora(this, usuarioId);
                    runOnUiThread(() -> {
                        if (removidos > 0) {
                            for (int i = todosTreinos.size() - 1; i >= 0; i--) {
                                Treino item = todosTreinos.get(i);
                                if (item.isPersonalizado() && item.getId().equals(treino.getId())) {
                                    todosTreinos.remove(i);
                                }
                            }
                            atualizarLista();
                            Toast.makeText(this, "Desafio excluído. O histórico foi preservado.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Não foi possível excluir este desafio.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }))
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (recarregarAoRetornar) {
            recarregarAoRetornar = false;
            carregarTreinosOffline();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    // --------------------------------------------------
    // VOLTAR
    // --------------------------------------------------

    private void configurarBotaoVoltar() {
        btnVoltarTreinos.setOnClickListener(v -> finish());
    }
}