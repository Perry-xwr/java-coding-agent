package com.agent.benchmark.repairreliability;

import com.agent.llm.LLMClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Paired DEV runner. Provider creation is mandatory and infrastructure failures stop a round at three. */
public final class RepairReliabilityBenchmarkRunner {
    private final ConditionRunner conditions;

    public RepairReliabilityBenchmarkRunner() {
        this(new RepairReliabilityRuntimeHarness()::run);
    }
    public RepairReliabilityBenchmarkRunner(RepairReliabilityRuntimeHarness harness) {
        this(Objects.requireNonNull(harness)::run);
    }
    public RepairReliabilityBenchmarkRunner(ConditionRunner conditions) {
        this.conditions = Objects.requireNonNull(conditions);
    }

    /** Legacy in-memory paired execution; callers must explicitly supply providers. */
    public List<RepairReliabilityRuntimeHarness.RunResult> runAll(
            RepairReliabilityManifest manifest, ProviderFactory providers, Path fixturesRoot,
            Path runsRoot, String runId, Path localMavenRepository) throws Exception {
        RoundResult result = runRound(manifest, providers, fixturesRoot, runsRoot, runId,
                localMavenRepository, null, RepairReliabilityRoundOrder.VERIFICATION_THEN_GUIDED);
        return result.results();
    }

    public RoundResult runRound(
            RepairReliabilityManifest manifest, ProviderFactory providers, Path fixturesRoot,
            Path workspacesRoot, String round, Path localMavenRepository,
            Path resultsDirectory, RepairReliabilityRoundOrder order) throws IOException {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(providers, "provider factory must be explicitly supplied");
        Objects.requireNonNull(order, "round order must not be null");
        RepairReliabilityResultWriter writer = resultsDirectory == null ? null
                : new RepairReliabilityResultWriter(resultsDirectory, workspacesRoot.resolve(round));
        List<RepairReliabilityRuntimeHarness.RunResult> results = new ArrayList<>();
        List<ConditionInfrastructureFailure> failures = new ArrayList<>();
        boolean stopped = false;
        for (RepairReliabilityTask task : manifest.tasks()) {
            for (RepairReliabilityMode mode : order.modes()) {
                try {
                    LLMClient provider = Objects.requireNonNull(providers.create(task, mode),
                            "provider factory returned null");
                    RepairReliabilityRuntimeHarness.RunResult result = conditions.run(task, mode, provider,
                            fixturesRoot, workspacesRoot, round, localMavenRepository);
                    results.add(result);
                    if (writer != null) writer.write(round, order, result);
                    if (result.metrics().infrastructureError()) {
                        failures.add(new ConditionInfrastructureFailure(task.id(), mode,
                                result.metrics().infrastructureErrorCategory()));
                    }
                } catch (Exception exception) {
                    String category = category(exception);
                    failures.add(new ConditionInfrastructureFailure(task.id(), mode, category));
                    if (writer != null) writer.writeInfrastructureFailure(round, order, task, mode, category);
                }
                if (failures.size() >= 3) {
                    stopped = true;
                    if (writer != null) writer.writeRoundSummary(round, order, results, failures, true);
                    return new RoundResult(round, order, results, failures, true);
                }
            }
        }
        if (writer != null) writer.writeRoundSummary(round, order, results, failures, false);
        return new RoundResult(round, order, results, failures, stopped);
    }

    private static String category(Exception exception) {
        String name = exception.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        String message = Objects.toString(exception.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
        if (name.contains("socket") || name.contains("connect") || message.contains("timeout")
                || message.contains("connection") || message.contains("proxy")) return "PROVIDER_TRANSPORT";
        if (message.contains("verif")) return "VERIFIER_UNAVAILABLE";
        if (message.contains("workspace") || message.contains("fixture")) return "WORKSPACE_RUNTIME";
        return "RUNNER_FAILURE";
    }

    public record RoundResult(String round, RepairReliabilityRoundOrder order,
                              List<RepairReliabilityRuntimeHarness.RunResult> results,
                              List<ConditionInfrastructureFailure> infrastructureFailures,
                              boolean stoppedAtInfrastructureThreshold) {
        public RoundResult {
            results = List.copyOf(results);
            infrastructureFailures = List.copyOf(infrastructureFailures);
        }
    }

    public record ConditionInfrastructureFailure(String taskId, RepairReliabilityMode mode, String category) { }

    @FunctionalInterface
    public interface ProviderFactory { LLMClient create(RepairReliabilityTask task, RepairReliabilityMode mode); }

    @FunctionalInterface
    public interface ConditionRunner {
        RepairReliabilityRuntimeHarness.RunResult run(RepairReliabilityTask task, RepairReliabilityMode mode,
                LLMClient provider, Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
                throws Exception;
    }
}
