package com.example.smarttennisapp.jogo;

import java.text.Normalizer;
import java.util.Locale;

/** Classes de golpe aceitas pelo aplicativo: saque, forehand, backhand e ruído/outro. */
public final class ClasseGolpeRaquete {
    public static final String INDEFINIDO = "?";
    public static final String NAO_IDENTIFICADO = "NAO_IDENTIFICADO";

    /** Códigos curtos internos usados pelo app e enviados ao backend. */
    public static final String[] CODIGOS = {
            "SAQ", "FHD", "FHS", "BHD", "BHS", "RUI"
    };
    public static final String[] NOMES = {
            "Saque / smash", "Forehand drive", "Forehand slice", "Backhand drive",
            "Backhand slice", "Ruído / outro"
    };
    /** Família usada para contadores e para metas genéricas de treino. */
    public static final String[] FAMILIAS = {
            "SAQUE/SMASH", "FOREHAND", "FOREHAND", "BACKHAND", "BACKHAND", "OUTRO"
    };

    private ClasseGolpeRaquete() { }

    /**
     * Converte somente as classes atualmente aceitas pelo aplicativo.
     * Categorias removidas são rejeitadas e não entram na pontuação nem no histórico novo.
     */
    public static String normalizarToken(String valor) {
        if (valor == null) return null;
        String chave = chave(valor);
        if (chave.isEmpty()) return null;

        // Metas genéricas continuam aceitas em treinos antigos e novos.
        if (chave.equals("SAQUE") || chave.equals("FOREHAND") || chave.equals("BACKHAND")) return chave;
        if (chave.equals("FH") || chave.equals("F")) return "FOREHAND";
        if (chave.equals("BH") || chave.equals("B")) return "BACKHAND";

        // Classes que existem no detector atual.
        for (int i = 0; i < CODIGOS.length; i++) {
            if (chave.equals(CODIGOS[i]) || chave.equals(chave(NOMES[i]))
                    || chave.equals(chave(nomeFirmware(i)))) {
                return CODIGOS[i];
            }
        }

        // A classe SAQUE/SMASH é indivisível no IMU atual.
        if (chave.equals("SAQUE SMASH") || chave.equals("SMASH")
                || chave.equals("SAQUE PLANO") || chave.equals("SAQUE SLICE")
                || chave.equals("SAQUE KICK") || chave.equals("SPL")
                || chave.equals("SSL") || chave.equals("SKK") || chave.equals("SMA")) {
            return "SAQ";
        }

        if (chave.equals("FOREHAND DRIVE")) return "FHD";
        if (chave.equals("FOREHAND SLICE")) return "FHS";
        if (chave.equals("BACKHAND DRIVE")) return "BHD";
        if (chave.equals("BACKHAND SLICE")) return "BHS";

        if (chave.equals("RUIDO") || chave.equals("OUTRO") || chave.equals("RUIDO OUTRO")) return "RUI";

        // AMO/LOB foram removidos pelo firmware e não devem ser apresentados como metas válidas.
        return null;
    }

    public static String familia(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        if (token == null) return INDEFINIDO;
        if (token.equals("SAQUE") || token.equals("FOREHAND") || token.equals("BACKHAND")) return token;
        for (int i = 0; i < CODIGOS.length; i++) {
            if (CODIGOS[i].equals(token)) return FAMILIAS[i];
        }
        return INDEFINIDO;
    }

    public static String nomeExibicao(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        if (token == null) return "Não identificado";
        switch (token) {
            case "SAQUE": return "Saque / smash · qualquer ocorrência";
            case "FOREHAND": return "Forehand · drive ou slice";
            case "BACKHAND": return "Backhand · drive ou slice";
            default:
                for (int i = 0; i < CODIGOS.length; i++) {
                    if (CODIGOS[i].equals(token)) return NOMES[i];
                }
                return "Não identificado";
        }
    }

    public static String nomePorCodigo(String codigo) {
        if (NAO_IDENTIFICADO.equals(codigo) || INDEFINIDO.equals(codigo)) return "Não identificado";
        return nomeExibicao(codigo);
    }

    /** Compara metas específicas ou genéricas com a classe efetivamente recebida. */
    public static boolean corresponde(String esperado, String codigoRecebido) {
        String alvo = normalizarToken(esperado);
        String recebido = normalizarToken(codigoRecebido);
        if (alvo == null || recebido == null) return false;

        if (alvo.equals("SAQUE")) return recebido.equals("SAQ") || recebido.equals("SAQUE");
        if (alvo.equals("FOREHAND")) return recebido.equals("FHD") || recebido.equals("FHS") || recebido.equals("FOREHAND");
        if (alvo.equals("BACKHAND")) return recebido.equals("BHD") || recebido.equals("BHS") || recebido.equals("BACKHAND");
        return alvo.equals(recebido);
    }

    public static boolean ehClasseDeGolpe(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        return token != null && !token.equals("SAQUE") && !token.equals("FOREHAND") && !token.equals("BACKHAND");
    }

    /** Ruído/outro é identificável, mas não pode pontuar como golpe válido. */
    public static boolean ehGolpeValidoParaPontuacao(String codigo) {
        String token = normalizarToken(codigo);
        return token != null && !token.equals("RUI");
    }

    private static String nomeFirmware(int i) {
        switch (CODIGOS[i]) {
            case "SAQ": return "SAQUE/SMASH";
            case "FHD": return "FH DRIVE";
            case "FHS": return "FH SLICE";
            case "BHD": return "BH DRIVE";
            case "BHS": return "BH SLICE";
            case "RUI": return "RUIDO/OUTRO";
            default: return NOMES[i].toUpperCase(Locale.ROOT);
        }
    }

    private static String chave(String valor) {
        String semAcentos = Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcentos.toUpperCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s*/\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
