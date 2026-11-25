package com.onebuild.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskingTest {

    @Test
    void masksWholeAuthorizationHeader() {
        String masked = Masking.mask("Authorization: Bearer abc.def.ghi");
        assertFalse(masked.contains("abc.def.ghi"), masked);
        assertTrue(masked.contains("***"), masked);
    }

    @Test
    void masksPasswordValue() {
        String masked = Masking.mask("password=secret-value");
        assertFalse(masked.contains("secret-value"), masked);
    }

    @Test
    void masksUrlEmbeddedCredentials() {
        String masked = Masking.mask("clone https://user:pass@git.example.com/repo.git failed");
        assertFalse(masked.contains("user:pass"), masked);
        assertTrue(masked.contains("://***@"), masked);
    }

    @Test
    void masksPrivateKey() {
        String masked = Masking.mask(
                "-----BEGIN TEST PRIVATE KEY-----\nxxxxx\n-----END TEST PRIVATE KEY-----");
        assertTrue(masked.contains("***PRIVATE_KEY***"), masked);
    }
}
