package com.agent.execution.secure;

/** Sanitized, read-only Docker preflight result; no raw Docker output is retained. */
public record DockerEnvironmentCheck(Status status, boolean cliAvailable,
                                     boolean daemonAvailable, boolean linuxContainers,
                                     boolean pinnedImagePresent, String observedImageDigest,
                                     String reason) {
    public DockerEnvironmentCheck {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        observedImageDigest = observedImageDigest == null ? "" : observedImageDigest;
        reason = reason == null ? "" : reason;
    }

    public boolean readyFor(ExecutionPolicy policy) {
        return status == Status.READY && cliAvailable && daemonAvailable && linuxContainers
                && pinnedImagePresent && policy.imageReference().equals(observedImageDigest);
    }

    public enum Status { READY, CLI_UNAVAILABLE, DAEMON_UNAVAILABLE, WRONG_CONTAINER_MODE,
        IMAGE_MISSING, IMAGE_DIGEST_MISMATCH, POLICY_UNSATISFIED }
}
