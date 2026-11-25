package com.onebuild.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathGuardTest {

    @Test
    void allowsNestedPath() {
        Path base = Paths.get("target", "ws", "proj1").toAbsolutePath();
        assertEquals(base.resolve("sub").normalize(), PathGuard.resolveWithin(base, "sub"));
    }

    @Test
    void rejectsParentEscape() {
        Path base = Paths.get("target", "ws", "proj1").toAbsolutePath();
        assertThrows(ApiException.class, () -> PathGuard.resolveWithin(base, ".."));
    }

    @Test
    void rejectsSiblingPrefixEscape() {
        Path base = Paths.get("target", "ws", "proj1").toAbsolutePath();
        assertThrows(ApiException.class, () -> PathGuard.resolveWithin(base, "../proj10"));
    }
}
