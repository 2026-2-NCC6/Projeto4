package com.example.smarttennisapp.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.smarttennisapp.database.AppDatabase;
import com.example.smarttennisapp.database.DatabaseProvider;
import com.example.smarttennisapp.database.DesafioEntity;
import com.example.smarttennisapp.database.HistoricoTreinoEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sincronizacao offline-first. Todas as telas continuam lendo/escrevendo no Room;
 * o servidor e usado como copia da conta para outros celulares.
 */
public final class SyncManager {
    private static final String TAG = "SmartTennisSync";
    private static final Object SYNC_LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private SyncManager() { }

    public static void sincronizarEmSegundoPlano(Context context, int usuarioLocalId) {
        if (context == null || usuarioLocalId <= 0) return;
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> sincronizarAgora(appContext, usuarioLocalId));
    }

    /** Deve ser chamado em uma thread de trabalho (login/cadastro ja usam executor). */
    public static boolean sincronizarAgora(Context context, int usuarioLocalId) {
        synchronized (SYNC_LOCK) {
            if (context == null || usuarioLocalId <= 0) return false;
            String token = SessaoRemota.obterToken(context, usuarioLocalId);
            if (token == null) return false; // conta local offline, ainda sem vinculo com o servidor.

            AppDatabase db = DatabaseProvider.getInstance(context.getApplicationContext());
            try {
            JSONArray desafios = new JSONArray();
            for (DesafioEntity desafio : db.desafioDAO().listarTodosParaSincronizacao(usuarioLocalId)) {
                desafios.put(paraJson(desafio));
            }

            JSONArray historicos = new JSONArray();
            for (HistoricoTreinoEntity item : db.treinoDAO().listarHistoricoUsuario(usuarioLocalId)) {
                if (item.syncId == null || item.syncId.trim().isEmpty()) {
                    item.syncId = UUID.randomUUID().toString();
                    db.treinoDAO().atualizarSyncId(item.id, item.syncId);
                }
                historicos.put(paraJson(item));
            }

            JSONObject gamificacao = new JSONObject();
            SharedPreferences xpPrefs = context.getSharedPreferences("SmartTennisGamificacao", Context.MODE_PRIVATE);
            SharedPreferences jogoPrefs = context.getSharedPreferences("JogoPrefs", Context.MODE_PRIVATE);
            gamificacao.put("xpCarreira", xpPrefs.getInt("xp_total_" + usuarioLocalId, 0));
            gamificacao.put("recordes", jogoPrefs.getString("recordes_" + usuarioLocalId, ""));

            JSONObject payload = new JSONObject();
            payload.put("challenges", desafios);
            payload.put("history", historicos);
            payload.put("gamification", gamificacao);
            JSONObject snapshot = BackendApi.sincronizar(context, token, payload);
            aplicarSnapshot(context, db, usuarioLocalId, snapshot);
            Log.i(TAG, "Sincronizacao concluida: " + desafios.length() + " desafios locais, "
                    + historicos.length() + " historicos locais.");
                return true;
            } catch (Exception e) {
                // A falha remota nunca invalida o banco local nem bloqueia o treino.
                Log.w(TAG, "Sincronizacao adiada; os dados continuam salvos neste celular: " + e.getMessage());
                return false;
            }
        }
    }

    private static JSONObject paraJson(DesafioEntity d) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", d.id);
        json.put("nome", d.nome == null ? "Desafio" : d.nome);
        json.put("descricao", d.descricao == null ? "" : d.descricao);
        json.put("duracaoMinutos", d.duracaoMinutos);
        json.put("dificuldade", d.dificuldade == null ? "Iniciante" : d.dificuldade);
        json.put("categoria", d.categoria == null ? "PERSONALIZADO" : d.categoria);
        json.put("sequenciaGolpes", d.sequenciaGolpes == null ? "" : d.sequenciaGolpes);
        json.put("criadoEm", d.criadoEm);
        json.put("atualizadoEm", d.atualizadoEm);
        json.put("velocidadeAlvoKmh", d.velocidadeAlvoKmh);
        json.put("bolasAlvo", d.bolasAlvo);
        json.put("excluido", d.excluido);
        return json;
    }

    private static JSONObject paraJson(HistoricoTreinoEntity h) throws Exception {
        JSONObject json = new JSONObject();
        json.put("syncId", h.syncId);
        json.put("treinoId", h.treinoId == null ? "TREINO" : h.treinoId);
        json.put("dataConclusao", h.dataConclusao);
        json.put("tempoSegundos", h.tempoSegundos);
        json.put("pontuacao", h.pontuacao);
        json.put("saques", h.saques);
        json.put("forehands", h.forehands);
        json.put("backhands", h.backhands);
        json.put("golpesCorretos", h.golpesCorretos);
        json.put("totalGolpes", h.totalGolpes);
        json.put("golpesRecebidos", h.golpesRecebidos);
        json.put("concluido", h.concluido);
        json.put("bolasDetectadas", h.bolasDetectadas);
        json.put("bolasAcimaMeta", h.bolasAcimaMeta);
        json.put("velocidadeMaximaKmh", h.velocidadeMaximaKmh);
        json.put("velocidadeMediaKmh", h.velocidadeMediaKmh);
        json.put("metricasCameraJson", h.metricasCameraJson == null ? "[]" : h.metricasCameraJson);
        json.put("metricasGolpesJson", h.metricasGolpesJson == null ? "{}" : h.metricasGolpesJson);
        return json;
    }

    private static void aplicarSnapshot(Context context, AppDatabase db, int usuarioLocalId, JSONObject snapshot) throws Exception {
        JSONArray desafios = snapshot.optJSONArray("challenges");
        if (desafios != null) {
            for (int i = 0; i < desafios.length(); i++) {
                JSONObject json = desafios.optJSONObject(i);
                if (json == null) continue;
                String id = json.optString("id", "");
                if (id.trim().isEmpty()) continue;
                DesafioEntity local = db.desafioDAO().buscarParaSincronizacao(id, usuarioLocalId);
                long atualizadoServidor = json.optLong("atualizadoEm", 0L);
                if (local != null && local.atualizadoEm >= atualizadoServidor) continue;

                DesafioEntity remoto = new DesafioEntity(
                        id,
                        usuarioLocalId,
                        json.optString("nome", "Desafio"),
                        json.optString("descricao", ""),
                        Math.max(1, json.optInt("duracaoMinutos", 1)),
                        json.optString("dificuldade", "Iniciante"),
                        json.optString("categoria", "PERSONALIZADO"),
                        json.optString("sequenciaGolpes", ""),
                        json.optLong("criadoEm", System.currentTimeMillis()),
                        atualizadoServidor,
                        (float) json.optDouble("velocidadeAlvoKmh", 0),
                        json.optInt("bolasAlvo", 0)
                );
                remoto.excluido = json.optBoolean("excluido", false);
                db.desafioDAO().salvar(remoto);
            }
        }

        JSONObject gamificacaoRemota = snapshot.optJSONObject("gamification");
        if (gamificacaoRemota != null) {
            SharedPreferences xpPrefs = context.getSharedPreferences("SmartTennisGamificacao", Context.MODE_PRIVATE);
            int xpLocal = xpPrefs.getInt("xp_total_" + usuarioLocalId, 0);
            int xpServidor = gamificacaoRemota.optInt("xpCarreira", 0);
            xpPrefs.edit().putInt("xp_total_" + usuarioLocalId, Math.max(xpLocal, xpServidor)).apply();
            String recordesServidor = gamificacaoRemota.optString("recordes", "");
            if (!recordesServidor.trim().isEmpty()) {
                context.getSharedPreferences("JogoPrefs", Context.MODE_PRIVATE).edit()
                        .putString("recordes_" + usuarioLocalId, recordesServidor).apply();
            }
        }

        JSONArray historicos = snapshot.optJSONArray("history");
        if (historicos != null) {
            for (int i = 0; i < historicos.length(); i++) {
                JSONObject json = historicos.optJSONObject(i);
                if (json == null) continue;
                String syncId = json.optString("syncId", "");
                if (syncId.trim().isEmpty() || db.treinoDAO().buscarPorSyncId(syncId) != null) continue;

                HistoricoTreinoEntity item = new HistoricoTreinoEntity();
                item.syncId = syncId;
                item.usuarioId = usuarioLocalId;
                item.treinoId = json.optString("treinoId", "TREINO");
                item.dataConclusao = json.optLong("dataConclusao", System.currentTimeMillis());
                item.tempoSegundos = json.optInt("tempoSegundos", 0);
                item.pontuacao = json.optInt("pontuacao", 0);
                item.saques = json.optInt("saques", 0);
                item.forehands = json.optInt("forehands", 0);
                item.backhands = json.optInt("backhands", 0);
                item.golpesCorretos = json.optInt("golpesCorretos", 0);
                item.totalGolpes = json.optInt("totalGolpes", 0);
                item.golpesRecebidos = json.optInt("golpesRecebidos", 0);
                item.concluido = json.optBoolean("concluido", false);
                item.bolasDetectadas = json.optInt("bolasDetectadas", 0);
                item.bolasAcimaMeta = json.optInt("bolasAcimaMeta", 0);
                item.velocidadeMaximaKmh = (float) json.optDouble("velocidadeMaximaKmh", 0);
                item.velocidadeMediaKmh = (float) json.optDouble("velocidadeMediaKmh", 0);
                item.metricasCameraJson = json.optString("metricasCameraJson", "[]");
                item.metricasGolpesJson = json.optString("metricasGolpesJson", "{}");
                // Fallback para servidores antigos que só devolvem o campo de métricas legado.
                try {
                    JSONObject metricasNovas = new JSONObject(item.metricasGolpesJson);
                    if (metricasNovas.optJSONObject("classes") == null) {
                        JSONObject antigas = new JSONObject(item.metricasCameraJson);
                        JSONObject espelho = antigas.optJSONObject("metricasGolpes");
                        if (espelho != null) item.metricasGolpesJson = espelho.toString();
                    }
                } catch (Exception ignorada) {
                    item.metricasGolpesJson = "{}";
                }
                db.treinoDAO().salvarHistoricoSincronizado(item);
            }
        }
    }
}
