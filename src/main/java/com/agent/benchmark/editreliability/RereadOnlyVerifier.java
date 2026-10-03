package com.agent.benchmark.editreliability;

import com.agent.environment.verification.CodeVerifier;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Benchmark-only V1.8 pre-verification baseline: no verifier feedback is emitted. */
public final class RereadOnlyVerifier implements CodeVerifier {
    private static final Set<String> EXTENSIONS = Set.of(".py", ".js", ".mjs", ".cjs", ".java");

    @Override public String id() { return "reread-only"; }

    @Override public boolean supports(Path file, Path workspaceRoot) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    @Override public VerificationResult verify(Path file, Path workspaceRoot, long mutationSequence) {
        return new VerificationResult(VerificationStatus.NOT_APPLICABLE, workspaceRoot.relativize(file), id(), "",
                "Baseline condition has no generic post-edit verifier", mutationSequence);
    }
}
