package com.onebuild.common;

import com.onebuild.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    @Test
    void encryptThenDecryptRoundTrip() {
        AppProperties props = new AppProperties();
        props.getSecurity().setMasterKey("unit-test-master-key");
        CryptoService service = new CryptoService(props);
        String cipher = service.encrypt("hello-secret");
        assertNotEquals("hello-secret", cipher);
        assertTrue(cipher.startsWith("enc:"));
        assertEquals("hello-secret", service.decrypt(cipher));
    }

    @Test
    void differentIvPerEncryption() {
        AppProperties props = new AppProperties();
        props.getSecurity().setMasterKey("unit-test-master-key");
        CryptoService service = new CryptoService(props);
        assertNotEquals(service.encrypt("same"), service.encrypt("same"));
    }
}
