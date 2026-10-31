package com.example.smarttennisapp.jogo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser das linhas de classificação emitidas pelo detector_golpes v2 (7 classes). */
public final class ParserRaquete {
    /** Inclui a janela de captura do firmware e a latência normal do Bluetooth SPP. */
    public static final long ATRASO_FIRMWARE_MS = 800;

    private static final Pattern RECONHECIDO = Pattern.compile(
            ">>\\s*(SAQUE\\s*/\\s*SMASH|SAQUE\\s+SMASH|SAQUE\\s+PLANO|" +
            "SAQUE\\s+SLICE|SAQUE\\s+KICK|SMASH|SAQUE|" +
            "FH\\s+DRIVE|FH\\s+SLICE|FH\\s+VOLEIO|" +
            "BH\\s+DRIVE|BH\\s+SLICE|BH\\s+VOLEIO|VOLEIO|" +
            "RUIDO\\s*/\\s*OUTRO|FOREHAND|BACKHAND)\\s+(\\d{1,3})\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCONHECIDO = Pattern.compile(
            "(?:\\?{2,}\\s+nao identificado|nao identificado)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VELOCIDADE_CABECA = Pattern.compile(
            "~\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*km\\s*/?\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACELERACAO = Pattern.compile(
            "\\ba\\s*=\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    /** Compatibilidade com firmware antigo, no qual w= representava velocidade angular. */
    private static final Pattern VELOCIDADE_ANGULAR_ANTIGA = Pattern.compile(
            "\\bw\\s*=\\s*([+-]?\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE);

    private ParserRaquete() { }

    /** Retorna evento somente para uma classe emitida ou uma rejeição explícita do classificador. */
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
                // O detector atual envia km/h e aceleração, mas não envia velocidade angular.
                double angularAntiga = velocidade > 0 ? 0.0
                        : extrair(VELOCIDADE_ANGULAR_ANTIGA, linha, 0.0);
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
