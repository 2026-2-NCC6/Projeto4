package com.example.smarttennisapp;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Cliente da estação local. O vídeo permanece no computador; o celular recebe apenas eventos. */
public final class VisaoComputacionalClient {
    private static final int DISCOVERY_PORT = 37020;
    private static final String DISCOVERY_REQUEST = "SMARTTENNIS_CAMERA_DISCOVER_V1";

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
    private volatile boolean discovering = false;
    private volatile Context discoveryContext;
    private volatile ScheduledFuture<?> pollingTask = null;
    private volatile long clockOffsetMs = 0L;
    private volatile long bestRttMs = Long.MAX_VALUE;

    public void setListener(Listener callback) { listener = callback; }

    /** Descobre a estação por UDP broadcast na LAN, sem exigir IP ou token digitados.
     * A busca continua enquanto esta tela estiver ativa, inclusive se o servidor for
     * iniciado depois do aplicativo.
     */
    public void discoverAndConnect(Context context, Listener callback) {
        if (context == null || closed) return;
        listener = callback;
        discoveryContext = context.getApplicationContext();
        iniciarDescobertaSeNecessario();
    }

    private synchronized void iniciarDescobertaSeNecessario() {
        if (closed || discovering || discoveryContext == null) return;
        discovering = true;
        connected = false;
        cameraActive = false;
        modelReady = false;
        notifyStatus(false, null, "Procurando servidor Python na rede local…");
        executor.execute(this::executarDescoberta);
    }

    private void executarDescoberta() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setBroadcast(true);
            socket.setSoTimeout(450);
            byte[] query = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            byte[] responseBuffer = new byte[2048];
            Set<InetAddress> destinations = obterBroadcasts(discoveryContext);
            long proximoAviso = System.currentTimeMillis() + 12000L;

