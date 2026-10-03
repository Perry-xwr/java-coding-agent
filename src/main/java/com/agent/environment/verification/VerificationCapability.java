package com.agent.environment.verification;

import java.util.Locale;
import java.util.Objects;

public record VerificationCapability(WorkspaceKind workspaceKind, String recommendedVerifier) {
    public VerificationCapability {
        Objects.requireNonNull(workspaceKind);
        recommendedVerifier = Objects.requireNonNullElse(recommendedVerifier, "unknown");
    }
    public static VerificationCapability unknown() {
        return new VerificationCapability(WorkspaceKind.UNKNOWN, "unknown");
    }
    public static VerificationCapability forFile(String file, boolean maven) {
        String name = Objects.requireNonNullElse(file, "").toLowerCase(Locale.ROOT);
        String verifier = name.endsWith(".java") ? (maven ? "maven" : "javac")
                : name.endsWith(".py") ? "python-syntax"
                : name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs") ? "node-check"
                : name.endsWith(".c") ? "gcc-syntax"
                : name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx") ? "g++-syntax"
                : "unknown";
        return new VerificationCapability(maven ? WorkspaceKind.MAVEN : WorkspaceKind.STANDALONE, verifier);
    }
    public enum WorkspaceKind { MAVEN, STANDALONE, UNKNOWN }
}
