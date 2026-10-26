package com.example.smarttennisapp.backend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Token da API separado da sessao local: o app continua abrindo sem internet. */
public final class SessaoRemota {
    private static final String PREFS = "SmartTennisRemoteSession";
    private static final String TOKEN = "token";
    private static final String SERVER_ID = "serverUserId";
    private static final String LOCAL_ID = "localUserId";
    private static final String EMAIL = "email";

    private SessaoRemota() { }

    public static void salvar(Context context, JSONObject auth, int localUserId) {
        if (context == null || auth == null || localUserId <= 0) return;
        JSONObject user = auth.optJSONObject("user");
        if (user == null) return;
        String token = auth.optString("token", "");
        int serverId = user.optInt("id", -1);
        if (token.isEmpty() || serverId <= 0) return;
        prefs(context).edit()
                .putString(TOKEN, token)
                .putInt(SERVER_ID, serverId)
                .putInt(LOCAL_ID, localUserId)
                .putString(EMAIL, user.optString("email", ""))
                .apply();
    }

    public static String obterToken(Context context, int localUserId) {
        if (context == null || localUserId <= 0) return null;
        SharedPreferences p = prefs(context);
        if (p.getInt(LOCAL_ID, -1) != localUserId) return null;
        String token = p.getString(TOKEN, null);
        return token == null || token.trim().isEmpty() ? null : token;
    }

    public static int obterIdServidor(Context context, int localUserId) {
        if (context == null || prefs(context).getInt(LOCAL_ID, -1) != localUserId) return -1;
        return prefs(context).getInt(SERVER_ID, -1);
    }

    public static void limpar(Context context) {
        if (context != null) prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
