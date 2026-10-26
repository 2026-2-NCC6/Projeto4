package com.example.smarttennisapp.jogo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser compatível com as linhas reais de classificação do detector_golpes_v2.ino. */
public final class ParserRaquete {
    /** O firmware coleta 500 Hz durante cerca de 0,5 s após o gatilho; inclui latência BT. */
    public static final long ATRASO_FIRMWARE_MS = 800;

    private static final Pattern RECONHECIDO = Pattern.compile(
            ">>\\s*(SAQUE\\s+PLANO|SAQUE\\s+SLICE|SAQUE\\s+KICK|SMASH|" +
            "FH\\s+DRIVE|FH\\s+SLICE|FH\\s+VOLEIO|BH\\s+DRIVE|BH\\s+SLICE|BH\\s+VOLEIO|" +
            "AMORTIDA|LOB|RUIDO\\s*/\\s*OUTRO|SAQUE|FOREHAND|BACKHAND)\\s+(\\d{1,3})\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCONHECIDO = Pattern.compile("(?:\\?{2,}\\s+nao identificado|nao identificado)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VELOCIDADE_CABECA = Pattern.compile("~\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*km\\s*/?\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACELERACAO = Pattern.compile("\\ba\\s*=\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    /** Compatibilidade com firmware antigo, no qual w= representava velocidade angular. */
    private static final Pattern VELOCIDADE_ANGULAR_ANTIGA = Pattern.compile("\\bw\\s*=\\s*([+-]?\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE);

    private ParserRaquete() { }

    /** Retorna um evento apenas para linhas de golpe reconhecido ou rejeitado pelo ESP32. */
    public static SwingEvento parse(String linha, long agoraMs) {
        if (linha == null || linha.isEmpty()) return null;
        Matcher m = RECONHECIDO.matcher(linha);
        if (m.find()) {
            try {
                String token = ClasseGolpeRaquete.normalizarToken(m.group(1));
                if (token == null) return null;
                int confianca = Integer.parseInt(m.group(2));
                double velocidade = extrair(VELOCIDADE_CABECA, linha, 0.0);
                double aceleracao = extrair(ACELERACAO, linha, 0.0);
                // A versão v2 não envia dps na linha reconhecida; esse campo fica zero.
                double angularAntiga = velocidade > 0 ? 0.0 : extrair(VELOCIDADE_ANGULAR_ANTIGA, linha, 0.0);
                return new SwingEvento(
                        ClasseGolpeRaquete.familia(token), token,
                        ClasseGolpeRaquete.nomePorCodigo(token), confianca,
                        aceleracao, angularAntiga, velocidade,
                        agoraMs, agoraMs - ATRASO_FIRMWARE_MS);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (DESCONHECIDO.matcher(linha).find()) {
            double aceleracao = extrair(ACELERACAO, linha, 0.0);
            double velocidade = extrair(VELOCIDADE_CABECA, linha, 0.0);
            return new SwingEvento(SwingEvento.INDEFINIDO, SwingEvento.INDEFINIDO,
                    "Não identificado", 0, aceleracao, 0.0, velocidade,
                    agoraMs, agoraMs - ATRASO_FIRMWARE_MS);
        }
        return null;
    }

    private static double extrair(Pattern pattern, String texto, double padrao) {
        Matcher m = pattern.matcher(texto);
        if (!m.find()) return padrao;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return padrao;
        }
    }
}
