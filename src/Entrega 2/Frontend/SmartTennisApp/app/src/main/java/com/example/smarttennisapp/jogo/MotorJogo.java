package com.example.smarttennisapp.jogo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Regras dos jogos "Rali de Combo" e "Chefão" para treino contra máquina de bolas + parede.
 *
 * Sinais usados:
 *  - Raquete (ESP32): classe do golpe, confiança e velocidade estimada da cabeça da raquete (km/h). Chegam ~0,8 s depois do golpe.
 *  - Câmera (PC): voo da bola e ponto de impacto na parede (zona). Chega ~1-3 s depois do golpe.
 * O golpe e a bola são ligados pelo TEMPO (janela em torno do voo), sem depender de relógios iguais:
 * quem converte o relógio do PC para o do celular é o chamador.
 *
 * Importante: a câmera pode falhar em ver a bola. Por isso "sem leitura" NÃO tira vida nem
 * penaliza além de zerar o combo; só uma bola vista que não chegou à parede conta como erro.
 *
 * Não é thread-safe: chame sempre da mesma thread (a da interface).
 */
public final class MotorJogo {

    public enum Modo { RALI_COMBO, CHEFAO }

    public enum Resultado {
        /** Bola chegou à parede (ou, sem parede configurada, a câmera viu a bola; sem câmera, golpe claro). */
        VALIDADO,
        /** A câmera viu a bola, mas ela não chegou à parede. */
        BOLA_SEM_PAREDE,
        /** A câmera não leu a bola (ou foi um golpe no ar). */
        SEM_LEITURA
    }

    // ---- constantes de balanceamento (fáceis de ajustar) ----
    public static final long JANELA_PAREAMENTO_MS = 700;
    public static final long TIMEOUT_RESOLUCAO_MS = 3500;
    public static final int PONTOS_VALIDADO = 100;
    public static final int PONTOS_BOLA_SEM_PAREDE = 40;
    public static final int PONTOS_SEM_LEITURA = 20;
    public static final int BONUS_CRITICO = 50;
    public static final int BONUS_CHEFAO = 300;
    public static final double LIMITE_VEL_PLAUSIVEL_KMH = 200.0;
    public static final int CLAREZA_MINIMA_SEM_CAMERA = 60;
    public static final int DEVOLUCOES_PARA_TROCAR_ALVO = 6;
    public static final long CHEFAO_REGENERA_APOS_MS = 8000;
    public static final double CHEFAO_REGENERA_HP_POR_S = 10.0;

    public static final class Config {
        public Modo modo = Modo.RALI_COMBO;
        public long duracaoMs = 180_000;
        public boolean usaCamera = true;
        public boolean espelharLados = false;
        public int nivelJogador = 1;
        public long semente = System.nanoTime();
    }

    public static final class Resolucao {
        public final SwingEvento swing;
        public final Resultado resultado;
        public final int pontos;
        public final int combo;
        public final double multCombo;
        public final double fatorForca;
        public final boolean acertouAlvo;
        public final boolean critico;
        public final int zona;              // -1 se desconhecida
        public final double velBolaKmh;     // 0 se desconhecida
        public final boolean chefaoDerrotado;

        Resolucao(SwingEvento swing, Resultado resultado, int pontos, int combo, double multCombo, double fatorForca,
                  boolean acertouAlvo, boolean critico, int zona, double velBolaKmh, boolean chefaoDerrotado) {
            this.swing = swing;
            this.resultado = resultado;
            this.pontos = pontos;
            this.combo = combo;
            this.multCombo = multCombo;
            this.fatorForca = fatorForca;
            this.acertouAlvo = acertouAlvo;
            this.critico = critico;
            this.zona = zona;
            this.velBolaKmh = velBolaKmh;
            this.chefaoDerrotado = chefaoDerrotado;
        }
    }

    public static final class Resumo {
        public int pontos, golpes, validados, bolaSemParede, semLeitura, maiorCombo, alvosAcertados, criticos, chefoesDerrotados;
        public double velMaxBolaKmh, forcaRelativaMedia;
        public long xpGanho, xpTotal;
        public int nivelAntes, nivelDepois;
        public boolean novoRecordeVelocidade, novoRecordeForca, novoRecordeCombo;
        public long duracaoMs;
    }

