package com.agent.agent;

/** Bounded, actionable instruction assembled only from verifier/environment facts. */
public record RepairDirective(String text) {
    public static RepairDirective from(VerificationFailureContext context) {
        StringBuilder text = new StringBuilder("POST_EDIT_VERIFICATION_FAILED\nfile: ")
                .append(context.file()).append("\nverifier: ").append(context.verifier());
        if (context.location().known()) {
            text.append("\nlocation: line ").append(context.location().line());
            if (context.location().column() > 0) text.append(", column ").append(context.location().column());
        }
        if (!context.diagnostic().isBlank()) text.append("\ndiagnostic: ").append(context.diagnostic());
        text.append("\nrepair requirement: Re-read this file before editing it again.")
                .append("\nrepair guidance: Make the smallest change needed to restore verification; do not modify unrelated files.");
        if (context.failureCount() >= 2) {
            text.append("\nRepeated verification failure. Re-read the file and reconsider the repair approach.");
        }
        if (context.capability().workspaceKind() == com.agent.environment.verification.VerificationCapability.WorkspaceKind.STANDALONE
                && "javac".equals(context.capability().recommendedVerifier())) {
            text.append("\nworkspace_kind: STANDALONE\nproject verifier: javac\nMaven project not detected.");
        } else if (context.capability().workspaceKind() == com.agent.environment.verification.VerificationCapability.WorkspaceKind.MAVEN) {
            text.append("\nworkspace_kind: MAVEN\nproject verifier: Maven.");
        }
        return new RepairDirective(text.toString());
    }
}
