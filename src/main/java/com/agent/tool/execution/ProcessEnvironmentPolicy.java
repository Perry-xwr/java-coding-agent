package com.agent.tool.execution;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Creates a child environment from an explicit allowlist; arbitrary parent variables never pass through. */
public final class ProcessEnvironmentPolicy {
    private static final Set<String> WINDOWS_BASE = Set.of(
            "systemroot", "windir", "path", "pathext", "temp", "tmp"
    );
    private static final Set<String> UNIX_BASE = Set.of("path", "home", "tmpdir");
    private static final Set<String> JAVA_HOME = Set.of("java_home");
    private static final String JAVA_OPTIONS = "-Dfile.encoding=UTF-8"
            + " -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8";

    private ProcessEnvironmentPolicy() { }

    public static Map<String, String> create(
            ProcessEnvironmentProfile profile,
            Map<String, String> parentEnvironment,
            Map<String, String> trustedOverrides,
            boolean windows
    ) {
        if (profile == null) throw new IllegalArgumentException("environment profile is required");
        Map<String, String> source = parentEnvironment == null ? Map.of() : parentEnvironment;
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> base = windows ? WINDOWS_BASE : UNIX_BASE;
        copyAllowed(source, result, base, windows);
        if (profile == ProcessEnvironmentProfile.MAVEN
                || profile == ProcessEnvironmentProfile.JAVA_TOOL
                || profile == ProcessEnvironmentProfile.GENERAL) {
            copyAllowed(source, result, JAVA_HOME, windows);
        }

        // The previous runner guaranteed UTF-8 Java output. Keep that behavior but never
        // carry arbitrary parent JVM options into model-triggered child processes.
        putCanonical(result, "JAVA_TOOL_OPTIONS", JAVA_OPTIONS, windows);
        if (windows) {
            String systemRoot = find(source, "systemroot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                putCanonical(result, "COMSPEC", systemRoot + "\\System32\\cmd.exe", true);
            }
        }

        if (trustedOverrides != null) {
            Set<String> allowedOverrideNames = new java.util.HashSet<>(base);
            if (profile == ProcessEnvironmentProfile.MAVEN
                    || profile == ProcessEnvironmentProfile.JAVA_TOOL
                    || profile == ProcessEnvironmentProfile.GENERAL) {
                allowedOverrideNames.addAll(JAVA_HOME);
            }
            for (Map.Entry<String, String> entry : trustedOverrides.entrySet()) {
                if (entry.getKey() != null && allowedOverrideNames.contains(normalize(entry.getKey()))
                        && entry.getValue() != null) {
                    putCanonical(result, entry.getKey(), entry.getValue(), windows);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String find(Map<String, String> source, String name) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && normalize(entry.getKey()).equals(name)) return entry.getValue();
        }
        return null;
    }

    private static void copyAllowed(Map<String, String> source, Map<String, String> target,
                                    Set<String> allowed, boolean windows) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && allowed.contains(normalize(entry.getKey()))
                    && entry.getValue() != null) {
                putCanonical(target, entry.getKey(), entry.getValue(), windows);
            }
        }
    }

    private static void putCanonical(Map<String, String> target, String name, String value, boolean windows) {
        String normalized = normalize(name);
        String canonical = windows ? switch (normalized) {
            case "systemroot" -> "SystemRoot";
            case "windir" -> "WINDIR";
            case "path" -> "PATH";
            case "pathext" -> "PATHEXT";
            case "temp" -> "TEMP";
            case "tmp" -> "TMP";
            case "java_home" -> "JAVA_HOME";
            case "java_tool_options" -> "JAVA_TOOL_OPTIONS";
            default -> name;
        } : name;
        target.put(canonical, value);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
