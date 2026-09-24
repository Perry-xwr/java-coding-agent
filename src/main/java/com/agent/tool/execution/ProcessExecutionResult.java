package com.agent.tool.execution;

public record ProcessExecutionResult(
        int exitCode,
        boolean timedOut,
        String output,
        boolean outputTruncated,
        long durationMs
) {
    public ProcessExecutionResult {
        if (output == null) {
            output = "";
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }
}
