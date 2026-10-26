package com.example.smarttennisapp.jogo;

/** Evento de golpe emitido pelo ESP32, com classe detalhada e família compatível com o app antigo. */
public final class SwingEvento {
    public static final String INDEFINIDO = "?";

    /** Família geral: SAQUE, FOREHAND, BACKHAND, SMASH, AMORTIDA, LOB, OUTRO ou "?". */
    public final String golpe;
    /** Código exato do firmware: SPL, SSL, SKK, SMA, FHD, FHS, FHV, BHD, BHS, BHV, AMO, LOB, RUI ou "?". */
    public final String codigoClasse;
    public final String nomeClasse;
    public final int confiancaPct;
    public final double aceleracaoG;
    /** Mantido para compatibilidade; o detector v2 não envia dps na linha de classificação. */
    public final double velAngularDps;
    /** Velocidade estimada da cabeça da raquete emitida pelo detector v2, em km/h. */
    public final double velRaqueteKmh;
    public final long chegadaMs;
    public final long contatoMs;

    /** Construtor legado mantido para não quebrar outras integrações locais. */
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
