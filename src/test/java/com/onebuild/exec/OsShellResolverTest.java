package com.onebuild.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OsShellResolverTest {

    @Test
    void bashWrapsWithDashC() {
        assertEquals(List.of("bash", "-c", "echo 1"),
                OsShellResolver.resolve("bash", "echo 1"));
    }

    @Test
    void cmdWrapsWithSlashC() {
        assertEquals(List.of("cmd", "/c", "dir"),
                OsShellResolver.resolve("cmd", "dir"));
    }

    @Test
    void powershellUsesCommandFlag() {
        List<String> resolved = OsShellResolver.resolve("powershell", "Get-Date");
        assertEquals("-Command", resolved.get(1));
        assertEquals("Get-Date", resolved.get(2));
    }
}
