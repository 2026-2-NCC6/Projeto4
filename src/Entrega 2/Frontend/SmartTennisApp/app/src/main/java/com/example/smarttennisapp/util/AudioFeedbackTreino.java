package com.example.smarttennisapp.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;

import java.util.ArrayDeque;
import java.util.Locale;

/** Voz em português e sinais sonoros curtos para acerto/erro durante a prática. */
public final class AudioFeedbackTreino implements AutoCloseable {
    private TextToSpeech tts;
    private ToneGenerator tons;
    private final ArrayDeque<String> filaFalas = new ArrayDeque<>();
    private boolean pronto = false;
    private boolean fechado = false;

    public AudioFeedbackTreino(Context context) {
        try {
            tons = new ToneGenerator(AudioManager.STREAM_MUSIC, 72);
        } catch (Exception ignored) { }
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (fechado || tts == null) return;
            if (status == TextToSpeech.SUCCESS) {
                int idioma = tts.setLanguage(new Locale("pt", "BR"));
                pronto = idioma != TextToSpeech.LANG_MISSING_DATA
                        && idioma != TextToSpeech.LANG_NOT_SUPPORTED;
                if (pronto) {
                    tts.setSpeechRate(1.0f);
                    while (!filaFalas.isEmpty()) falarAgora(filaFalas.removeFirst());
                }
            }
        });
    }

    public synchronized void falar(String texto) {
        if (fechado || texto == null || texto.trim().isEmpty()) return;
        if (!pronto || tts == null) {
            if (filaFalas.size() >= 8) filaFalas.removeFirst();
            filaFalas.addLast(texto);
            return;
        }
        falarAgora(texto);
    }

    private void falarAgora(String texto) {
        if (tts != null) tts.speak(texto, TextToSpeech.QUEUE_ADD, null, "smarttennis_" + System.nanoTime());
    }

    public void sucesso() {
        if (tons != null) {
            tons.startTone(ToneGenerator.TONE_PROP_ACK, 180);
        }
    }

    public void erro() {
        if (tons != null) {
            tons.startTone(ToneGenerator.TONE_PROP_NACK, 260);
        }
    }

    @Override public synchronized void close() {
        fechado = true;
        filaFalas.clear();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        if (tons != null) {
            try { tons.release(); } catch (Exception ignored) { }
            tons = null;
        }
    }
}
