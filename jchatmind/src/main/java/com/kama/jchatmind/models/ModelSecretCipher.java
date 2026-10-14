package com.kama.jchatmind.models;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class ModelSecretCipher {
    private final ModelSettings settings;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public ModelSecretCipher(ModelSettings settings) { this.settings = settings; }

    public boolean isConfigured() {
        return StringUtils.hasText(settings.getEncryptionKeyFile());
    }

    public String encrypt(String modelId, String plaintext) {
        if (!StringUtils.hasText(plaintext)) return null;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(modelId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException error) {
            throw unavailable();
        }
    }

    public String decrypt(String modelId, String ciphertext) {
        if (ciphertext == null) return "";
        try {
            String[] parts = ciphertext.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("v1")) throw unavailable();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(modelId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw unavailable();
        }
    }

    private SecretKey key() {
        SecretKey existing = key;
        if (existing != null) return existing;
        synchronized (this) {
            if (key != null) return key;
            if (!isConfigured()) throw unavailable();
            byte[] bytes = null;
            try {
                Path path = Path.of(settings.getEncryptionKeyFile());
                if (!Files.isRegularFile(path) || Files.size(path) > 256) throw unavailable();
                bytes = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
                if (bytes.length != 32) throw unavailable();
                key = new SecretKeySpec(bytes, "AES");
                return key;
            } catch (Exception error) {
                throw unavailable();
            } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private ModelException unavailable() {
        return new ModelException(503, "模型密钥配置不可用，请联系管理员");
    }
}
