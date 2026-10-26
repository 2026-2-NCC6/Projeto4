package com.example.smarttennisapp.jogo;

import java.text.Normalizer;
import java.util.Locale;

/** Catálogo compartilhado das classes realmente emitidas pelo firmware do ESP32 v2. */
public final class ClasseGolpeRaquete {
    public static final String INDEFINIDO = "?";
    public static final String NAO_IDENTIFICADO = "NAO_IDENTIFICADO";

    public static final String[] CODIGOS = {
            "SPL", "SSL", "SKK", "SMA", "FHD", "FHS", "FHV",
            "BHD", "BHS", "BHV", "AMO", "LOB", "RUI"
    };
    public static final String[] NOMES = {
            "Saque plano", "Saque slice", "Saque kick", "Smash",
            "Forehand drive", "Forehand slice", "Forehand voleio",
            "Backhand drive", "Backhand slice", "Backhand voleio",
            "Amortida", "Lob", "Ruído / outro"
    };
    public static final String[] FAMILIAS = {
            "SAQUE", "SAQUE", "SAQUE", "SMASH", "FOREHAND", "FOREHAND", "FOREHAND",
            "BACKHAND", "BACKHAND", "BACKHAND", "AMORTIDA", "LOB", "OUTRO"
    };

    private ClasseGolpeRaquete() { }

    /** Normaliza as sequências novas e as antigas sem alterar os dados já gravados. */
    public static String normalizarToken(String valor) {
        if (valor == null) return null;
        String chave = chave(valor);
        if (chave.isEmpty()) return null;

        // Tokens genéricos antigos continuam significando qualquer variante da família.
        if (chave.equals("SAQUE") || chave.equals("FOREHAND") || chave.equals("BACKHAND")) return chave;
        if (chave.equals("FH") || chave.equals("F")) return "FOREHAND";
        if (chave.equals("BH") || chave.equals("B")) return "BACKHAND";

        for (int i = 0; i < CODIGOS.length; i++) {
            if (chave.equals(CODIGOS[i]) || chave.equals(chave(NOMES[i])) || chave.equals(chave(nomeFirmware(i)))) {
                return CODIGOS[i];
            }
        }

        if (chave.equals("RUIDO") || chave.equals("OUTRO") || chave.equals("RUIDO OUTRO")) return "RUI";
        if (chave.equals("FOREHAND DRIVE")) return "FHD";
        if (chave.equals("FOREHAND SLICE")) return "FHS";
        if (chave.equals("FOREHAND VOLEIO") || chave.equals("FOREHAND VOLLEY")) return "FHV";
        if (chave.equals("BACKHAND DRIVE")) return "BHD";
        if (chave.equals("BACKHAND SLICE")) return "BHS";
        if (chave.equals("BACKHAND VOLEIO") || chave.equals("BACKHAND VOLLEY")) return "BHV";
        return null;
    }

    public static String familia(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        if (token == null) return INDEFINIDO;
        if (token.equals("SAQUE") || token.equals("FOREHAND") || token.equals("BACKHAND")) return token;
        for (int i = 0; i < CODIGOS.length; i++) if (CODIGOS[i].equals(token)) return FAMILIAS[i];
        return INDEFINIDO;
    }

    public static String nomeExibicao(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        if (token == null) return "Não identificado";
        switch (token) {
            case "SAQUE": return "Saque · qualquer tipo";
            case "FOREHAND": return "Forehand · qualquer tipo";
            case "BACKHAND": return "Backhand · qualquer tipo";
            default:
                for (int i = 0; i < CODIGOS.length; i++) if (CODIGOS[i].equals(token)) return NOMES[i];
                return "Não identificado";
        }
    }

    public static String nomePorCodigo(String codigo) {
        if (NAO_IDENTIFICADO.equals(codigo) || INDEFINIDO.equals(codigo)) return "Não identificado";
        for (int i = 0; i < CODIGOS.length; i++) if (CODIGOS[i].equals(codigo)) return NOMES[i];
        return nomeExibicao(codigo);
    }

    public static boolean corresponde(String esperado, String codigoRecebido) {
        String alvo = normalizarToken(esperado);
        String recebido = normalizarToken(codigoRecebido);
        if (alvo == null || recebido == null) return false;
        if (alvo.equals("SAQUE") || alvo.equals("FOREHAND") || alvo.equals("BACKHAND")) {
            return alvo.equals(familia(recebido));
        }
        return alvo.equals(recebido);
    }

    public static boolean ehClasseDeGolpe(String codigoOuToken) {
        String token = normalizarToken(codigoOuToken);
        return token != null && !token.equals("SAQUE") && !token.equals("FOREHAND") && !token.equals("BACKHAND");
    }

    public static boolean ehGolpeValidoParaPontuacao(String codigo) {
        String token = normalizarToken(codigo);
        return token != null && !token.equals("RUI");
    }

    /** Rótulo textual do CLS[] do firmware, usado apenas como alias de leitura. */
    private static String nomeFirmware(int i) {
        switch (CODIGOS[i]) {
            case "FHD": return "FH DRIVE";
            case "FHS": return "FH SLICE";
            case "FHV": return "FH VOLEIO";
            case "BHD": return "BH DRIVE";
            case "BHS": return "BH SLICE";
            case "BHV": return "BH VOLEIO";
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
