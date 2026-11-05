package com.agent.execution.secure;

/** Result returned only by an isolated backend; approval is attached by the coordinator. */
public record BackendExecutionResult(ExecutionResult.Status status, boolean executed,
                                    Integer exitCode, String output, boolean outputTruncated,
                                    long durationMs, String containerName,
                                    ExecutionResult.CleanupStatus cleanupStatus) {
    public BackendExecutionResult {
        if (status == null || cleanupStatus == null) throw new IllegalArgumentException("statuses must not be null");
        output = output == null ? "" : output;
        containerName = containerName == null ? "" : containerName;
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must not be negative");
        if (status == ExecutionResult.Status.EXITED && (!executed || exitCode == null)) {
            throw new IllegalArgumentException("EXITED requires an execution and exit code");
        }
        if (status == ExecutionResult.Status.DENIED || status == ExecutionResult.Status.APPROVED) {
            throw new IllegalArgumentException("Backend cannot decide approval");
        }
    }
}
