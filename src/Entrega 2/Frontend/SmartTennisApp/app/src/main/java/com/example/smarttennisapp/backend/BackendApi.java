package com.example.smarttennisapp.backend;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Cliente HTTP sincrono: chamar somente em uma thread de trabalho, nunca na UI. */
public final class BackendApi {
    private BackendApi() { }

    public static final class ApiException extends IOException {
        public final int statusCode;
        public final String code;

        ApiException(int statusCode, String code, String message) {
            super(message == null || message.isEmpty() ? "Erro HTTP " + statusCode : message);
            this.statusCode = statusCode;
            this.code = code == null ? "REQUEST_FAILED" : code;
        }
    }

    public static JSONObject login(Context context, String email, String senha) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("senha", senha);
        } catch (Exception e) {
            throw new IOException("Nao foi possivel preparar o login.", e);
        }
        return request(context, "POST", "/api/auth/login", null, body);
    }

    public static JSONObject cadastrar(Context context, String nome, String email, String senha, String dataNascimento) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("nome", nome);
            body.put("email", email);
            body.put("senha", senha);
            if (dataNascimento != null && !dataNascimento.trim().isEmpty()) body.put("dataNascimento", dataNascimento);
        } catch (Exception e) {
            throw new IOException("Nao foi possivel preparar o cadastro.", e);
        }
        return request(context, "POST", "/api/auth/register", null, body);
    }

    public static JSONObject sincronizar(Context context, String token, JSONObject payload) throws IOException {
        return request(context, "POST", "/api/sync", token, payload);
    }

    public static JSONObject baixarSnapshot(Context context, String token) throws IOException {
        return request(context, "GET", "/api/sync", token, null);
    }

    public static JSONObject obterRankingGlobal(Context context, String token, int limite) throws IOException {
        int tamanho = Math.max(1, Math.min(100, limite));
        return request(context, "GET", "/api/rankings/global?limit=" + tamanho, token, null);
    }

    public static JSONObject obterRankingDesafio(Context context, String token, String desafioId,
                                                   String nomeDesafio, int limite) throws IOException {
        if (desafioId == null || desafioId.trim().isEmpty()) {
            throw new IOException("O desafio selecionado nao possui identificador valido.");
        }
        int tamanho = Math.max(1, Math.min(100, limite));
        String id = URLEncoder.encode(desafioId, StandardCharsets.UTF_8.name()).replace("+", "%20");
        String nome = URLEncoder.encode(nomeDesafio == null ? "" : nomeDesafio, StandardCharsets.UTF_8.name());
        return request(context, "GET", "/api/rankings/challenges/" + id + "?nome=" + nome + "&limit=" + tamanho, token, null);
    }

    private static JSONObject request(Context context, String method, String path, String token, JSONObject body) throws IOException {
        String baseUrl = BackendConfig.obterBaseUrl(context);
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("URL do backend SmartTennis não definida no código-fonte.");
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            // Azure pode acordar uma instância parada e o bcrypt aumenta o tempo de auth.
            // Limites curtos causavam falso erro depois de o servidor já gravar o cadastro.
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String responseText = readFully(stream);
            JSONObject response;
            try {
                response = responseText.trim().isEmpty() ? new JSONObject() : new JSONObject(responseText);
            } catch (Exception e) {
                if (status >= 200 && status < 300) return new JSONObject();
                throw new ApiException(status, "INVALID_RESPONSE", "Servidor retornou uma resposta invalida.");
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(status, response.optString("code", "REQUEST_FAILED"),
                        response.optString("error", "Falha na requisicao ao servidor."));
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Servidor SmartTennis indisponivel: " + (e.getMessage() == null ? "falha de rede" : e.getMessage()), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readFully(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
