package com.agent.execution.secure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Typed request for a fixed isolated action; deliberately has no executable or shell field. */
public record ExecutionRequest(UUID requestId, String toolCallId, Action action, Path workspaceRoot,
                               List<String> includedFiles, String testClass, String testMethod,
                               Duration timeout, int maxOutputBytes) {
    private static final Pattern TEST_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern TEST_METHOD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    public ExecutionRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (toolCallId == null || toolCallId.isBlank()) throw new IllegalArgumentException("toolCallId is required");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        includedFiles = List.copyOf(Objects.requireNonNull(includedFiles, "includedFiles"));
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be between 0 and 5 minutes");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1 and 1048576");
        }
        if (testClass != null && !TEST_CLASS.matcher(testClass).matches()) {
            throw new IllegalArgumentException("Invalid testClass");
        }
        if (testMethod != null && !TEST_METHOD.matcher(testMethod).matches()) {
            throw new IllegalArgumentException("Invalid testMethod");
        }
        if (testMethod != null && testClass == null) {
            throw new IllegalArgumentException("testMethod requires testClass");
        }
    }

    public enum Action { MAVEN_TEST }
}
