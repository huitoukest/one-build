package com.onebuild.exec;

import java.util.List;
import java.util.Locale;

public final class OsShellResolver {

    private OsShellResolver() {
    }

    public static List<String> resolve(String shell, String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String resolved = (shell == null || shell.isBlank() || "auto".equalsIgnoreCase(shell))
                ? (windows ? "cmd" : "sh")
                : shell.toLowerCase(Locale.ROOT);
        return switch (resolved) {
            case "cmd" -> List.of("cmd", "/c", command);
            case "powershell", "pwsh" -> List.of(windows ? "powershell" : "pwsh", "-Command", command);
            case "bash" -> List.of("bash", "-c", command);
            default -> List.of("sh", "-c", command);
        };
    }
}
