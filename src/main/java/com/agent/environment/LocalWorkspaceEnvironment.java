package com.agent.environment;

import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import com.agent.tool.WorkspacePathResolver;
import com.agent.tool.ToolResult;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.tool.execution.DefaultProcessRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Local filesystem-backed environment bound to one workspace and tool registry. */
public final class LocalWorkspaceEnvironment implements AgentEnvironment {
    private final Path workspaceRoot;
    private final ToolRegistry toolRegistry;
    private final PostEditVerificationService verificationService;

    public LocalWorkspaceEnvironment(Path workspaceRoot, ToolRegistry toolRegistry) {
        this(workspaceRoot, toolRegistry, BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
    }

    public LocalWorkspaceEnvironment(Path workspaceRoot, ToolRegistry toolRegistry, VerifierRegistry verifiers) {
        this.workspaceRoot = new WorkspacePathResolver(
                Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
        ).root();
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.verificationService = new PostEditVerificationService(this.workspaceRoot, verifiers);
        toolRegistry.workspaceRoot().ifPresent(registryRoot -> {
            if (!this.workspaceRoot.equals(registryRoot)) {
                throw new IllegalArgumentException("ToolRegistry workspace does not match environment workspace");
            }
        });
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public ToolResult execute(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        return toolRegistry.execute(toolCall.name(), toolCall.arguments());
    }

    @Override
    public List<ToolDefinition> toolDefinitions() {
        return toolRegistry.definitions();
    }

    @Override
    public VerificationResult verifyPostEdit(String relativePath, long mutationSequence) {
        return verificationService.verify(relativePath, mutationSequence);
    }
}
