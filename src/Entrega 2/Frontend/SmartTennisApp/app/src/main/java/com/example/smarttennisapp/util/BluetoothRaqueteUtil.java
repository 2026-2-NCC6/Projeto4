package com.example.smarttennisapp.util;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Utilidades compartilhadas pelas telas Treino Livre e Treino Guiado.
 * Centraliza a descoberta do dispositivo já pareado e o socket RFCOMM,
 * sem assumir como cada tela interpreta ou envia os dados do ESP32.
 */
public final class BluetoothRaqueteUtil {
    public static final String NOME_DISPOSITIVO = "RAQUETE-TENIS";
    private static final UUID UUID_SPP = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );

    private BluetoothRaqueteUtil() {
        // Classe utilitária.
    }

    public static boolean temPermissaoConectar(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED;
    }

    /** Retorna a raquete pareada ou null quando ela não estiver pareada. */
    @Nullable
    public static BluetoothDevice encontrarDispositivoPareado(
            Context context,
            @Nullable BluetoothAdapter adapter
    ) {
        if (adapter == null) {
            return null;
        }
        if (!temPermissaoConectar(context)) {
            throw new SecurityException("Permissão BLUETOOTH_CONNECT não concedida.");
        }

        Set<BluetoothDevice> dispositivos = adapter.getBondedDevices();
        for (BluetoothDevice dispositivo : dispositivos) {
            String nome = dispositivo.getName();
            if (nome != null && NOME_DISPOSITIVO.equalsIgnoreCase(nome)) {
                return dispositivo;
            }
        }
        return null;
    }

    public static BluetoothSocket criarSocket(BluetoothDevice dispositivo)
            throws IOException {
        if (dispositivo == null) {
            throw new IllegalArgumentException("Dispositivo Bluetooth nulo.");
        }
        return dispositivo.createRfcommSocketToServiceRecord(UUID_SPP);
    }
}
