package com.seucrm.shared;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12;   // bytes
    private static final int    GCM_TAG    = 128;   // bits

    @Value("${app.encryption.secret-key}")
    private String secretKeyHex;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = hexToBytes(secretKeyHex);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "app.encryption.secret-key deve ter exatamente 32 bytes (64 hex chars). " +
                "Gere com: openssl rand -hex 32");
        }
        secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("EncryptionService inicializado com AES-256-GCM");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG, iv));

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8));

            // Formato: base64(iv) + "." + base64(ciphertext)
            return Base64.getEncoder().encodeToString(iv)
                    + "." + Base64.getEncoder().encodeToString(ciphertext);

        } catch (Exception e) {
            throw new RuntimeException("Falha ao criptografar", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException("Formato inválido");

            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Falha ao descriptografar", e);
        }
    }

    private byte[] hexToBytes(String hex) {
        int    len  = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
