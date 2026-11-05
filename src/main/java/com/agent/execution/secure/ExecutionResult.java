package com.agent.execution.secure;

/** Typed result keeps consent, process start, termination, and exit status distinct. */
public record ExecutionResult(ApprovalDecision.Status approvalStatus, Status status,
                              boolean executed, Integer exitCode, String output,
                              boolean outputTruncated, long durationMs,
                              String requestDigest, String containerName,
                              CleanupStatus cleanupStatus) {
    public ExecutionResult {
        if (approvalStatus == null || status == null || cleanupStatus == null) {
            throw new IllegalArgumentException("result statuses must not be null");
        }
        output = output == null ? "" : output;
        requestDigest = requestDigest == null ? "" : requestDigest;
        containerName = containerName == null ? "" : containerName;
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must not be negative");
        if (status == Status.EXITED && (!executed || exitCode == null)) {
            throw new IllegalArgumentException("EXITED requires an executed process and exit code");
        }
        if (status == Status.DENIED && (approvalStatus != ApprovalDecision.Status.DENIED || executed)) {
            throw new IllegalArgumentException("DENIED must not execute");
        }
        if (status == Status.APPROVED && (approvalStatus != ApprovalDecision.Status.APPROVED || executed)) {
            throw new IllegalArgumentException("APPROVED is consent without execution");
        }
    }

    public enum Status {
        APPROVED, DENIED, UNAVAILABLE, POLICY_REJECTED, STAGING_FAILED,
        START_FAILED, TIMED_OUT, CANCELLED, EXITED, CLEANUP_FAILED
    }
    public enum CleanupStatus { NOT_REQUIRED, SUCCEEDED, FAILED, UNKNOWN }
}
