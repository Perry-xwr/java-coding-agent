package com.agent.benchmark.toolavailability;

import com.agent.agent.PlanningMode;
import com.agent.agent.VerificationRepairPolicy;
import com.agent.llm.LLMClient;
import com.agent.trajectory.TrajectoryJsonWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Paired DEV runner. The infrastructure gate completes before a provider factory is invoked. */
public final class ToolAvailabilityBenchmarkRunner {
    private final ToolAvailabilityInfrastructureGate gate;
    private final ToolAvailabilityRuntimeHarness harness;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolAvailabilityBenchmarkRunner() {
        this(new ToolAvailabilityInfrastructureGate(), new ToolAvailabilityRuntimeHarness());
    }

    public ToolAvailabilityBenchmarkRunner(ToolAvailabilityInfrastructureGate gate,
                                           ToolAvailabilityRuntimeHarness harness) {
        this.gate = Objects.requireNonNull(gate);
        this.harness = Objects.requireNonNull(harness);
    }

    public RunSummary run(List<ToolAvailabilityTask> tasks, ProviderFactory providers, Path fixturesRoot,
                          Path runsRoot, String runId, Path localMavenRepository,
                          PlanningMode planningMode, VerificationRepairPolicy repairPolicy) throws Exception {
        ToolAvailabilityInfrastructureGate.Result gateResult = gate.check(tasks, fixturesRoot, localMavenRepository);
        if (!gateResult.ready()) return new RunSummary(List.of(), List.of(), gateResult, false);
        Path outputRoot = runsRoot.toAbsolutePath().normalize();
        Path requiredOutputRoot = Path.of("benchmark-runs/tool-availability-v1").toAbsolutePath().normalize();
        if (!outputRoot.equals(requiredOutputRoot)) {
            throw new IOException("Tool-availability run artifacts must stay under benchmark-runs/tool-availability-v1");
        }
        Path output = outputRoot.resolve(runId).normalize();
        if (!output.startsWith(outputRoot) || Files.exists(output)) {
            throw new IOException("Refusing to reuse or escape tool-availability run directory");
        }
        Files.createDirectories(output);
        ToolAvailabilityInfrastructureStopRule stopRule = new ToolAvailabilityInfrastructureStopRule();
        List<ToolAvailabilityRuntimeHarness.RunResult> results = new ArrayList<>();
        List<ConditionInfrastructureFailure> failures = new ArrayList<>();
        boolean stopped = false;
        outer:
        for (ToolAvailabilityTask task : tasks) {
            for (ToolAvailabilityMode mode : ToolAvailabilityMode.values()) {
                try {
                    LLMClient provider = providers.create(task, mode);
                    ToolAvailabilityRuntimeHarness.RunResult result = harness.run(task, mode, provider,
                            fixturesRoot, outputRoot, runId, localMavenRepository, planningMode, repairPolicy);
                    results.add(result);
                    boolean infrastructure = result.metrics().infrastructureError();
                    if (infrastructure) failures.add(new ConditionInfrastructureFailure(task.id(), mode.name(),
                            result.metrics().infrastructureErrorCategory() == null
                                    ? "condition reported infrastructure failure"
                                    : result.metrics().infrastructureErrorCategory()));
                    if (stopRule.observeCondition(infrastructure)) { stopped = true; break outer; }
                } catch (Exception exception) {
                    String message = safe(exception);
                    failures.add(new ConditionInfrastructureFailure(task.id(), mode.name(), message));
                    if (stopRule.observeCondition(true)) { stopped = true; break outer; }
                }
            }
        }
        writeResults(output, results, failures, gateResult, stopped, stopRule.failures());
        return new RunSummary(results, failures, gateResult, stopped, stopRule.failures());
    }

    private void writeResults(Path output, List<ToolAvailabilityRuntimeHarness.RunResult> results,
                              List<ConditionInfrastructureFailure> failures,
                              ToolAvailabilityInfrastructureGate.Result gateResult, boolean stopped,
                              int infrastructureFailureCount) throws IOException {
        Path resultFile = output.resolve("condition-results.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
            for (ToolAvailabilityRuntimeHarness.RunResult result : results) {
                writer.write(mapper.writeValueAsString(result.metrics()));
                writer.newLine();
                new TrajectoryJsonWriter(output.resolve("trajectories")).write(result.agentResult().trajectory());
            }
        }
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("protocol", "tool-availability-v1");
        summary.put("conditionsCompleted", results.size());
        summary.put("infrastructureFailures", infrastructureFailureCount);
        summary.put("stoppedAtInfrastructureThreshold", stopped);
        summary.put("infrastructureGate", gateResult);
        summary.put("conditionMetrics", results.stream().map(ToolAvailabilityRuntimeHarness.RunResult::metrics).toList());
        summary.put("conditionFailures", failures);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run-summary.json").toFile(), summary);
    }

    private static String safe(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    @FunctionalInterface
    public interface ProviderFactory {
        LLMClient create(ToolAvailabilityTask task, ToolAvailabilityMode mode) throws Exception;
    }

    public record ConditionInfrastructureFailure(String taskId, String mode, String message) { }
    public record RunSummary(List<ToolAvailabilityRuntimeHarness.RunResult> results,
                             List<ConditionInfrastructureFailure> infrastructureFailures,
                             ToolAvailabilityInfrastructureGate.Result infrastructureGate,
                             boolean stoppedAtInfrastructureThreshold, int infrastructureFailureCount) {
        public RunSummary(List<ToolAvailabilityRuntimeHarness.RunResult> results,
                          List<ConditionInfrastructureFailure> failures,
                          ToolAvailabilityInfrastructureGate.Result gate, boolean stopped) {
            this(results, failures, gate, stopped, failures.size());
        }
        public RunSummary {
            results = List.copyOf(results);
            infrastructureFailures = List.copyOf(infrastructureFailures);
        }
    }
}
