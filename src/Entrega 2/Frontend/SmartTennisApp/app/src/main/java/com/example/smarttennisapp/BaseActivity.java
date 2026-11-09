package com.example.smarttennisapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.smarttennisapp.backend.BackendApi;
import com.example.smarttennisapp.backend.SessaoRemota;
import com.example.smarttennisapp.util.SessaoUsuario;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity base: mantém o modo imersivo e verifica notificações sociais enquanto o app está em primeiro plano. */
public abstract class BaseActivity extends AppCompatActivity {
    private static final String CANAL_AMIZADES = "smartace_amizades";
    private static final int REQUEST_NOTIFICACOES = 713;
    private static final long INTERVALO_NOTIFICACOES_MS = 20_000L;

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService notificationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean consultaEmAndamento = new AtomicBoolean(false);
    private boolean verificacaoAtiva;

    private final Runnable tarefaVerificacao = new Runnable() {
        @Override public void run() {
            if (!verificacaoAtiva || isFinishing() || isDestroyed()) return;
            consultarNotificacoesAmizade();
            notificationHandler.postDelayed(this, INTERVALO_NOTIFICACOES_MS);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        aplicarModoImersivo();
        iniciarVerificacaoNotificacoes();
    }

    @Override
    protected void onPause() {
        verificacaoAtiva = false;
        notificationHandler.removeCallbacks(tarefaVerificacao);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) aplicarModoImersivo();
    }

    private void aplicarModoImersivo() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        if (controller == null) return;
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void iniciarVerificacaoNotificacoes() {
        verificacaoAtiva = true;
        boolean aguardandoPermissao = solicitarPermissaoNotificacoesSeNecessario();
        if (!aguardandoPermissao) consultarNotificacoesAmizade();
        notificationHandler.removeCallbacks(tarefaVerificacao);
        notificationHandler.postDelayed(tarefaVerificacao, INTERVALO_NOTIFICACOES_MS);
    }

    /** Retorna true quando acabou de abrir a caixa de diálogo de permissão. */
    private boolean solicitarPermissaoNotificacoesSeNecessario() {
        if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        int usuarioId = SessaoUsuario.obterId(this);
        if (usuarioId <= 0 || SessaoRemota.obterToken(this, usuarioId) == null) return false;

        SharedPreferences preferencias = getSharedPreferences("PermissoesSmartAce", MODE_PRIVATE);
        if (!preferencias.getBoolean("solicitouNotificacoes", false)) {
            preferencias.edit().putBoolean("solicitouNotificacoes", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICACOES);
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICACOES && verificacaoAtiva) {
            // Só consome notificações depois que a escolha de permissão foi apresentada ao usuário.
            consultarNotificacoesAmizade();
        }
    }

    private void consultarNotificacoesAmizade() {
        if (!verificacaoAtiva || !consultaEmAndamento.compareAndSet(false, true)) return;
        int usuarioId = SessaoUsuario.obterId(this);
        String token = usuarioId > 0 ? SessaoRemota.obterToken(this, usuarioId) : null;
        if (token == null) {
            consultaEmAndamento.set(false);
            return;
        }

        notificationExecutor.execute(() -> {
            try {
                JSONObject resposta = BackendApi.listarNotificacoesAmizade(getApplicationContext(), token);
                JSONArray notificacoes = resposta.optJSONArray("notificacoes");
                if (notificacoes != null && notificacoes.length() > 0) {
                    runOnUiThread(() -> {
                        // Usa o contexto da aplicação para que o aviso não seja descartado
                        // durante uma troca de Activity enquanto a consulta HTTP termina.
                        for (int i = 0; i < notificacoes.length(); i++) {
                            JSONObject notificacao = notificacoes.optJSONObject(i);
                            if (notificacao != null) exibirNotificacaoAmizade(notificacao);
                        }
                        if (!isFinishing() && !isDestroyed()) onFriendNotificationsReceived(notificacoes);
                    });
                }
            } catch (IOException ignored) {
                // A consulta pode falhar temporariamente; a próxima verificação tentará novamente.
            } finally {
                consultaEmAndamento.set(false);
            }
        });
    }

    private void exibirNotificacaoAmizade(JSONObject dados) {
        Context contexto = getApplicationContext();
        String titulo = dados.optString("titulo", "Novo amigo no SmartAce");
        String mensagem = dados.optString("mensagem", "Alguém adicionou você à lista de amigos.");
        if (Build.VERSION.SDK_INT >= 33 &&
                contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(contexto, mensagem, Toast.LENGTH_LONG).show();
            return;
        }

        NotificationManager manager = (NotificationManager) contexto.getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL_AMIZADES, "Amizades", NotificationManager.IMPORTANCE_DEFAULT);
            canal.setDescription("Avisos quando alguém adiciona você como amigo no SmartAce.");
            manager.createNotificationChannel(canal);
        }

        Intent destino = new Intent(contexto, MeuPerfil.class);
        destino.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = (int) Math.max(1L, dados.optLong("id", System.currentTimeMillis()) % Integer.MAX_VALUE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(contexto, id, destino, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(contexto, CANAL_AMIZADES)
                : new Notification.Builder(contexto);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titulo)
                .setContentText(mensagem)
                .setStyle(new Notification.BigTextStyle().bigText(mensagem))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL);
        if (Build.VERSION.SDK_INT < 26) builder.setPriority(Notification.PRIORITY_DEFAULT);
        manager.notify(id, builder.build());
    }

    /** Permite que telas específicas atualizem seu conteúdo quando uma notificação social chega. */
    protected void onFriendNotificationsReceived(JSONArray notificacoes) {
        // Implementação padrão: a notificação do Android já foi exibida.
    }

    @Override
    protected void onDestroy() {
        verificacaoAtiva = false;
        notificationHandler.removeCallbacks(tarefaVerificacao);
        notificationExecutor.shutdownNow();
        super.onDestroy();
    }
}
