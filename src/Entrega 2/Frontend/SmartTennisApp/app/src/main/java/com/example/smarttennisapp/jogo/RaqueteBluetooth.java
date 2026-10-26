package com.example.smarttennisapp.jogo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conexão Bluetooth Classic (SPP) com a raquete "RAQUETE-TENIS" e leitura de linhas de texto.
 * Os callbacks sempre chegam na thread principal. O firmware do ESP32 não é alterado.
 */
public final class RaqueteBluetooth {

    public interface Ouvinte {
        void aoEstado(boolean conectado, String mensagem);
        void aoLinha(String linha);
    }

    private static final String NOME_DISPOSITIVO = "RAQUETE-TENIS";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context contexto;
    private final Ouvinte ouvinte;
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean ativo = false;
    private volatile BluetoothSocket socket;

    public RaqueteBluetooth(Context contexto, Ouvinte ouvinte) {
        this.contexto = contexto.getApplicationContext();
        this.ouvinte = ouvinte;
    }

    /** No Android 12+ é preciso a permissão BLUETOOTH_CONNECT em tempo de execução. */
    public boolean temPermissao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(contexto, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission") // verificado em temPermissao()
    public void conectar() {
        executor.execute(() -> {
            try {
                BluetoothAdapter adaptador = BluetoothAdapter.getDefaultAdapter();
                if (adaptador == null) { estado(false, "Bluetooth não disponível neste celular."); return; }
                // Android 12+ exige CONNECT também para isEnabled(); verificar antes de chamar a API.
                if (!temPermissao()) { estado(false, "Permissão Bluetooth não concedida."); return; }
                if (!adaptador.isEnabled()) { estado(false, "Ative o Bluetooth do celular."); return; }

                BluetoothDevice raquete = null;
                Set<BluetoothDevice> pareados = adaptador.getBondedDevices();
                for (BluetoothDevice d : pareados) {
                    String nome = d.getName();
                    if (nome != null && NOME_DISPOSITIVO.equalsIgnoreCase(nome)) { raquete = d; break; }
                }
                if (raquete == null) {
                    estado(false, "Raquete não encontrada. Pareie '" + NOME_DISPOSITIVO + "' nas configurações do Bluetooth.");
                    return;
                }
                estado(false, "Conectando à raquete...");
                fecharSocket();
                // A raquete já está pareada. Cancelar discovery não é necessário e exigiria SCAN.
                BluetoothSocket novo = raquete.createRfcommSocketToServiceRecord(SPP_UUID);
                novo.connect();
                socket = novo;
                ativo = true;
                estado(true, "Raquete conectada");
                final InputStream entrada = novo.getInputStream();
                // Thread própria: não prende o executor, então "reconectar" funciona.
                new Thread(() -> ler(novo, entrada), "raquete-leitura").start();
            } catch (Exception e) {
                ativo = false;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                estado(false, "Falha na conexão com a raquete: " + msg);
            }
        });
    }

    private void ler(BluetoothSocket meu, InputStream entrada) {
        byte[] buffer = new byte[512];
        StringBuilder linha = new StringBuilder();
        try {
            while (ativo && socket == meu) {
                int n = entrada.read(buffer);
                if (n < 0) break;
                String pedaco = new String(buffer, 0, n, StandardCharsets.UTF_8);
                for (int i = 0; i < pedaco.length(); i++) {
                    char c = pedaco.charAt(i);
                    if (c == '\n' || c == '\r') {
                        if (linha.length() > 0) {
                            final String pronta = linha.toString();
                            linha.setLength(0);
                            principal.post(() -> ouvinte.aoLinha(pronta));
                        }
                    } else if (linha.length() < 400) {
                        linha.append(c);
                    }
                }
            }
        } catch (IOException ignorado) {
            // cai para a mensagem de conexão perdida abaixo
        }
        if (ativo && socket == meu) {   // se foi substituído/fechado de propósito, não avisa
            ativo = false;
            estado(false, "Conexão com a raquete perdida.");
        }
    }

    private void estado(boolean conectado, String mensagem) {
        principal.post(() -> ouvinte.aoEstado(conectado, mensagem));
    }

    private void fecharSocket() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignorado) { }
        }
    }

    public void fechar() {
        ativo = false;
        fecharSocket();
        executor.shutdownNow();
    }
}
