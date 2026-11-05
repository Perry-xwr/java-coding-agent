package com.agent.execution.secure;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Redacts common credential-shaped output and applies a UTF-8 byte cap before feedback is returned. */
public final class ExecutionOutputSanitizer {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|access[_-]?token|authorization)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern KEY_SHAPE = Pattern.compile("(?i)\\b(?:sk|glm|ds)-[A-Za-z0-9_-]{16,}\\b");

    private ExecutionOutputSanitizer() { }

    public static SanitizedOutput sanitize(String raw, int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        String safe = raw == null ? "" : raw;
        safe = ASSIGNMENT.matcher(safe).replaceAll("$1$2[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = KEY_SHAPE.matcher(safe).replaceAll("[REDACTED]");
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return new SanitizedOutput(safe, false);
        String marker = "\n...[output truncated]";
        if (maxBytes <= marker.length()) return new SanitizedOutput(marker.substring(0, maxBytes), true);
        int limit = maxBytes - marker.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder prefix = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < safe.length();) {
            int codePoint = safe.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > limit) break;
            prefix.append(value);
            used += size;
            offset += Character.charCount(codePoint);
        }
        return new SanitizedOutput(prefix + marker, true);
    }

    public record SanitizedOutput(String output, boolean truncated) { }
}
