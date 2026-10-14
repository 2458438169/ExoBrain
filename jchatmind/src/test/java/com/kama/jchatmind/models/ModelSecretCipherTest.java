package com.kama.jchatmind.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ModelSecretCipherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void encryptsWithFreshNonceAndAuthenticatesTheModelIdentity() throws Exception {
        ModelSecretCipher cipher = configuredCipher();
        String secret = "provider-test-secret";
        String first = cipher.encrypt("model-a", secret);
        String second = cipher.encrypt("model-a", secret);
        assertNotEquals(first, second);
        assertFalse(first.contains(secret));
        assertEquals(secret, cipher.decrypt("model-a", first));
        assertThrows(ModelException.class, () -> cipher.decrypt("model-b", first));
        assertThrows(ModelException.class, () -> cipher.decrypt("model-a", first.substring(0, first.length() - 4) + "AAAA"));
    }

    @Test
    void incorrectOrMissingKeyHasOnlyASafeError() throws Exception {
        ModelSecretCipher cipher = configuredCipher();
        String encrypted = cipher.encrypt("model-a", "secret");
        ModelException wrongKey = assertThrows(ModelException.class,
                () -> configuredCipher().decrypt("model-a", encrypted));
        assertFalse(wrongKey.getMessage().contains(temporaryDirectory.toString()));
        ModelException missing = assertThrows(ModelException.class,
                () -> new ModelSecretCipher(new ModelSettings()).encrypt("model-a", "secret"));
        assertEquals(503, missing.getStatus());
        assertNull(missing.getCause());
    }

    private ModelSecretCipher configuredCipher() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path file = Files.createTempFile(temporaryDirectory, "model-key-", ".txt");
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        ModelSettings settings = new ModelSettings();
        settings.setEncryptionKeyFile(file.toString());
        return new ModelSecretCipher(settings);
    }
}
