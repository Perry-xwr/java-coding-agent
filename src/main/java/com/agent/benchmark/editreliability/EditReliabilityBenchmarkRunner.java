package com.agent.benchmark.editreliability;

import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Paired runner. Provider creation is mandatory and explicitly injected; there is no live-provider default. */
public final class EditReliabilityBenchmarkRunner {
    private final ConditionRunner conditions;

    public EditReliabilityBenchmarkRunner() {
        EditReliabilityRuntimeHarness harness = new EditReliabilityRuntimeHarness();
        this.conditions = harness::run;
    }

    public EditReliabilityBenchmarkRunner(ConditionRunner conditions) {
        this.conditions = Objects.requireNonNull(conditions);
    }

    public List<EditReliabilityRuntimeHarness.RunResult> runAll(
            EditReliabilityManifest manifest,
            ProviderFactory providers,
            Path fixturesRoot,
            Path runsRoot,
            String runId,
            Path localMavenRepository
    ) throws Exception {
        return runRound(manifest, providers, fixturesRoot, runsRoot, runId, localMavenRepository).results();
    }

    /** Runs one ordered round and stops after three condition-level infrastructure failures. */
    public RoundResult runRound(
            EditReliabilityManifest manifest,
            ProviderFactory providers,
            Path fixturesRoot,
            Path runsRoot,
            String runId,
            Path localMavenRepository
    ) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(providers, "provider factory must be explicitly supplied");
        List<EditReliabilityRuntimeHarness.RunResult> results = new ArrayList<>();
        List<ConditionInfrastructureFailure> failures = new ArrayList<>();
        EditReliabilityInfrastructureStopRule stopRule = new EditReliabilityInfrastructureStopRule();
        boolean stopped = false;
        for (EditReliabilityTask task : manifest.tasks()) {
            for (EditReliabilityMode mode : EditReliabilityMode.values()) {
                boolean conditionInfrastructureError = false;
                try {
                    LLMClient provider = Objects.requireNonNull(providers.create(task, mode),
                            "provider factory returned null");
                    EditReliabilityRuntimeHarness.RunResult result = conditions.run(task, mode, provider,
                            fixturesRoot, runsRoot, runId, localMavenRepository);
                    results.add(result);
                    conditionInfrastructureError = result.evaluation().infrastructureError();
                    if (conditionInfrastructureError) {
                        failures.add(new ConditionInfrastructureFailure(task.id(), mode,
                                String.join(";", result.evaluation().failures().stream()
                                        .filter(value -> value.contains("VERIFIER_UNAVAILABLE"))
                                        .toList())));
                    }
                } catch (Exception exception) {
                    conditionInfrastructureError = true;
                    failures.add(new ConditionInfrastructureFailure(task.id(), mode,
                            exception.getClass().getSimpleName()));
                }
                if (stopRule.observeCondition(conditionInfrastructureError)) {
                    stopped = true;
                    return new RoundResult(results, failures, stopRule.infrastructureFailures(), stopped);
                }
            }
        }
        return new RoundResult(results, failures, stopRule.infrastructureFailures(), stopped);
    }

    public record RoundResult(List<EditReliabilityRuntimeHarness.RunResult> results,
                              List<ConditionInfrastructureFailure> infrastructureFailures,
                              int infrastructureFailureCount, boolean stoppedAtInfrastructureThreshold) {
        public RoundResult {
            results = List.copyOf(results);
            infrastructureFailures = List.copyOf(infrastructureFailures);
        }
    }

    public record ConditionInfrastructureFailure(String taskId, EditReliabilityMode mode, String category) { }

    @FunctionalInterface
    public interface ProviderFactory {
        LLMClient create(EditReliabilityTask task, EditReliabilityMode mode);
    }

    @FunctionalInterface
    public interface ConditionRunner {
        EditReliabilityRuntimeHarness.RunResult run(EditReliabilityTask task, EditReliabilityMode mode,
                LLMClient provider, Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
                throws Exception;
    }
}