    public interface Ouvinte {
        /** Feedback imediato: o ESP32 detectou um golpe (ainda sem o resultado da câmera). */
        void aoSwing(SwingEvento swing);
        /** Resultado do golpe depois de cruzar com a câmera (ou por timeout). */
        void aoResolver(Resolucao r);
        void aoNovoAlvo(int zona);
        void aoFim(Resumo resumo);
    }

    private static final class Pendente {
        final SwingEvento swing;
        final double fatorForca;
        boolean bolaVista;
        double velMax;
        Pendente(SwingEvento swing, double fatorForca) {
            this.swing = swing;
            this.fatorForca = fatorForca;
        }
    }

    private final Config cfg;
    private final Recordes recordes;
    private final Ouvinte ouvinte;
    private final Random rnd;
    private final List<Pendente> pendentes = new ArrayList<>();

    private boolean ativo = false;
    private long inicioMs, ultimoTickMs, ultimaValidacaoMs;
    private int pontos, combo, maiorCombo, golpes, validados, bolaSemParede, semLeitura, alvosAcertados, criticos;
    private double velMaxBola, somaFator;
    private int cols = 3, rows = 1, alvoZona = -1, validadosDesdeAlvo = 0;
    private boolean paredeConhecida = false;
    private final long xpInicial;
    private final double prVelInicial, prVelRaqueteInicial;
    private final int maiorComboInicial;

    // Chefão
    private int chefaoNumero = 1, chefoesDerrotados = 0;
    private double chefaoHp, chefaoHpMax;

    public MotorJogo(Config cfg, Recordes recordes, Ouvinte ouvinte) {
        this.cfg = cfg;
        this.recordes = recordes;
        this.ouvinte = ouvinte;
        this.rnd = new Random(cfg.semente);
        this.xpInicial = recordes.xp;
        this.prVelInicial = recordes.prVelBolaKmh;
        this.prVelRaqueteInicial = recordes.prVelRaqueteKmh;
        this.maiorComboInicial = recordes.maiorCombo;
    }

    // ------------------------------------------------------------------ ciclo de vida
    public void iniciar(long agoraMs) {
        ativo = true;
        inicioMs = agoraMs;
        ultimoTickMs = agoraMs;
        ultimaValidacaoMs = agoraMs;
        chefaoHpMax = hpDoChefao(1);
        chefaoHp = chefaoHpMax;
        escolherNovoAlvo();
    }

    public boolean ativo() { return ativo; }

    public void encerrar(long agoraMs) {
        if (!ativo) return;
        for (Pendente p : new ArrayList<>(pendentes)) {
            resolver(p, p.bolaVista ? Resultado.BOLA_SEM_PAREDE : Resultado.SEM_LEITURA, null, agoraMs);
        }
        ativo = false;

        Resumo r = new Resumo();
        r.pontos = pontos;
        r.golpes = golpes;
        r.validados = validados;
        r.bolaSemParede = bolaSemParede;
        r.semLeitura = semLeitura;
        r.maiorCombo = maiorCombo;
        r.alvosAcertados = alvosAcertados;
        r.criticos = criticos;
        r.chefoesDerrotados = chefoesDerrotados;
        r.velMaxBolaKmh = velMaxBola;
        r.forcaRelativaMedia = golpes > 0 ? somaFator / golpes : 0;
        r.duracaoMs = Math.min(cfg.duracaoMs, Math.max(0, agoraMs - inicioMs));
        r.xpGanho = pontos / 10 + validados * 2L + chefoesDerrotados * 50L;
        r.nivelAntes = Progressao.nivel(xpInicial);
        recordes.xp = xpInicial + r.xpGanho;
        recordes.sessoes++;
        recordes.chefoesDerrotados += chefoesDerrotados;
        if (maiorCombo > recordes.maiorCombo) recordes.maiorCombo = maiorCombo;
        r.xpTotal = recordes.xp;
        r.nivelDepois = Progressao.nivel(recordes.xp);
        r.novoRecordeVelocidade = recordes.prVelBolaKmh > prVelInicial && prVelInicial > 0;
        r.novoRecordeForca = recordes.prVelRaqueteKmh > prVelRaqueteInicial;
        r.novoRecordeCombo = maiorCombo > maiorComboInicial && maiorComboInicial > 0;
        ouvinte.aoFim(r);
    }

