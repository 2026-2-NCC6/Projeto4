package com.example.smarttennisapp;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Exibe uma contagem regressiva visual antes de iniciar a sessão de treino. */
public final class InicioTreinoOverlay {
    private final FrameLayout overlay;
    private final TextView numero;
    private final TextView mensagem;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean executando;
    private int etapa;
    private Runnable aoConcluir;

    private final Runnable avancar = this::avancarEtapa;

    private static final String[] NUMEROS = {"3", "2", "1", "VAI!"};
    private static final String[] MENSAGENS = {
            "PREPARE-SE!",
            "POSIÇÃO DE JOGO!",
            "OLHOS NA BOLA!",
            "BORA JOGAR!"
    };

    public InicioTreinoOverlay(FrameLayout overlay, TextView numero, TextView mensagem) {
        this.overlay = overlay;
        this.numero = numero;
        this.mensagem = mensagem;
    }

    public boolean isRunning() {
        return executando;
    }

    public void start(Runnable callback) {
        if (executando) return;

        executando = true;
        etapa = 0;
        aoConcluir = callback;
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().cancel();
        overlay.animate().alpha(1f).setDuration(180L).start();
        mostrarEtapa();
        handler.removeCallbacks(avancar);
        handler.postDelayed(avancar, 700L);
    }

    private void avancarEtapa() {
        if (!executando) return;
        etapa++;
        if (etapa < NUMEROS.length) {
            mostrarEtapa();
            handler.postDelayed(avancar, etapa == 3 ? 550L : 700L);
            return;
        }

        executando = false;
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.setAlpha(1f);
            Runnable callback = aoConcluir;
            aoConcluir = null;
            if (callback != null) callback.run();
        }).start();
    }

    private void mostrarEtapa() {
        numero.animate().cancel();
        numero.setText(NUMEROS[etapa]);
        numero.setTextColor(etapa == 3
                ? Color.rgb(102, 209, 158)
                : Color.rgb(255, 200, 87));
        mensagem.setText(MENSAGENS[etapa]);
        numero.setScaleX(0.72f);
        numero.setScaleY(0.72f);
        numero.setAlpha(0.45f);
        numero.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(330L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    public void cancel() {
        handler.removeCallbacks(avancar);
        executando = false;
        aoConcluir = null;
        overlay.animate().cancel();
        numero.animate().cancel();
        overlay.setAlpha(1f);
        overlay.setVisibility(View.GONE);
    }
}
