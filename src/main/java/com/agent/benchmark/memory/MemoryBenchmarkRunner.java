package com.agent.benchmark.memory;

import com.agent.WorkingMemoryMode;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.llm.LLMClient;
import com.agent.trajectory.TrajectoryJsonWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs isolated memory-v1 tasks under one explicitly selected ablation condition. */
public final class MemoryBenchmarkRunner {
    private final MemoryFixtureWorkspace workspaces;
    private final MemoryBenchmarkEvaluator evaluator = new MemoryBenchmarkEvaluator();
    private final Path fixturesRoot;

    public MemoryBenchmarkRunner(Path fixturesRoot) {
        this.fixturesRoot = fixturesRoot.toAbsolutePath().normalize();
        this.workspaces = new MemoryFixtureWorkspace(this.fixturesRoot);
    }

    public List<Result> run(
            String runId,
            List<MemoryBenchmarkTask> tasks,
            WorkingMemoryMode mode,
            Path runsRoot,
            ProviderFactory providers
    ) throws Exception {
        List<Result> results = new ArrayList<>();
        for (MemoryBenchmarkTask task : tasks) {
            long started = System.nanoTime();
            Path workspace = workspaces.reset(task, runsRoot.resolve("workspaces"), runId, mode.name());
            ProviderBudget budget = new ProviderBudget(task.maxProviderRequests());
            BudgetedLlmClient client = new BudgetedLlmClient(
                    providers.create(task, mode), budget, task.maxProviderRequests());
            MemoryRuntimeHarness runtime = new MemoryRuntimeHarness(client, workspace, mode);
            List<MemoryRuntimeHarness.TurnResult> turns = runtime.run(task);
            MemoryBenchmarkMetrics metrics = MemoryBenchmarkMetrics.from(
                    turns, task.allowedMutationTargets(), budget.used());
            Path fixture = fixturesRoot.resolve(task.fixture()).normalize();
            MemoryBenchmarkEvaluator.Evaluation evaluation = evaluator.evaluate(
                    task, workspace, fixture, turns, metrics);

            Path trajectoryDirectory = runsRoot.resolve(runId).resolve("trajectories")
                    .resolve(mode.name()).resolve(task.id());
            TrajectoryJsonWriter writer = new TrajectoryJsonWriter(trajectoryDirectory);
            for (MemoryRuntimeHarness.TurnResult turn : turns) {
                writer.write(turn.trajectory());
            }
            String failureType = evaluation.passed() ? "NONE" : "EVALUATION_FAILURE";
            results.add(new Result(task.id(), mode, evaluation.passed(), metrics,
                    failureType, trajectoryDirectory.toString(), evaluation.failures(),
                    (System.nanoTime() - started) / 1_000_000L));
        }
        return List.copyOf(results);
    }

    public Summary summarize(WorkingMemoryMode mode, List<Result> results) {
        long successes = results.stream().filter(Result::success).count();
        return new Summary(
                mode,
                results.size(),
                successes,
                results.isEmpty() ? 0.0 : (double) successes / results.size(),
                average(results, result -> result.metrics().providerRequests()),
                average(results, result -> result.metrics().toolSteps()),
                sum(results, result -> result.metrics().targetDriftCount()),
                sum(results, result -> result.metrics().repeatedFailureCount()),
                sum(results, result -> result.metrics().redundantReadCount())
        );
    }

    private static double average(List<Result> values, java.util.function.ToIntFunction<Result> extractor) {
        return values.stream().mapToInt(extractor).average().orElse(0.0);
    }

    private static int sum(List<Result> values, java.util.function.ToIntFunction<Result> extractor) {
        return values.stream().mapToInt(extractor).sum();
    }

    @FunctionalInterface
    public interface ProviderFactory {
        LLMClient create(MemoryBenchmarkTask task, WorkingMemoryMode mode);
    }

    public record Result(
            String taskId,
            WorkingMemoryMode memoryMode,
            boolean success,
            MemoryBenchmarkMetrics metrics,
            String failureType,
            String trajectoryPath,
            List<String> failedCriteria,
            long durationMs
    ) {
        public Result {
            failedCriteria = List.copyOf(failedCriteria);
        }
    }

    public record Summary(
            WorkingMemoryMode memoryMode,
            int taskCount,
            long successCount,
            double successRate,
            double averageProviderRequests,
            double averageToolSteps,
            int totalTargetDrift,
            int totalRepeatedFailures,
            int totalRedundantReads
    ) {
    }
}