    /** Chame ~4x por segundo: resolve timeouts, regenera o chefão e encerra ao fim do tempo. */
    public void tick(long agoraMs) {
        if (!ativo) return;
        for (Pendente p : new ArrayList<>(pendentes)) {
            if (agoraMs - p.swing.chegadaMs >= TIMEOUT_RESOLUCAO_MS) {
                resolver(p, p.bolaVista ? Resultado.BOLA_SEM_PAREDE : Resultado.SEM_LEITURA, null, agoraMs);
            }
        }
        if (cfg.modo == Modo.CHEFAO && agoraMs - ultimaValidacaoMs > CHEFAO_REGENERA_APOS_MS) {
            double dt = Math.max(0, agoraMs - ultimoTickMs) / 1000.0;
            chefaoHp = Math.min(chefaoHpMax, chefaoHp + CHEFAO_REGENERA_HP_POR_S * dt);
        }
        ultimoTickMs = agoraMs;
        if (agoraMs - inicioMs >= cfg.duracaoMs) encerrar(agoraMs);
    }

    // ------------------------------------------------------------------ entradas
    public void aoReceberSwing(SwingEvento s, long agoraMs) {
        if (!ativo) return;
        double fator = 0.7;   // golpe não identificado vale menos
        if (s.reconhecido()) {
            if (s.velRaqueteKmh > 0) {
                double mediana = recordes.medianaVelRaquete(s.codigoClasse);
                fator = (mediana > 0) ? clamp(s.velRaqueteKmh / mediana, 0.7, 1.4) : 1.0;
                recordes.registraVelRaquete(s.codigoClasse, s.velRaqueteKmh);
            } else if (s.velAngularDps > 0) {
                // Compatibilidade com eventos provenientes de versões antigas do parser.
                double mediana = recordes.medianaForca(s.golpe);
                fator = (mediana > 0) ? clamp(s.velAngularDps / mediana, 0.7, 1.4) : 1.0;
                recordes.registraForca(s.golpe, s.velAngularDps);
            }
        }
        ouvinte.aoSwing(s);
        Pendente p = new Pendente(s, fator);
        if (!cfg.usaCamera) {
            // Sem câmera: vale como "golpe claro" (confiança alta do classificador).
            boolean claro = s.reconhecido() && s.confiancaPct >= CLAREZA_MINIMA_SEM_CAMERA;
            pendentes.add(p);
            resolver(p, claro ? Resultado.VALIDADO : Resultado.SEM_LEITURA, null, agoraMs);
        } else {
            pendentes.add(p);
        }
    }

    public void aoReceberBola(BolaEvento b, long agoraMs) {
        if (!ativo || !cfg.usaCamera) return;
        if (b.paredeConfigurada && b.cols > 0 && b.rows > 0) informarParede(b.cols, b.rows);
        Pendente par = null;
        for (Pendente p : pendentes) {
            long c = p.swing.contatoMs;
            if (c >= b.inicioMs - JANELA_PAREAMENTO_MS && c <= b.fimMs + JANELA_PAREAMENTO_MS) {
                par = p;
                break;
            }
        }
        if (par == null) return;   // bola da máquina sem golpe (deixou passar) ou fora da janela
        par.bolaVista = true;
        par.velMax = Math.max(par.velMax, b.velMaxKmh);
        boolean validada = !b.paredeConfigurada || b.acertouParede;
        if (validada) resolver(par, Resultado.VALIDADO, b, agoraMs);
    }

    /** A estação informou que a parede está configurada, com a grade cols×rows (alvos passam a existir). */
    public void informarParede(int novasCols, int novasRows) {
        boolean mudou = !paredeConhecida || novasCols != cols || novasRows != rows;
        paredeConhecida = true;
        if (mudou) {
            cols = Math.max(1, novasCols);
            rows = Math.max(1, novasRows);
            if (ativo) escolherNovoAlvo();
        }
    }

    public void informarSemParede() {
        paredeConhecida = false;
    }

