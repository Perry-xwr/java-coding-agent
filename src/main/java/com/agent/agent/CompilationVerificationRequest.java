package com.agent.agent;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic detector for explicit Java compilation checks in a user turn. */
public record CompilationVerificationRequest(String targetPath, boolean repairOnFailure) {
    private static final Pattern JAVA_PATH = Pattern.compile(
            "(?i)(?:[A-Za-z0-9_.-]+[\\\\/])*[A-Za-z0-9_$.-]+\\.java"
    );

    public CompilationVerificationRequest {
        targetPath = targetPath == null || targetPath.isBlank() ? null : targetPath;
    }

    public static CompilationVerificationRequest detect(String userTask) {
        String text = Objects.requireNonNull(userTask, "userTask must not be null");
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean compileIntent = containsAny(normalized, "编译", "javac", "compile", "compilation", "compiler error",
                "语法错误");
        if (!compileIntent) {
            return null;
        }
        Matcher matcher = JAVA_PATH.matcher(text);
        String target = matcher.find() ? matcher.group() : null;
        if (target == null && !normalized.contains("java")) {
            return null;
        }
        boolean repair = containsAny(normalized, "修复", "修正", "改正", "自动修", "fix", "repair");
        return new CompilationVerificationRequest(target, repair);
    }

    public boolean targets(String path) {
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".java")) {
            return false;
        }
        if (targetPath == null) {
            return true;
        }
        try {
            Path expected = Path.of(targetPath).normalize();
            Path actual = Path.of(path).normalize();
            return expected.equals(actual);
        } catch (RuntimeException exception) {
            return targetPath.equals(path);
        }
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
