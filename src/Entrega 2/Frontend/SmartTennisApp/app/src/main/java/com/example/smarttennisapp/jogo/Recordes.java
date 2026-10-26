package com.example.smarttennisapp.jogo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Perfil de jogo do atleta: XP, recordes e a "força típica" por golpe (base da força relativa). */
public final class Recordes {
    private static final int JANELA_FORCA = 30;
    private static final int MIN_AMOSTRAS = 5;

    private final Map<String, List<Double>> forca = new HashMap<>(); // dados antigos em graus/s
    private final Map<String, List<Double>> velocidadeRaquete = new HashMap<>();
    public double prVelBolaKmh = 0;
    public double prForcaDps = 0; // preservado para ler recordes gravados por versões anteriores
    public double prVelRaqueteKmh = 0;
    public long xp = 0;
    public int sessoes = 0;
    public int maiorCombo = 0;
    public int chefoesDerrotados = 0;

    /** Mediana recente de força (°/s) do golpe; 0 enquanto há poucas amostras. */
    public double medianaForca(String golpe) {
        List<Double> lista = forca.get(golpe);
        if (lista == null || lista.size() < MIN_AMOSTRAS) return 0;
        List<Double> copia = new ArrayList<>(lista);
        Collections.sort(copia);
        int n = copia.size();
        return n % 2 == 1 ? copia.get(n / 2) : (copia.get(n / 2 - 1) + copia.get(n / 2)) / 2.0;
    }


    /** Mediana da velocidade estimada da cabeça da raquete para uma classe específica. */
    public double medianaVelRaquete(String codigoClasse) {
        List<Double> lista = velocidadeRaquete.get(codigoClasse);
        if (lista == null || lista.size() < MIN_AMOSTRAS) return 0;
        List<Double> copia = new ArrayList<>(lista);
        Collections.sort(copia);
        int n = copia.size();
        return n % 2 == 1 ? copia.get(n / 2) : (copia.get(n / 2 - 1) + copia.get(n / 2)) / 2.0;
    }

    public void registraVelRaquete(String codigoClasse, double kmh) {
        if (kmh <= 0) return;
        List<Double> lista = velocidadeRaquete.get(codigoClasse);
        if (lista == null) {
            lista = new ArrayList<>();
            velocidadeRaquete.put(codigoClasse, lista);
        }
        lista.add(kmh);
        while (lista.size() > JANELA_FORCA) lista.remove(0);
        if (kmh > prVelRaqueteKmh) prVelRaqueteKmh = kmh;
    }

    public void registraForca(String golpe, double dps) {
        if (dps <= 0) return;
        List<Double> lista = forca.get(golpe);
        if (lista == null) {
            lista = new ArrayList<>();
            forca.put(golpe, lista);
        }
        lista.add(dps);
        while (lista.size() > JANELA_FORCA) lista.remove(0);
        if (dps > prForcaDps) prForcaDps = dps;
    }

    public String serializar() {
        StringBuilder sb = new StringBuilder();
        sb.append("xp=").append(xp).append(';');
        sb.append("sessoes=").append(sessoes).append(';');
        sb.append("maiorCombo=").append(maiorCombo).append(';');
        sb.append("chefoes=").append(chefoesDerrotados).append(';');
        sb.append(String.format(Locale.US, "prVel=%.2f;prForca=%.1f;prRaquete=%.2f;", prVelBolaKmh, prForcaDps, prVelRaqueteKmh));
        for (Map.Entry<String, List<Double>> e : forca.entrySet()) {
            sb.append("f.").append(e.getKey()).append('=');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format(Locale.US, "%.0f", e.getValue().get(i)));
            }
            sb.append(';');
        }
        for (Map.Entry<String, List<Double>> e : velocidadeRaquete.entrySet()) {
            sb.append("vr.").append(e.getKey()).append('=');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format(Locale.US, "%.2f", e.getValue().get(i)));
            }
            sb.append(';');
        }
        return sb.toString();
    }

    public static Recordes desserializar(String texto) {
        Recordes r = new Recordes();
        if (texto == null || texto.isEmpty()) return r;
        for (String par : texto.split(";")) {
            int i = par.indexOf('=');
            if (i <= 0) continue;
            String k = par.substring(0, i), v = par.substring(i + 1);
            try {
                switch (k) {
                    case "xp": r.xp = Long.parseLong(v); break;
                    case "sessoes": r.sessoes = Integer.parseInt(v); break;
                    case "maiorCombo": r.maiorCombo = Integer.parseInt(v); break;
                    case "chefoes": r.chefoesDerrotados = Integer.parseInt(v); break;
                    case "prVel": r.prVelBolaKmh = Double.parseDouble(v); break;
                    case "prForca": r.prForcaDps = Double.parseDouble(v); break;
                    case "prRaquete": r.prVelRaqueteKmh = Double.parseDouble(v); break;
                    default:
                        if (k.startsWith("f.")) {
                            List<Double> lista = new ArrayList<>();
                            for (String n : v.split(",")) if (!n.isEmpty()) lista.add(Double.parseDouble(n));
                            r.forca.put(k.substring(2), lista);
                        } else if (k.startsWith("vr.")) {
                            List<Double> lista = new ArrayList<>();
                            for (String n : v.split(",")) if (!n.isEmpty()) lista.add(Double.parseDouble(n));
                            r.velocidadeRaquete.put(k.substring(3), lista);
                        }
                }
            } catch (NumberFormatException ignorado) {
                // valor corrompido: ignora só este campo
            }
        }
        return r;
    }
}
