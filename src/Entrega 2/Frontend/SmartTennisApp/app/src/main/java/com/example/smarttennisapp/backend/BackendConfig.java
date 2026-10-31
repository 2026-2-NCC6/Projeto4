package com.example.smarttennisapp.backend;

import android.content.Context;

/** URL do backend de contas: definida exclusivamente no código-fonte. */
public final class BackendConfig {
    public static final String DEFAULT_BASE_URL =
            "https://smartaceenrolados-dqfuhzaef0e5d5dz.southafricanorth-01.azurewebsites.net";

    /** Tempo para estabelecer a conexao HTTPS com o backend (inclui inicializacao do Azure). */
    public static final int CONNECT_TIMEOUT_MS = 60_000;

    /** Tempo maximo aguardando a resposta da API depois de conectar. */
    public static final int READ_TIMEOUT_MS = 120_000;

    private BackendConfig() { }

    public static String obterBaseUrl(Context context) {
        return DEFAULT_BASE_URL;
    }
}
