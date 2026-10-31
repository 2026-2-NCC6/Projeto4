package com.example.smarttennisapp.jogo;

/** Evento de golpe recebido do ESP32, com classe exata, nome e métricas do movimento. */
public final class SwingEvento {
    public static final String INDEFINIDO = "?";

    /** Família: SAQUE/SMASH, FOREHAND, BACKHAND, VOLEIO, OUTRO ou "?". */
    public final String golpe;
    /** Código interno: SAQ, FHD, FHS, BHD, BHS, VOL, RUI ou "?". */
    public final String codigoClasse;
    public final String nomeClasse;
    public final int confiancaPct;
    public final double aceleracaoG;
    /** Mantido para compatibilidade com versões anteriores do firmware. */
    public final double velAngularDps;
    /** Velocidade estimada da cabeça da raquete enviada pelo detector, em km/h. */
    public final double velRaqueteKmh;
    public final long chegadaMs;
    public final long contatoMs;

    /** Construtor legado mantido para integrações locais anteriores. */
    public SwingEvento(String golpe, int confiancaPct, double aceleracaoG, double velAngularDps,
                       long chegadaMs, long contatoMs) {
        this(golpe, golpe, golpe, confiancaPct, aceleracaoG, velAngularDps, 0.0, chegadaMs, contatoMs);
    }

    public SwingEvento(String golpe, String codigoClasse, String nomeClasse, int confiancaPct,
                       double aceleracaoG, double velAngularDps, double velRaqueteKmh,
                       long chegadaMs, long contatoMs) {
        this.golpe = golpe == null ? INDEFINIDO : golpe;
        this.codigoClasse = codigoClasse == null ? INDEFINIDO : codigoClasse;
        this.nomeClasse = nomeClasse == null ? "Não identificado" : nomeClasse;
        this.confiancaPct = confiancaPct;
        this.aceleracaoG = aceleracaoG;
        this.velAngularDps = velAngularDps;
        this.velRaqueteKmh = velRaqueteKmh;
        this.chegadaMs = chegadaMs;
        this.contatoMs = contatoMs;
    }

    public boolean reconhecido() {
        return ClasseGolpeRaquete.ehGolpeValidoParaPontuacao(codigoClasse);
    }

    public boolean temClasseIdentificada() {
        return !INDEFINIDO.equals(codigoClasse);
    }
}
