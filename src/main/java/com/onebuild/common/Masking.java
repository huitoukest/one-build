package com.onebuild.common;

import java.util.regex.Pattern;

public final class Masking {

    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern TOKEN =
            Pattern.compile("(?i)((?:token|access_token|password|passwd|pwd|secret)\\s*[:=]\\s*)(\\S+)");
    private static final Pattern PRIVATE_KEY =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(://)[^/@\\s]+@");

    private Masking() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String result = PRIVATE_KEY.matcher(text).replaceAll("***PRIVATE_KEY***");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1***@");
        result = AUTHORIZATION.matcher(result).replaceAll("$1***");
        result = TOKEN.matcher(result).replaceAll("$1***");
        return result;
    }

    public static String secret(String value) {
        return value == null || value.isEmpty() ? value : "***";
    }
}
