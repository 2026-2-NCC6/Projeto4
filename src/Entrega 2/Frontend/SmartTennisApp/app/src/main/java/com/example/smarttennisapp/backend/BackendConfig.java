package com.example.smarttennisapp.backend;

import android.content.Context;
import android.content.SharedPreferences;

/** Configuracao da API de contas/sincronizacao (separada da API da webcam). */
public final class BackendConfig {
    /** Android Emulator -> computador host. Em celular fisico, troque pelo IPv4 do PC na rede local. */
    public static final String DEFAULT_BASE_URL = "http://192.168.1.105:5001";
    private static final String PREFS = "SmartTennisBackend";
    private static final String KEY_BASE_URL = "baseUrl";

    private BackendConfig() { }

    public static String obterBaseUrl(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_BASE_URL;
        value = value.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
    }

    /** Permite configurar o IP da maquina por codigo ou por uma futura tela de configuracao. */
    public static void salvarBaseUrl(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        String normalized = url.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) normalized = "http://" + normalized;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BASE_URL, normalized).apply();
    }
}
