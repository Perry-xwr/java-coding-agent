package com.agent.benchmark.toolavailability;

import com.agent.benchmark.FixtureWorkspaceManager;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.RunMavenTestTool;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Offline, deterministic validation of all initial fixtures before any provider can be created. */
public final class ToolAvailabilityInfrastructureGate {
    private final ProcessRunner processRunner;

    public ToolAvailabilityInfrastructureGate() { this(new DefaultProcessRunner()); }

    ToolAvailabilityInfrastructureGate(ProcessRunner processRunner) {
        this.processRunner = Objects.requireNonNull(processRunner);
    }

    public Result check(List<ToolAvailabilityTask> tasks, Path fixturesRoot, Path localMavenRepository)
            throws IOException {
        Path temporaryRoot = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(temporaryRoot);
        Path tempRoot = Files.createTempDirectory(temporaryRoot, "tool-availability-fixture-gate-");
        List<FixtureCheck> checks = new ArrayList<>();
        try {
            for (ToolAvailabilityTask task : tasks) {
                Path fixture = fixturesRoot.toAbsolutePath().normalize().resolve(task.fixture()).normalize();
                Path workspace = tempRoot.resolve(task.id()).normalize();
                if (!fixture.startsWith(fixturesRoot.toAbsolutePath().normalize()) || !Files.isDirectory(fixture)) {
                    checks.add(new FixtureCheck(task.id(), false, "fixture missing or outside fixture root"));
                    continue;
                }
                try {
                    Files.createDirectories(workspace);
                    FixtureWorkspaceManager.copyTree(fixture, workspace);
                    String failure = validate(task, workspace, localMavenRepository);
                    checks.add(new FixtureCheck(task.id(), failure == null, failure));
                } catch (Exception exception) {
                    checks.add(new FixtureCheck(task.id(), false, safeMessage(exception)));
                }
            }
        } finally {
            ToolAvailabilityWorkspace.deleteTree(tempRoot);
        }
        return new Result(checks.size() == 12 && checks.stream().allMatch(FixtureCheck::valid), checks);
    }

    private String validate(ToolAvailabilityTask task, Path workspace, Path repository) throws IOException {
        for (String relative : task.expectedFileContains().keySet()) {
            Path source = workspace.resolve(relative).normalize();
            if (!source.startsWith(workspace) || !Files.isRegularFile(source)) return "initial target missing: " + relative;
        }
        if (task.workspaceClass() == ToolAvailabilityWorkspaceClass.MAVEN_JAVA) {
            ProcessRunner offline = (command, cwd, timeout, limit) -> {
                List<String> localCommand = new ArrayList<>(command);
                if (!localCommand.isEmpty()) localCommand.add(1, "-o");
                return processRunner.run(List.copyOf(localCommand), cwd, timeout, limit);
            };
            ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, offline, repository);
            ToolResult result = registry.execute("run_maven_test", "{}");
            return result.success() ? null : "offline Maven test failed: " + safeMessage(result.errorMessage(), result.output());
        }
        PostEditVerificationService verifier = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(processRunner));
        for (String relative : task.expectedFileContains().keySet()) {
            VerificationResult result = verifier.verify(relative, 0);
            if (result.status() != VerificationStatus.PASS) {
                return "initial syntax validation " + result.status() + " for " + relative + ": "
                        + safeMessage(result.unavailableReason(), result.diagnosticSummary());
            }
        }
        return null;
    }

    private static String safeMessage(Exception exception) {
        return safeMessage(exception.getClass().getSimpleName(), exception.getMessage());
    }

    private static String safeMessage(String first, String second) {
        String value = (first == null ? "" : first) + " " + (second == null ? "" : second);
        return value.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .strip();
    }

    public record FixtureCheck(String taskId, boolean valid, String detail) { }
    public record Result(boolean ready, List<FixtureCheck> checks) {
        public Result { checks = List.copyOf(checks); }
    }
}
