package com.kama.jchatmind.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test void saltsDifferAndOnlyTheCorrectPasswordVerifies() {
        PasswordHasher hasher = new PasswordHasher();
        String first = hasher.hash("demo1234");
        String second = hasher.hash("demo1234");
        assertNotEquals(first, second);
        assertFalse(first.contains("demo1234"));
        assertTrue(hasher.verify("demo1234", first));
        assertFalse(hasher.verify("wrongpass", first));
        assertFalse(hasher.verify(null, first));
        assertFalse(hasher.verify("demo1234", "plaintext"));
        assertFalse(hasher.verify("demo1234", "pbkdf2-sha256$999999999$abc$abc"));
    }
}
