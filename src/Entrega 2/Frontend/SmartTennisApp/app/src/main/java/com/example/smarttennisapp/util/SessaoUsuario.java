package com.example.smarttennisapp.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Único ponto de leitura e escrita da sessão local do usuário. */
public final class SessaoUsuario {
    private static final String PREFERENCIAS = "UserData";
    private static final String CHAVE_ID = "id";

    private SessaoUsuario() {
        // Classe utilitária.
    }

    /** Salva apenas IDs positivos; valores inválidos nunca substituem uma sessão válida. */
    public static void salvarId(Context context, int id) {
        if (context == null || id <= 0) return;
        preferencias(context).edit().putInt(CHAVE_ID, id).apply();
    }

    /** Lê o ID aceitando inteiro ou texto, para manter compatibilidade com versões antigas. */
    public static int obterId(Context context) {
        if (context == null) return -1;
        SharedPreferences prefs = preferencias(context);
        try {
            int id = prefs.getInt(CHAVE_ID, -1);
            if (id > 0) return id;
        } catch (ClassCastException ignored) {
            // Versões antigas podem ter salvo o ID como texto.
        }
        try {
            String valor = prefs.getString(CHAVE_ID, null);
            if (valor != null) {
                int id = Integer.parseInt(valor.trim());
                return id > 0 ? id : -1;
            }
        } catch (ClassCastException | NumberFormatException ignored) {
            // Tipo ou valor inválido: não há sessão utilizável.
        }
        return -1;
    }

    public static boolean temSessao(Context context) {
        return obterId(context) > 0;
    }

    public static void limpar(Context context) {
        if (context != null) preferencias(context).edit().remove(CHAVE_ID).apply();
    }

    private static SharedPreferences preferencias(Context context) {
        return context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }
}
