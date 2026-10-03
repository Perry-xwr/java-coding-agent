package com.agent.environment.verification;

import java.nio.file.Path;

public interface CodeVerifier {
    String id();

    boolean supports(Path file, Path workspaceRoot);

    VerificationResult verify(Path file, Path workspaceRoot, long mutationSequence);
}
