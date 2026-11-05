package com.agent.execution.secure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable host-enforced isolation profile. Model input cannot override any field. */
public record ExecutionPolicy(String imageReference, Path stagingParent, String containerUser,
                              double cpuLimit, long memoryBytes, int processLimit,
                              long workspaceTmpfsBytes, long tempTmpfsBytes,
                              Duration maxExecutionTime, int maxOutputBytes,
                              int maxFiles, long maxSingleFileBytes, long maxTotalBytes) {
    private static final Pattern PINNED_IMAGE = Pattern.compile("[^\\s@]+@sha256:[a-fA-F0-9]{64}");

    public ExecutionPolicy {
        Objects.requireNonNull(imageReference, "imageReference");
        Objects.requireNonNull(stagingParent, "stagingParent");
        Objects.requireNonNull(containerUser, "containerUser");
        Objects.requireNonNull(maxExecutionTime, "maxExecutionTime");
        stagingParent = stagingParent.toAbsolutePath().normalize();
        if (!PINNED_IMAGE.matcher(imageReference).matches()) {
            throw new IllegalArgumentException("Image must be pinned by sha256 digest");
        }
        if (!containerUser.matches("[1-9][0-9]*:[1-9][0-9]*")) {
            throw new IllegalArgumentException("Container user must be a fixed non-root uid:gid");
        }
        if (!Double.isFinite(cpuLimit) || cpuLimit <= 0 || cpuLimit > 2
                || memoryBytes < 128L * 1024 * 1024 || memoryBytes > 4L * 1024 * 1024 * 1024
                || processLimit < 1 || processLimit > 128
                || workspaceTmpfsBytes < 16L * 1024 * 1024
                || workspaceTmpfsBytes > 2L * 1024 * 1024 * 1024
                || tempTmpfsBytes < 4L * 1024 * 1024
                || tempTmpfsBytes > 256L * 1024 * 1024
                || maxExecutionTime.isNegative() || maxExecutionTime.isZero()
                || maxExecutionTime.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputBytes < 1 || maxOutputBytes > 1024 * 1024
                || maxFiles < 1 || maxFiles > 2000 || maxSingleFileBytes < 1
                || maxSingleFileBytes > 64L * 1024 * 1024
                || maxTotalBytes < maxSingleFileBytes || maxTotalBytes > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("Execution policy limits are outside safe bounds");
        }
    }

    public void validate(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.timeout().compareTo(maxExecutionTime) > 0
                || request.maxOutputBytes() > maxOutputBytes) {
            throw new IllegalArgumentException("Request exceeds the fixed execution policy");
        }
    }

    public String profileId() {
        return "docker-maven-offline-v1|" + imageReference + "|" + containerUser + "|"
                + cpuLimit + "|" + memoryBytes + "|" + processLimit + "|"
                + workspaceTmpfsBytes + "|" + tempTmpfsBytes + "|"
                + maxExecutionTime.toMillis() + "|" + maxOutputBytes + "|" + stagingParent
                + "|" + maxFiles + "|" + maxSingleFileBytes + "|" + maxTotalBytes;
    }
}
