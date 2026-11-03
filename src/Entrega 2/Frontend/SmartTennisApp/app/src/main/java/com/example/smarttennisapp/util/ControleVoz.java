package com.example.smarttennisapp.util;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/** Controle de voz em pt-BR. A escuta só fica ativa após o usuário tocar no microfone. */
public final class ControleVoz {
    public static final int REQUEST_AUDIO_PERMISSION = 7101;

    public interface Listener {
        void aoIniciarTreino();
        void aoPararTreino();
    }

    private final Activity activity;
    private final Button botao;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer reconhecedor;
    private boolean ouvindo = false;
    private boolean liberado = true;

    private final Runnable reiniciarEscuta = () -> {
        if (ouvindo && liberado) iniciarReconhecimento();
    };

    public ControleVoz(Activity activity, Button botao, Listener listener) {
        this.activity = activity;
        this.botao = botao;
        this.listener = listener;
        if (botao != null) {
            botao.setOnClickListener(v -> alternarEscuta());
            atualizarBotao();
        }
    }

    public boolean estaOuvindo() { return ouvindo; }

    private void alternarEscuta() {
        if (ouvindo) {
            pararEscuta();
            Toast.makeText(activity, "Controle por voz desativado.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "O reconhecimento de voz não está disponível neste aparelho.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        ouvindo = true;
        atualizarBotao();
        iniciarReconhecimento();
        Toast.makeText(activity, "Diga: iniciar treino ou parar treino.", Toast.LENGTH_LONG).show();
    }

    public void tratarResultadoPermissao(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_AUDIO_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ouvindo = true;
            atualizarBotao();
            iniciarReconhecimento();
            Toast.makeText(activity, "Controle por voz ativo. Diga: iniciar treino ou parar treino.", Toast.LENGTH_LONG).show();
        } else {
            ouvindo = false;
            atualizarBotao();
            Toast.makeText(activity, "Permita o uso do microfone para controlar o treino por voz.", Toast.LENGTH_LONG).show();
        }
    }

    private void iniciarReconhecimento() {
        if (!ouvindo || !liberado || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            destruirReconhecedor();
            reconhecedor = SpeechRecognizer.createSpeechRecognizer(activity);
            reconhecedor.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    if (!ouvindo) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        pararEscuta();
                        Toast.makeText(activity, "Permissão de microfone necessária.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    agendarReinicio(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900L : 450L);
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> frases = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (frases != null && !frases.isEmpty()) interpretar(frases.get(0));
                    agendarReinicio(400L);
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            reconhecedor.startListening(intent);
        } catch (Exception e) {
            agendarReinicio(1000L);
        }
    }

    private void interpretar(String frase) {
        if (frase == null || frase.trim().isEmpty() || listener == null) return;
        String texto = Normalizer.normalize(frase, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
        if (texto.contains("parar") || texto.contains("encerra") || texto.contains("finaliza")
                || texto.contains("termina treino") || texto.contains("interrompe treino")
                || texto.contains("cancelar treino") || texto.contains("pausar treino")) {
            listener.aoPararTreino();
        } else if (texto.contains("iniciar") || texto.contains("comecar") || texto.contains("comece")
                || texto.contains("vamos treinar") || texto.contains("inicia treino")) {
            listener.aoIniciarTreino();
        }
    }

    private void agendarReinicio(long atraso) {
        handler.removeCallbacks(reiniciarEscuta);
        if (ouvindo) handler.postDelayed(reiniciarEscuta, atraso);
    }

    private void atualizarBotao() {
        if (botao == null) return;
        botao.setText(ouvindo ? "🎙 Ouvindo" : "🎙 Voz");
        botao.setContentDescription(ouvindo ? "Desativar controle por voz" : "Ativar controle por voz");
    }

    public void pararEscuta() {
        ouvindo = false;
        handler.removeCallbacksAndMessages(null);
        destruirReconhecedor();
        atualizarBotao();
    }

    private void destruirReconhecedor() {
        if (reconhecedor != null) {
            try { reconhecedor.cancel(); } catch (Exception ignored) { }
            try { reconhecedor.destroy(); } catch (Exception ignored) { }
            reconhecedor = null;
        }
    }

    public void liberar() {
        liberado = false;
        pararEscuta();
    }
}
