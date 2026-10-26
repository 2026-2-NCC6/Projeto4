package com.example.smarttennisapp.backend;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import android.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash local com salt; o app nao precisa armazenar a senha em texto puro para funcionar offline. */
public final class SenhaLocalUtil {
    private static final String PREFIXO = "pbkdf2$";
    private static final int ITERACOES = 120000;
    private static final int TAMANHO_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SenhaLocalUtil() { }

    public static String gerarHash(String senha) {
        if (senha == null) senha = "";
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = derivar(senha.toCharArray(), salt, ITERACOES);
        return PREFIXO + ITERACOES + "$" + Base64.encodeToString(salt, Base64.NO_WRAP) + "$"
                + Base64.encodeToString(derived, Base64.NO_WRAP);
    }

    public static boolean eHash(String valor) {
        return valor != null && valor.startsWith(PREFIXO);
    }

    /** Aceita senhas em texto puro de versoes antigas e permite converte-las no proximo login. */
    public static boolean verificar(String senhaDigitada, String armazenada) {
        if (senhaDigitada == null || armazenada == null) return false;
        if (!eHash(armazenada)) {
            return MessageDigest.isEqual(senhaDigitada.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    armazenada.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            String[] partes = armazenada.split("\\$", -1);
            if (partes.length != 4) return false;
            int iteracoes = Integer.parseInt(partes[1]);
            byte[] salt = Base64.decode(partes[2], Base64.NO_WRAP);
            byte[] esperado = Base64.decode(partes[3], Base64.NO_WRAP);
            byte[] calculado = derivar(senhaDigitada.toCharArray(), salt, iteracoes);
            boolean ok = MessageDigest.isEqual(esperado, calculado);
            Arrays.fill(calculado, (byte) 0);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derivar(char[] senha, byte[] salt, int iteracoes) {
        PBEKeySpec spec = new PBEKeySpec(senha, salt, iteracoes, TAMANHO_BITS);
        try {
            SecretKeyFactory factory;
            try {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            } catch (Exception indisponivel) {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            }
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel proteger a senha local.", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(senha, '\0');
        }
    }
}
