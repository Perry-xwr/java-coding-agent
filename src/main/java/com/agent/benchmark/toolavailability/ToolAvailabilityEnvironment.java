package com.agent.benchmark.toolavailability;

import com.agent.environment.AgentEnvironment;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.ToolAvailabilityDecision;
import com.agent.environment.verification.VerificationCapability;
import com.agent.environment.verification.VerificationResult;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Benchmark-only mode adapter. Legacy executes the actual registry catalog without environment filtering. */
final class ToolAvailabilityEnvironment implements AgentEnvironment {
    private final ToolAvailabilityMode mode;
    private final LocalWorkspaceEnvironment local;
    private final ToolRegistry registry;
    private final ToolAvailabilityObservations observations;
    private final MavenProcessCounter processCounter;

    ToolAvailabilityEnvironment(ToolAvailabilityMode mode, LocalWorkspaceEnvironment local,
                                ToolRegistry registry, ToolAvailabilityObservations observations,
                                MavenProcessCounter processCounter) {
        this.mode = Objects.requireNonNull(mode);
        this.local = Objects.requireNonNull(local);
        this.registry = Objects.requireNonNull(registry);
        this.observations = Objects.requireNonNull(observations);
        this.processCounter = Objects.requireNonNull(processCounter);
    }

    @Override public ToolResult execute(ToolCall call) {
        int before = processCounter.count();
        ToolAvailabilityDecision snapshotAtExecution = availabilityDecision("run_maven_test");
        if (!"run_maven_test".equals(call.name())) {
            observations.observe("TOOL_INVOCATION", snapshotAtExecution);
        }
        ToolResult result = mode == ToolAvailabilityMode.LEGACY_ALL_TOOLS
                ? registry.execute(call.name(), call.arguments())
                : local.execute(call);
        if ("run_maven_test".equals(call.name())) {
            observations.afterExecution("MAVEN".equals(snapshotAtExecution.capabilitySnapshot()),
                    before, processCounter.count());
        }
        observations.observe("TOOL_RESULT", availabilityDecision("run_maven_test"));
        return result;
    }

    @Override public List<ToolDefinition> toolDefinitions() {
        return mode == ToolAvailabilityMode.LEGACY_ALL_TOOLS ? registry.definitions() : local.toolDefinitions();
    }

    @Override public ToolAvailabilityDecision toolAvailability(String name) {
        ToolAvailabilityDecision decision = availabilityDecision(name);
        if ("run_maven_test".equals(name)) observations.beforeInvocation(decision);
        return decision;
    }

    ToolAvailabilityDecision availabilityDecision(String name) {
        if (mode == ToolAvailabilityMode.ENVIRONMENT_AWARE) return local.toolAvailability(name);
        boolean pom = hasSafeRootPom();
        return ToolAvailabilityDecision.available(name, "Legacy all-tools condition exposes the complete registry.",
                pom ? "MAVEN" : "STANDALONE");
    }

    void providerTurn(List<ToolDefinition> definitions) {
        observations.providerTurn(definitions, availabilityDecision("run_maven_test"));
    }

    @Override public boolean projectTestVerificationAvailable() {
        return mode == ToolAvailabilityMode.LEGACY_ALL_TOOLS || local.projectTestVerificationAvailable();
    }

    @Override public VerificationCapability verificationCapability(String path) {
        return local.verificationCapability(path);
    }

    @Override public VerificationResult verifyPostEdit(String path, long sequence) {
        return local.verifyPostEdit(path, sequence);
    }

    private boolean hasSafeRootPom() {
        Path pom = local.workspaceRoot().resolve("pom.xml");
        return Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS);
    }

    interface MavenProcessCounter { int count(); }
}
