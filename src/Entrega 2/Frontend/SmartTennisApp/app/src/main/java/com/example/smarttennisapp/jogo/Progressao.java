package com.example.smarttennisapp.jogo;

/** XP e nível: o nível N exige 150 * (N-1)^2 de XP no total. */
public final class Progressao {
    private Progressao() { }

    public static int nivel(long xp) {
        return (int) Math.floor(Math.sqrt(Math.max(0, xp) / 150.0)) + 1;
    }

    public static long xpInicioDoNivel(int nivel) {
        long n = Math.max(1, nivel) - 1;
        return 150L * n * n;
    }

    /** Fração (0-1) já percorrida dentro do nível atual. */
    public static double fracaoNivel(long xp) {
        int n = nivel(xp);
        long ini = xpInicioDoNivel(n), fim = xpInicioDoNivel(n + 1);
        return Math.min(1.0, Math.max(0.0, (double) (xp - ini) / (double) (fim - ini)));
    }
}
