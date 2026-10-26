package com.example.smarttennisapp;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Cliente HTTP local: recebe métricas/eventos, nunca recebe o vídeo da webcam. */
public final class VisaoComputacionalClient {

    public interface Listener {
        void onStatus(boolean conectado, JSONObject status, String mensagem);
        void onBallFlight(JSONObject evento);
    }

    public interface SessionCallback {
        void onResult(boolean sucesso, JSONObject resposta, String mensagem);
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String baseUrl = "";
    private volatile String token = "";
    private volatile boolean connected = false;
    private volatile boolean cameraActive = false;
    private volatile boolean modelReady = false;
    private volatile Listener listener;
    private volatile String sessionId = null;
    private volatile long lastEventId = 0L;
    private volatile boolean closed = false;
    private volatile boolean pollingScheduled = false;
    private volatile ScheduledFuture<?> pollingTask = null;
    // Diferença entre o relógio do PC e o do celular (PC - celular), estimada a cada leitura de status.
    private volatile long clockOffsetMs = 0L;
    private volatile long bestRttMs = Long.MAX_VALUE;

    public void setListener(Listener callback) {
        listener = callback;
    }

    public void connect(String address, String accessToken, Listener callback) {
        if (address == null || address.trim().isEmpty() || accessToken == null || accessToken.trim().isEmpty()) {
            notifyStatus(false, null, "Informe o endereço e o token exibidos no computador.");
            return;
        }
        String normalized = address.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (!normalized.equals(baseUrl) || !accessToken.trim().equals(token)) {
            lastEventId = 0L;
            sessionId = null;
            clockOffsetMs = 0L;
            bestRttMs = Long.MAX_VALUE;
        }
        baseUrl = normalized;
        token = accessToken.trim();
        listener = callback;
        closed = false;
        // Somente uma leitura de saúde ao conectar. Métricas e eventos são consultados
        // periodicamente apenas durante uma sessão de treino ativa.
        executor.execute(() -> pollOnce(true));
    }

    /** Relógio do PC menos relógio do celular, em ms: tempoCelular = tempoPC - getClockOffsetMs(). */
    public long getClockOffsetMs() { return clockOffsetMs; }

    public boolean isConnected() { return connected; }
    public boolean isCameraActive() { return cameraActive && modelReady; }
    public String getSessionId() { return sessionId; }

    public void startSession(String newSessionId, String challengeId, float targetSpeedKmh, int targetBallCount, SessionCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("sessionId", newSessionId);
                body.put("challengeId", challengeId);
                body.put("targetSpeedKmh", targetSpeedKmh);
                body.put("targetBallCount", targetBallCount);
                JSONObject response = requestJson("POST", "/api/session/start", body);
                if (response.optBoolean("ok", false)) {
                    sessionId = newSessionId;
                    lastEventId = response.optLong("event_cursor", lastEventId);
                    startPollingForSession();
                    deliverSession(callback, true, response, response.optString("message", "Sessão de câmera iniciada."));
                } else {
                    deliverSession(callback, false, response, response.optString("message", "O computador não aceitou a sessão."));
                }
            } catch (Exception e) {
                deliverSession(callback, false, null, "Falha ao iniciar a sessão de câmera: " + safeMessage(e));
            }
        });
    }

    private synchronized void startPollingForSession() {
        if (pollingTask != null && !pollingTask.isCancelled()) return;
        pollingScheduled = true;
        pollingTask = executor.scheduleWithFixedDelay(() -> pollOnce(false), 0, 800, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPollingForSession() {
        if (pollingTask != null) pollingTask.cancel(false);
        pollingTask = null;
        pollingScheduled = false;
    }

    public void stopSession() {
        final String stoppingId = sessionId;
        sessionId = null;
        if (stoppingId == null || baseUrl.isEmpty()) {
            stopPollingForSession();
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("sessionId", stoppingId);
                requestJson("POST", "/api/session/stop", body);
            } catch (Exception ignored) { }
            stopPollingForSession();
        });
    }

    private void pollOnce(boolean initial) {
        if (closed || baseUrl.isEmpty()) return;
        try {
            long t0 = System.currentTimeMillis();
            JSONObject status = requestJson("GET", "/api/status", null);
            long t1 = System.currentTimeMillis();
            if (status.has("server_epoch_ms")) {
                // Amostra de menor latência = menor erro (o erro máximo é metade do RTT).
                long rtt = t1 - t0;
                if (rtt <= bestRttMs) {
                    clockOffsetMs = status.optLong("server_epoch_ms") - (t0 + t1) / 2;
                    bestRttMs = rtt;
                } else {
                    bestRttMs += 2;   // deixa o melhor RTT "envelhecer" para acompanhar deriva do relógio
                }
            }
            connected = status.optBoolean("ok", false);
            cameraActive = status.optBoolean("camera_active", false);
            modelReady = status.optBoolean("model_ready", false);
            notifyStatus(connected, status, cameraActive && modelReady
                    ? "Computador conectado · webcam ativa"
                    : "Computador conectado · aguardando modelo e webcam");

            if (sessionId != null) {
                JSONObject eventPayload = requestJson("GET", "/api/events?after=" + lastEventId, null);
                JSONArray events = eventPayload.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.optJSONObject(i);
                        if (event == null) continue;
                        long id = event.optLong("event_id", lastEventId);
                        if (id > lastEventId) lastEventId = id;
                        if ("ball_flight".equals(event.optString("type"))
                                && sessionId.equals(event.optString("session_id", ""))) {
                            notifyBallFlight(event);
                        }
                    }
                }
            }
        } catch (Exception e) {
            boolean wasConnected = connected;
            connected = false;
            cameraActive = false;
            modelReady = false;
            if (initial || wasConnected) {
                notifyStatus(false, null, "Sem conexão com o computador: " + safeMessage(e));
            }
        }
    }

    private JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2200);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-SmartTennis-Token", token);
            connection.setUseCaches(false);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder responseText = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) responseText.append(line);
                }
            }
            JSONObject response = responseText.length() > 0 ? new JSONObject(responseText.toString()) : new JSONObject();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("HTTP " + status + ": " + response.optString("message", response.optString("error", "erro de API")));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void notifyStatus(boolean ok, JSONObject status, String message) {
        Listener callback = listener;
        if (callback == null) return;
        mainHandler.post(() -> {
            Listener current = listener;
            if (current != null) current.onStatus(ok, status, message);
        });
    }

    private void notifyBallFlight(JSONObject event) {
        Listener callback = listener;
        if (callback == null) return;
        mainHandler.post(() -> {
            Listener current = listener;
            if (current != null) current.onBallFlight(event);
        });
    }

    private void deliverSession(SessionCallback callback, boolean ok, JSONObject response, String message) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(ok, response, message));
    }

    private String safeMessage(Exception error) {
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) return error.getClass().getSimpleName();
        return value;
    }

    public void close() {
        closed = true;
        final String stoppingId = sessionId;
        sessionId = null;
        listener = null;
        if (stoppingId != null && !baseUrl.isEmpty()) {
            try {
                executor.execute(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("sessionId", stoppingId);
                        requestJson("POST", "/api/session/stop", body);
                    } catch (Exception ignored) { }
                    stopPollingForSession();
                    executor.shutdown();
                });
                return;
            } catch (RuntimeException ignored) { }
        }
        stopPollingForSession();
        executor.shutdownNow();
    }
}
