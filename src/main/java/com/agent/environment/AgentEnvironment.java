package com.agent.environment;

import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolResult;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerificationCapability;

import java.util.List;
import java.nio.file.Path;

/** Execution boundary used by an Agent to discover and invoke tools. */
public interface AgentEnvironment {
    ToolResult execute(ToolCall toolCall);

    /** Returns the currently available model-facing tool definitions. */
    List<ToolDefinition> toolDefinitions();

    /** Unknown or unrestricted environments preserve existing registry behavior. */
    default ToolAvailabilityDecision toolAvailability(String toolName) {
        return ToolAvailabilityDecision.available(toolName,
                "No environment-specific restriction applies.", "UNKNOWN");
    }

    /** Whether project-level test verification is currently available. */
    default boolean projectTestVerificationAvailable() {
        return toolDefinitions().stream().anyMatch(definition ->
                "run_maven_test".equals(definition.name()));
    }

    default VerificationCapability verificationCapability(String relativePath) {
        return VerificationCapability.unknown();
    }

    default VerificationResult verifyPostEdit(String relativePath, long mutationSequence) {
        Path file;
        try {
            file = relativePath == null || relativePath.isBlank() ? Path.of(".") : Path.of(relativePath).normalize();
        } catch (RuntimeException exception) {
            file = Path.of(".");
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        boolean sourceCode = List.of(".py", ".js", ".mjs", ".cjs", ".c", ".cc", ".cpp", ".cxx", ".java")
                .stream().anyMatch(name::endsWith);
        return new VerificationResult(sourceCode ? VerificationStatus.UNAVAILABLE
                        : VerificationStatus.NOT_APPLICABLE,
                file, "none", "", sourceCode ? "This environment does not provide post-edit verification" : "",
                mutationSequence);
    }
}