            while (!closed) {
                for (InetAddress destination : destinations) {
                    try {
                        socket.send(new DatagramPacket(query, query.length, destination, DISCOVERY_PORT));
                    } catch (Exception ignored) { }
                }

                long janela = System.currentTimeMillis() + 1800L;
                while (!closed && System.currentTimeMillis() < janela) {
                    try {
                        DatagramPacket packet = new DatagramPacket(responseBuffer, responseBuffer.length);
                        socket.receive(packet);
                        JSONObject data = new JSONObject(new String(packet.getData(), packet.getOffset(),
                                packet.getLength(), StandardCharsets.UTF_8));
                        if (!"smarttennis-camera".equals(data.optString("service"))) continue;
                        int port = data.optInt("port", 8765);
                        String accessToken = data.optString("token", "").trim();
                        if (port < 1 || port > 65535 || accessToken.isEmpty()) continue;

                        InetAddress host = packet.getAddress();
                        baseUrl = "http://" + host.getHostAddress() + ":" + port;
                        token = accessToken;
                        lastEventId = 0L;
                        sessionId = null;
                        clockOffsetMs = 0L;
                        bestRttMs = Long.MAX_VALUE;
                        connected = false;
                        cameraActive = false;
                        modelReady = false;
                        notifyStatus(false, null, "Servidor encontrado em " + host.getHostAddress() + ". Verificando webcam…");
                        pollOnce(true);
                        if (connected) {
                            iniciarPollingSaude();
                            return;
                        }
                        // A estação respondeu ao broadcast, mas a API não ficou acessível.
                        // Continua a busca para recuperar um endereço/token válidos.
                        baseUrl = "";
                        token = "";
                    } catch (java.net.SocketTimeoutException ignored) {
                        break;
                    } catch (Exception ignored) { }
                }

                if (!closed && System.currentTimeMillis() >= proximoAviso) {
                    notifyStatus(false, null,
                            "Ainda procurando a estação Python… mantenha o computador e o celular na mesma rede Wi-Fi.");
                    proximoAviso = System.currentTimeMillis() + 12000L;
                }
            }
        } catch (Exception e) {
            if (!closed) notifyStatus(false, null, "Não foi possível procurar o servidor local: " + safeMessage(e));
        } finally {
            discovering = false;
        }
    }

    private synchronized void reiniciarDescoberta() {
        if (closed || discoveryContext == null) return;
        baseUrl = "";
        token = "";
        connected = false;
        cameraActive = false;
        modelReady = false;
        sessionId = null;
        pararPolling();
        iniciarDescobertaSeNecessario();
    }

    private Set<InetAddress> obterBroadcasts(Context context) {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        try { addresses.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                    int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                    byte[] bytes = new byte[] {(byte) (broadcast & 0xff), (byte) ((broadcast >> 8) & 0xff),
                            (byte) ((broadcast >> 16) & 0xff), (byte) ((broadcast >> 24) & 0xff)};
                    addresses.add(InetAddress.getByAddress(bytes));
                }
            }
        } catch (Exception ignored) { }
        return addresses;
    }

    /** Conexão manual mantida para compatibilidade com o minijogo existente. */
    public void connect(String address, String accessToken, Listener callback) {
        if (address == null || address.trim().isEmpty() || accessToken == null || accessToken.trim().isEmpty()) {
            notifyStatus(false, null, "Endereço ou token da estação inválido.");
            return;
        }
        String normalized = address.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) normalized = "http://" + normalized;
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        baseUrl = normalized;
        token = accessToken.trim();
        listener = callback;
        closed = false;
        lastEventId = 0L;
        sessionId = null;
        clockOffsetMs = 0L;
        bestRttMs = Long.MAX_VALUE;
        executor.execute(() -> pollOnce(true));
        iniciarPollingSaude();
    }

    public long getClockOffsetMs() { return clockOffsetMs; }
    public boolean isConnected() { return connected; }
    public boolean isCameraActive() { return cameraActive && modelReady; }
    public String getSessionId() { return sessionId; }

    public void startSession(String newSessionId, String challengeId, float targetSpeedKmh,
                             int targetBallCount, SessionCallback callback) {
        startSession(newSessionId, challengeId, targetSpeedKmh, targetBallCount, "IMPACTOS", callback);
    }

    public void startSession(String newSessionId, String challengeId, float targetSpeedKmh,
                             int targetBallCount, String cameraMode, SessionCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("sessionId", newSessionId);
                body.put("challengeId", challengeId);
                body.put("targetSpeedKmh", targetSpeedKmh);
                body.put("targetBallCount", targetBallCount);
                body.put("cameraMode", cameraMode == null ? "IMPACTOS" : cameraMode);
                JSONObject response = requestJson("POST", "/api/session/start", body);
                if (response.optBoolean("ok", false)) {
                    sessionId = newSessionId;
                    lastEventId = response.optLong("event_cursor", lastEventId);
                    iniciarPollingSessao();
                    deliverSession(callback, true, response, response.optString("message", "Sessão de câmera iniciada."));
                } else {
                    deliverSession(callback, false, response, response.optString("message", "O computador não aceitou a sessão."));
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) {
                    reiniciarDescoberta();
                }
                deliverSession(callback, false, null, "Falha ao iniciar a sessão de câmera: " + safeMessage(e));
            }
        });
    }

    private synchronized void iniciarPollingSaude() {
        substituirPolling(2, TimeUnit.SECONDS);
    }

    private synchronized void iniciarPollingSessao() {
        substituirPolling(800, TimeUnit.MILLISECONDS);
    }

    private synchronized void substituirPolling(long intervalo, TimeUnit unidade) {
        if (pollingTask != null) pollingTask.cancel(false);
        if (closed || baseUrl.isEmpty()) return;
        pollingTask = executor.scheduleWithFixedDelay(() -> pollOnce(false), 0, intervalo, unidade);
    }

    private synchronized void pararPolling() {
        if (pollingTask != null) pollingTask.cancel(false);
        pollingTask = null;
    }

    public void stopSession() {
        final String stoppingId = sessionId;
        sessionId = null;
        if (stoppingId == null || baseUrl.isEmpty()) {
            iniciarPollingSaude();
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("sessionId", stoppingId);
                requestJson("POST", "/api/session/stop", body);
            } catch (Exception ignored) { }
            iniciarPollingSaude();
        });
    }

    private void pollOnce(boolean initial) {
        if (closed || baseUrl.isEmpty()) return;
        try {
            long t0 = System.currentTimeMillis();
            JSONObject status = requestJson("GET", "/api/status", null);
            long t1 = System.currentTimeMillis();
            if (status.has("server_epoch_ms")) {
                long rtt = t1 - t0;
                if (rtt <= bestRttMs) {
                    clockOffsetMs = status.optLong("server_epoch_ms") - (t0 + t1) / 2;
                    bestRttMs = rtt;
                } else bestRttMs += 2;
            }
            connected = status.optBoolean("ok", false);
            cameraActive = status.optBoolean("camera_active", false);
            modelReady = status.optBoolean("model_ready", false);
            notifyStatus(connected, status, cameraActive && modelReady
                    ? "Computador conectado · webcam ativa"
                    : (connected ? "Computador conectado · aguardando webcam" : "Servidor local indisponível"));

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
                                && sessionId.equals(event.optString("session_id", ""))) notifyBallFlight(event);
                    }
                }
            }
        } catch (Exception e) {
            boolean wasConnected = connected;
            boolean tinhaEstacaoDescoberta = discoveryContext != null && !baseUrl.isEmpty();
            connected = false;
            cameraActive = false;
            modelReady = false;
            if (initial || wasConnected) notifyStatus(false, null, "Sem conexão com o computador: " + safeMessage(e));
            if (tinhaEstacaoDescoberta) reiniciarDescoberta();
        }
    }

    private JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2600);
            connection.setReadTimeout(3000);
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
        if (listener == null) return;
        mainHandler.post(() -> { Listener current = listener; if (current != null) current.onStatus(ok, status, message); });
    }
    private void notifyBallFlight(JSONObject event) {
        if (listener == null) return;
        mainHandler.post(() -> { Listener current = listener; if (current != null) current.onBallFlight(event); });
    }
    private void deliverSession(SessionCallback callback, boolean ok, JSONObject response, String message) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(ok, response, message));
    }
    private String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    public void close() {
        closed = true;
        discovering = false;
        final String stoppingId = sessionId;
        sessionId = null;
        listener = null;
        pararPolling();
        if (stoppingId != null && !baseUrl.isEmpty()) {
            try {
                executor.execute(() -> {
                    try {
                        JSONObject body = new JSONObject(); body.put("sessionId", stoppingId);
                        requestJson("POST", "/api/session/stop", body);
                    } catch (Exception ignored) { }
                    executor.shutdown();
                });
                return;
            } catch (RuntimeException ignored) { }
        }
        executor.shutdownNow();
    }
}
