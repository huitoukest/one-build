package com.onebuild.common;

import java.nio.file.Path;

public final class PathGuard {

    private PathGuard() {
    }

    public static Path resolveWithin(Path base, String relative) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        String rel = (relative == null || relative.isBlank()) ? "." : relative;
        Path resolved = normalizedBase.resolve(rel).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw ApiException.badRequest("workdir 越界: " + relative);
        }
        return resolved;
    }
}
