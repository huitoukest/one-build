package com.onebuild.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256UtilTest {

    @Test
    void knownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256Util.hex("abc"));
    }
}