    // ------------------------------------------------------------------ núcleo
    private void resolver(Pendente p, Resultado resultado, BolaEvento b, long agoraMs) {
        if (!pendentes.remove(p)) return;
        golpes++;
        somaFator += p.fatorForca;

        double velBola = b != null ? b.velMaxKmh : p.velMax;
        boolean velPlausivel = velBola > 0 && velBola <= LIMITE_VEL_PLAUSIVEL_KMH;
        int zona = -1;
        if (b != null && b.acertouParede && b.col >= 0 && b.row >= 0) {
            int col = cfg.espelharLados ? Math.max(0, b.cols - 1 - b.col) : b.col;
            zona = b.row * Math.max(1, b.cols) + col;
        }

        int base;
        switch (resultado) {
            case VALIDADO: base = PONTOS_VALIDADO; break;
            case BOLA_SEM_PAREDE: base = PONTOS_BOLA_SEM_PAREDE; break;
            default: base = PONTOS_SEM_LEITURA;
        }

        boolean acertouAlvo = false, critico = false, chefaoDerrotado = false;
        if (resultado == Resultado.VALIDADO) {
            combo++;
            maiorCombo = Math.max(maiorCombo, combo);
            validados++;
            validadosDesdeAlvo++;
            ultimaValidacaoMs = agoraMs;
            acertouAlvo = zona >= 0 && zona == alvoZona;
            if (velPlausivel) {
                if (recordes.prVelBolaKmh > 0 && velBola >= 0.9 * recordes.prVelBolaKmh) critico = true;
                if (velBola > recordes.prVelBolaKmh) recordes.prVelBolaKmh = velBola;
                velMaxBola = Math.max(velMaxBola, velBola);
            }
        } else {
            combo = 0;
            if (resultado == Resultado.BOLA_SEM_PAREDE) bolaSemParede++; else semLeitura++;
        }

        double mult = multCombo(combo);
        int pts = (int) Math.round(base * p.fatorForca * mult * (acertouAlvo ? 2.0 : 1.0));
        if (critico) { pts += BONUS_CRITICO; criticos++; }

        if (cfg.modo == Modo.CHEFAO && pts > 0) {
            chefaoHp -= pts;
            if (chefaoHp <= 0) {
                chefaoDerrotado = true;
                chefoesDerrotados++;
                pts += BONUS_CHEFAO;
                chefaoNumero++;
                chefaoHpMax = hpDoChefao(chefaoNumero);
                chefaoHp = chefaoHpMax;
            }
        }
        pontos += pts;

        if (acertouAlvo) {
            alvosAcertados++;
        }
        Resolucao r = new Resolucao(p.swing, resultado, pts, combo, mult, p.fatorForca, acertouAlvo, critico, zona, velBola, chefaoDerrotado);
        ouvinte.aoResolver(r);
        if (acertouAlvo || validadosDesdeAlvo >= DEVOLUCOES_PARA_TROCAR_ALVO) escolherNovoAlvo();
    }

    private void escolherNovoAlvo() {
        int total = cols * rows;
        if (total <= 1) {
            alvoZona = 0;
        } else {
            int novo;
            do { novo = rnd.nextInt(total); } while (novo == alvoZona);
            alvoZona = novo;
        }
        validadosDesdeAlvo = 0;
        ouvinte.aoNovoAlvo(alvoZona);
    }

    private double hpDoChefao(int numero) {
        double base = 400.0 + 100.0 * Math.max(1, cfg.nivelJogador);
        return Math.round(base * Math.pow(1.25, numero - 1));
    }

    // ------------------------------------------------------------------ utilidades / estado
    public static double multCombo(int combo) {
        return Math.min(4.0, 1.0 + 0.5 * Math.floor(Math.max(0, combo) / 3.0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String nomeZona(int zona, int cols, int rows) {
        if (zona < 0) return "-";
        if (rows == 1 && cols == 3) return new String[]{"ESQUERDA", "CENTRO", "DIREITA"}[Math.min(2, zona)];
        if (rows == 1) return "COLUNA " + (zona + 1);
        return "LINHA " + (zona / cols + 1) + " · COLUNA " + (zona % cols + 1);
    }

    public int pontos() { return pontos; }
    public int combo() { return combo; }
    public int maiorCombo() { return maiorCombo; }
    public double multComboAtual() { return multCombo(combo); }
    public int alvoZona() { return alvoZona; }
    /** Há alvos nesta sessão? (câmera em uso e parede configurada na estação) */
    public boolean alvoDisponivel() { return cfg.usaCamera && paredeConhecida; }
    public int cols() { return cols; }
    public int rows() { return rows; }
    public int pendentes() { return pendentes.size(); }
    public long tempoRestanteMs(long agoraMs) { return Math.max(0, cfg.duracaoMs - (agoraMs - inicioMs)); }
    public double chefaoHp() { return Math.max(0, chefaoHp); }
    public double chefaoHpMax() { return chefaoHpMax; }
    public int chefaoNumero() { return chefaoNumero; }
    public Modo modo() { return cfg.modo; }
}
