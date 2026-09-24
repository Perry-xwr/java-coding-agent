package com.agent.tool;

import java.util.Map;
import java.util.Objects;

public record ToolResult(
        boolean success,
        String output,
        ToolErrorCode errorCode,
        String errorMessage,
        Map<String, Object> metadata
) {
    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (success) {
            Objects.requireNonNull(output, "successful result output must not be null");
            if (errorCode != null || errorMessage != null) {
                throw new IllegalArgumentException("successful result must not contain an error");
            }
        } else {
            Objects.requireNonNull(errorCode, "failed result errorCode must not be null");
            Objects.requireNonNull(errorMessage, "failed result errorMessage must not be null");
        }
    }

    public static ToolResult success(String output) {
        return success(output, Map.of());
    }

    public static ToolResult success(String output, Map<String, Object> metadata) {
        return new ToolResult(true, output, null, null, metadata);
    }

    public static ToolResult failure(ToolErrorCode errorCode, String errorMessage) {
        return failure(errorCode, errorMessage, Map.of());
    }

    public static ToolResult failure(
            ToolErrorCode errorCode,
            String errorMessage,
            Map<String, Object> metadata
    ) {
        return new ToolResult(false, null, errorCode, errorMessage, metadata);
    }

    public static ToolResult failureWithOutput(
            ToolErrorCode errorCode,
            String errorMessage,
            String output,
            Map<String, Object> metadata
    ) {
        return new ToolResult(false, output, errorCode, errorMessage, metadata);
    }
}
