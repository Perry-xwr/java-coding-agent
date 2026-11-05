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
import com.agent.environment.verification.VerificationCapability;
import com.agent.tool.execution.DefaultProcessRunner;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Objects;
import com.agent.agent.TaskChangeReview;
import com.agent.agent.TaskDiffCollector;
import com.agent.llm.ToolCall;

/** Local filesystem-backed environment bound to one workspace and tool registry. */
public final class LocalWorkspaceEnvironment implements AgentEnvironment {
    private final Path workspaceRoot;
    private final ToolRegistry toolRegistry;
    private final PostEditVerificationService verificationService;
    private TaskDiffCollector changeCollector;

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

    @Override
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public ToolResult execute(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        ToolAvailabilityDecision availability = toolAvailability(toolCall.name());
        if (!availability.available()) {
            return availability.rejectionResult();
        }
        TaskDiffCollector collector;
        synchronized (this) { collector = changeCollector; }
        TaskDiffCollector.Snapshot before = collector == null
                ? null : collector.before(toolCall);
        ToolResult result = toolRegistry.execute(toolCall.name(), toolCall.arguments());
        if (collector != null && before != null) collector.after(before, toolCall, result);
        return result;
    }

    @Override
    public synchronized void beginTask(String runId) {
        changeCollector = new TaskDiffCollector(workspaceRoot);
    }

    @Override
    public synchronized TaskChangeReview finishTask(String runId, java.util.Map<String, String> verificationByPath) {
        TaskDiffCollector collector = changeCollector;
        changeCollector = null;
        return collector == null ? TaskChangeReview.empty(runId) : collector.finish(runId, verificationByPath);
    }

    @Override
    public List<ToolDefinition> toolDefinitions() {
        return toolRegistry.definitions().stream()
                .filter(definition -> toolAvailability(definition.name()).available())
                .toList();
    }

    @Override
    public ToolAvailabilityDecision toolAvailability(String toolName) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (!"run_maven_test".equals(toolName)) {
            return ToolAvailabilityDecision.available(toolName,
                    "This tool is not restricted by the local Maven workspace policy.",
                    workspaceHasSafeRootPom() ? "MAVEN" : "STANDALONE");
        }
        if (workspaceHasSafeRootPom()) {
            return ToolAvailabilityDecision.available(toolName,
                    "A regular root pom.xml was detected.", "MAVEN");
        }
        return ToolAvailabilityDecision.unavailable(toolName,
                "no safe root pom.xml was detected.", "STANDALONE");
    }

    @Override
    public boolean projectTestVerificationAvailable() {
        return toolAvailability("run_maven_test").available();
    }

    private boolean workspaceHasSafeRootPom() {
        Path pom = workspaceRoot.resolve("pom.xml").normalize();
        return pom.startsWith(workspaceRoot)
                && Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public VerificationResult verifyPostEdit(String relativePath, long mutationSequence) {
        return verificationService.verify(relativePath, mutationSequence);
    }

    @Override
    public VerificationCapability verificationCapability(String relativePath) {
        return VerificationCapability.forFile(relativePath,
                Files.isRegularFile(workspaceRoot.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS));
    }
}
