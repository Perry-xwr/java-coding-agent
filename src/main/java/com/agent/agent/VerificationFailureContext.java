package com.agent.agent;

import com.agent.environment.verification.DiagnosticLocation;
import com.agent.environment.verification.VerificationCapability;
import com.agent.environment.verification.VerificationStatus;

import java.util.Objects;

/** Run-local facts from one current verifier failure; contains no model reasoning. */
public record VerificationFailureContext(
        String file, String verifier, VerificationStatus status, String diagnostic,
        DiagnosticLocation location, long mutationSequence, String lastMutationTool,
        String lastSuccessfulRereadIdentity, VerificationCapability capability, int failureCount,
        int failureStep
) {
    public VerificationFailureContext {
        Objects.requireNonNull(file); Objects.requireNonNull(verifier); Objects.requireNonNull(status);
        diagnostic = Objects.requireNonNullElse(diagnostic, "");
        Objects.requireNonNull(location); Objects.requireNonNull(capability);
        lastMutationTool = Objects.requireNonNullElse(lastMutationTool, "unknown");
        lastSuccessfulRereadIdentity = Objects.requireNonNullElse(lastSuccessfulRereadIdentity, "unknown");
    }
}
