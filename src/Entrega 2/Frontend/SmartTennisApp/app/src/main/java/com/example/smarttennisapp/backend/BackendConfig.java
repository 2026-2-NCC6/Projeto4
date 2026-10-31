package com.example.smarttennisapp.backend;

import android.content.Context;

/** URL do backend de contas: definida exclusivamente no código-fonte. */
public final class BackendConfig {
    public static final String DEFAULT_BASE_URL =
            "https://smartaceenrolados-dqfuhzaef0e5d5dz.southafricanorth-01.azurewebsites.net";

    private BackendConfig() { }

    public static String obterBaseUrl(Context context) {
        return DEFAULT_BASE_URL;
    }
}
