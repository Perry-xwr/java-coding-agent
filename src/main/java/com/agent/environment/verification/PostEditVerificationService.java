package com.agent.environment.verification;

import com.agent.tool.WorkspacePathResolver;
import com.agent.tool.WorkspaceViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class PostEditVerificationService {
    public static final int MAX_DIAGNOSTIC_CHARS = 4_000;

    private final Path workspaceRoot;
    private final WorkspacePathResolver pathResolver;
    private final VerifierRegistry registry;

    public PostEditVerificationService(Path workspaceRoot, VerifierRegistry registry) {
        this.pathResolver = new WorkspacePathResolver(Objects.requireNonNull(workspaceRoot));
        this.workspaceRoot = pathResolver.root();
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public VerificationResult verify(String relativePath, long mutationSequence) {
        Path displayPath = Path.of(".");
        try {
            if (relativePath == null || relativePath.isBlank()) {
                return result(VerificationStatus.UNAVAILABLE, Path.of("."), "path", "",
                        "Mutation result did not include a path", mutationSequence);
            }
            Path requested = Path.of(relativePath);
            if (requested.isAbsolute()) {
                return result(VerificationStatus.UNAVAILABLE, requested, "path", "",
                        "Absolute paths are not accepted for post-edit verification", mutationSequence);
            }
            displayPath = requested.normalize();
            if (!workspaceRoot.resolve(displayPath).normalize().startsWith(workspaceRoot)) {
                return result(VerificationStatus.UNAVAILABLE, displayPath, "path", "",
                        "Path is outside the workspace", mutationSequence);
            }
            Path target = pathResolver.resolveExisting(relativePath);
            if (!Files.isRegularFile(target)) {
                return result(VerificationStatus.UNAVAILABLE, displayPath, "path", "",
                        "Target is not a regular file", mutationSequence);
            }
            CodeVerifier verifier = registry.verifierFor(target, workspaceRoot).orElse(null);
            if (verifier == null) {
                return result(VerificationStatus.NOT_APPLICABLE, displayPath, "none", "",
                        "No verifier is registered for this file type", mutationSequence);
            }
            VerificationResult result = verifier.verify(target, workspaceRoot, mutationSequence);
            return new VerificationResult(result.status(), displayPath, result.verifierId(),
                    bound(result.diagnosticSummary()), bound(result.unavailableReason()), mutationSequence);
        } catch (WorkspaceViolationException exception) {
            return result(VerificationStatus.UNAVAILABLE, displayPath, "path", "",
                    "Workspace containment check failed", mutationSequence);
        } catch (IOException | RuntimeException exception) {
            return result(VerificationStatus.UNAVAILABLE, displayPath, "path", "",
                    "Unable to safely resolve file for verification", mutationSequence);
        }
    }

    private static VerificationResult result(VerificationStatus status, Path path, String verifier,
                                             String diagnostic, String unavailable, long sequence) {
        return new VerificationResult(status, path, verifier, bound(diagnostic), bound(unavailable), sequence);
    }

    private static String bound(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .replaceAll("(?i)(api[_-]?key|token|password|authorization)(\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\s,;]+", "$1$2[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
                .replaceAll("(?i)\\b(?:sk|glm)-[A-Za-z0-9_-]{16,}\\b", "[REDACTED]")
                .replaceAll("(?m)\\s+$", "").strip();
        normalized = normalized.lines().distinct().limit(40)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        return normalized.length() <= MAX_DIAGNOSTIC_CHARS
                ? normalized : normalized.substring(0, MAX_DIAGNOSTIC_CHARS) + "…[truncated]";
    }
}
