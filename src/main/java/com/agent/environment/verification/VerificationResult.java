package com.agent.environment.verification;

import java.nio.file.Path;
import java.util.Objects;

public record VerificationResult(
        VerificationStatus status,
        Path file,
        String verifierId,
        String diagnosticSummary,
        String unavailableReason,
        long mutationSequence
) {
    public VerificationResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(file, "file must not be null");
        verifierId = Objects.requireNonNullElse(verifierId, "none");
        diagnosticSummary = Objects.requireNonNullElse(diagnosticSummary, "");
        unavailableReason = Objects.requireNonNullElse(unavailableReason, "");
        if (mutationSequence < 0) {
            throw new IllegalArgumentException("mutationSequence must not be negative");
        }
    }
}
