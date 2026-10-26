package com.example.smarttennisapp.jogo;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Agrega contagens e métricas das classes de golpe para persistir sem perder compatibilidade de schema. */
public final class RegistroGolpesSessao {
    private static final class ClasseResumo {
        int quantidade;
        int confiancaN;
        double confiancaSoma;
        int velocidadeN;
        double velocidadeSoma;
        double velocidadeMax;
        int aceleracaoN;
        double aceleracaoSoma;
        double aceleracaoMax;
    }

    private final Map<String, ClasseResumo> classes = new LinkedHashMap<>();
    private int totalEventos;
    private int velocidadeN;
    private double velocidadeSoma;
    private double velocidadeMax;
    private int aceleracaoN;
    private double aceleracaoSoma;
    private double aceleracaoMax;

    public void limpar() {
        classes.clear();
        totalEventos = velocidadeN = aceleracaoN = 0;
        velocidadeSoma = velocidadeMax = aceleracaoSoma = aceleracaoMax = 0;
    }

    public void registrar(SwingEvento evento) {
        if (evento == null) return;
        totalEventos++;
        String codigo = evento.temClasseIdentificada() ? evento.codigoClasse : ClasseGolpeRaquete.NAO_IDENTIFICADO;
        ClasseResumo r = classes.get(codigo);
        if (r == null) {
            r = new ClasseResumo();
            classes.put(codigo, r);
        }
        r.quantidade++;
        if (evento.confiancaPct > 0) {
            r.confiancaN++;
            r.confiancaSoma += evento.confiancaPct;
        }
        if (evento.velRaqueteKmh > 0) {
            r.velocidadeN++;
            r.velocidadeSoma += evento.velRaqueteKmh;
            r.velocidadeMax = Math.max(r.velocidadeMax, evento.velRaqueteKmh);
            velocidadeN++;
            velocidadeSoma += evento.velRaqueteKmh;
            velocidadeMax = Math.max(velocidadeMax, evento.velRaqueteKmh);
        }
        if (evento.aceleracaoG > 0) {
            r.aceleracaoN++;
            r.aceleracaoSoma += evento.aceleracaoG;
            r.aceleracaoMax = Math.max(r.aceleracaoMax, evento.aceleracaoG);
            aceleracaoN++;
            aceleracaoSoma += evento.aceleracaoG;
            aceleracaoMax = Math.max(aceleracaoMax, evento.aceleracaoG);
        }
    }

    public int getTotalEventos() { return totalEventos; }
    public int getQuantidade(String codigo) {
        ClasseResumo r = classes.get(codigo);
        return r == null ? 0 : r.quantidade;
    }
    public int getOutrosTennis() {
        return getQuantidade("SMA") + getQuantidade("AMO") + getQuantidade("LOB") + getQuantidade("RUI");
    }

    /** Resumo curto de todas as classes observadas, incluindo ruído e rejeições. */
    public String resumoPorClasse() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ClasseGolpeRaquete.CODIGOS.length; i++) {
            String codigo = ClasseGolpeRaquete.CODIGOS[i];
            int quantidade = getQuantidade(codigo);
            if (quantidade <= 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(ClasseGolpeRaquete.NOMES[i]).append(' ').append(quantidade);
        }
        int naoIdentificados = getQuantidade(ClasseGolpeRaquete.NAO_IDENTIFICADO);
        if (naoIdentificados > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Não identificados ").append(naoIdentificados);
        }
        return sb.length() == 0 ? "nenhum evento" : sb.toString();
    }

    public String paraJson(String origem) {
        try {
            JSONObject raiz = new JSONObject();
            raiz.put("versao", 1);
            raiz.put("origem", origem == null ? "TREINO" : origem);
            raiz.put("totalEventos", totalEventos);
            raiz.put("velocidadeRaqueteMediaKmh", velocidadeN == 0 ? 0 : arredondar(velocidadeSoma / velocidadeN));
            raiz.put("velocidadeRaqueteMaximaKmh", arredondar(velocidadeMax));
            raiz.put("aceleracaoMediaG", aceleracaoN == 0 ? 0 : arredondar(aceleracaoSoma / aceleracaoN));
            raiz.put("aceleracaoMaximaG", arredondar(aceleracaoMax));
            JSONObject porClasse = new JSONObject();
            for (Map.Entry<String, ClasseResumo> entrada : classes.entrySet()) {
                String codigo = entrada.getKey();
                ClasseResumo r = entrada.getValue();
                JSONObject item = new JSONObject();
                item.put("nome", ClasseGolpeRaquete.nomePorCodigo(codigo));
                item.put("quantidade", r.quantidade);
                item.put("confiancaMediaPct", r.confiancaN == 0 ? 0 : arredondar(r.confiancaSoma / r.confiancaN));
                item.put("velocidadeMediaKmh", r.velocidadeN == 0 ? 0 : arredondar(r.velocidadeSoma / r.velocidadeN));
                item.put("velocidadeMaximaKmh", arredondar(r.velocidadeMax));
                item.put("aceleracaoMediaG", r.aceleracaoN == 0 ? 0 : arredondar(r.aceleracaoSoma / r.aceleracaoN));
                item.put("aceleracaoMaximaG", arredondar(r.aceleracaoMax));
                porClasse.put(codigo, item);
            }
            raiz.put("classes", porClasse);
            return raiz.toString();
        } catch (Exception e) {
            return "{\"versao\":1,\"origem\":\"" + (origem == null ? "TREINO" : origem) + "\",\"totalEventos\":" + totalEventos + "}";
        }
    }

    private double arredondar(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
