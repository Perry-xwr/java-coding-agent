package com.agent.tool;

import com.agent.execution.secure.ApprovalDecision;
import com.agent.execution.secure.ExecutionApprovalPrompt;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.UUID;

/** Test-only registry factories that explicitly simulate a one-shot approval decision. */
public final class TestingToolRegistries {
    private TestingToolRegistries() { }

    public static ToolRegistry withCodingTools(Path root, ProcessRunner runner, Path localRepository) {
        return ToolRegistry.withCodingToolsForTesting(root, runner, localRepository, TestingToolRegistries::approve);
    }

    public static ToolRegistry withActionOrientedCodingTools(Path root, ProcessRunner runner, Path localRepository) {
        return ToolRegistry.withActionOrientedCodingToolsForTesting(root, runner, localRepository,
                TestingToolRegistries::approve);
    }

    public static ToolRegistry withCliCodingTools(Path root, ProcessRunner runner, Path localRepository) {
        return ToolRegistry.withCliCodingToolsForTesting(root, runner, localRepository,
                TestingToolRegistries::approve);
    }

    public static ToolResult runMavenTest(Path root, ProcessRunner runner, Path localRepository, String arguments) {
        return new RunMavenTestTool(root, runner, localRepository, new ObjectMapper(),
                RunMavenTestTool.DEFAULT_TIMEOUT, RunMavenTestTool.DEFAULT_MAX_OUTPUT_BYTES,
                TestingToolRegistries::approve).execute(arguments);
    }

    public static ToolRegistry withPreciseEditCodingTools(Path root, ProcessRunner runner, Path localRepository) {
        return ToolRegistry.withPreciseEditCodingToolsForTesting(root, runner, localRepository,
                TestingToolRegistries::approve);
    }

    private static ApprovalDecision approve(ExecutionApprovalPrompt prompt) {
        return new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(),
                UUID.randomUUID().toString());
    }
}
