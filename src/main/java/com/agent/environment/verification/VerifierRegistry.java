package com.agent.environment.verification;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class VerifierRegistry {
    private final List<CodeVerifier> verifiers;

    public VerifierRegistry(List<CodeVerifier> verifiers) {
        this.verifiers = List.copyOf(Objects.requireNonNull(verifiers, "verifiers must not be null"));
    }

    public Optional<CodeVerifier> verifierFor(Path file, Path workspaceRoot) {
        return verifiers.stream().filter(verifier -> verifier.supports(file, workspaceRoot)).findFirst();
    }

    public List<String> verifierIds() {
        return verifiers.stream().map(CodeVerifier::id).toList();
    }
}
