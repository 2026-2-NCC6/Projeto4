package com.example.smarttennisapp.jogo;

/** Um voo de bola medido pela câmera (estação no PC), com tempos no relógio do celular. */
public final class BolaEvento {
    public final long inicioMs;
    public final long fimMs;
    public final double velMaxKmh;
    public final double velMediaKmh;
    public final boolean paredeConfigurada;   // a estação tem a parede/grade configurada
    public final boolean acertouParede;       // houve impacto detectado dentro da parede
    public final int cols;                    // grade (0 se não configurada)
    public final int rows;
    public final int col;                     // célula do impacto (-1 se não houve)
    public final int row;

    public BolaEvento(long inicioMs, long fimMs, double velMaxKmh, double velMediaKmh,
                      boolean paredeConfigurada, boolean acertouParede, int cols, int rows, int col, int row) {
        this.inicioMs = inicioMs;
        this.fimMs = fimMs;
        this.velMaxKmh = velMaxKmh;
        this.velMediaKmh = velMediaKmh;
        this.paredeConfigurada = paredeConfigurada;
        this.acertouParede = acertouParede;
        this.cols = cols;
        this.rows = rows;
        this.col = col;
        this.row = row;
    }
}
