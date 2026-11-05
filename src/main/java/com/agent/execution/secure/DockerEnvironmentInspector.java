package com.agent.execution.secure;

/** Read-only Docker CLI inspection. It never pulls images or creates/starts containers. */
public final class DockerEnvironmentInspector {
    private final ReadOnlyDockerProbe probe;

    public DockerEnvironmentInspector(ReadOnlyDockerProbe probe) {
        this.probe = java.util.Objects.requireNonNull(probe);
    }

    public DockerEnvironmentCheck inspect(ExecutionPolicy policy) {
        final ProbeResult result;
        try { result = probe.inspect(policy.imageReference()); }
        catch (Exception exception) {
            return new DockerEnvironmentCheck(DockerEnvironmentCheck.Status.CLI_UNAVAILABLE,
                    false, false, false, false, "", "Docker probe unavailable");
        }
        if (result == null || !result.cliAvailable()) return check(DockerEnvironmentCheck.Status.CLI_UNAVAILABLE, result, "Docker CLI unavailable");
        if (!result.daemonAvailable()) return check(DockerEnvironmentCheck.Status.DAEMON_UNAVAILABLE, result, "Docker daemon unavailable");
        if (!"linux".equalsIgnoreCase(result.serverOs())) return check(DockerEnvironmentCheck.Status.WRONG_CONTAINER_MODE, result, "Docker daemon is not using Linux containers");
        if (!result.imagePresent()) return check(DockerEnvironmentCheck.Status.IMAGE_MISSING, result, "Pinned image is not present locally; automatic pull is disabled");
        if (!policy.imageReference().equals(result.imageDigest())) return check(DockerEnvironmentCheck.Status.IMAGE_DIGEST_MISMATCH, result, "Local image digest does not match policy");
        return check(DockerEnvironmentCheck.Status.READY, result, "Read-only preflight passed");
    }

    private static DockerEnvironmentCheck check(DockerEnvironmentCheck.Status status, ProbeResult result, String reason) {
        return new DockerEnvironmentCheck(status, result != null && result.cliAvailable(),
                result != null && result.daemonAvailable(), result != null && "linux".equalsIgnoreCase(result.serverOs()),
                result != null && result.imagePresent(), result == null ? "" : result.imageDigest(), reason);
    }

    /** Probe outputs only a server OS and the exact inspected RepoDigest; never raw Docker output. */
    @FunctionalInterface
    public interface ReadOnlyDockerProbe {
        ProbeResult inspect(String pinnedImageReference) throws Exception;
    }

    public record ProbeResult(boolean cliAvailable, boolean daemonAvailable, String serverOs,
                              boolean imagePresent, String imageDigest) {
        public ProbeResult { serverOs = serverOs == null ? "" : serverOs; imageDigest = imageDigest == null ? "" : imageDigest; }
    }

}
