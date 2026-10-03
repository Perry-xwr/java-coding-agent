package com.agent.benchmark.planning;

import com.agent.agent.Agent;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.PlanningMode;
import com.agent.llm.LLMClient;
import com.agent.trajectory.TrajectoryJsonWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Provider-injected round runner with durable, per-condition metadata and trajectories. */
public final class PlanningBenchmarkRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PlanningRuntimeHarness runtime = new PlanningRuntimeHarness();

    /** Executes manifest order and the requested fixed mode order; every mode/task gets its own cap. */
    public List<PlanningRunRecord> runRound(
            List<PlanningBenchmarkTask> tasks,
            int round,
            ExecutionOrder executionOrder,
            String runtimeCommit,
            String benchmarkCommit,
            String providerName,
            String model,
            Path fixturesRoot,
            Path outputRoot,
            String runId,
            Path localMavenRepository,
            ProviderFactory providers
    ) throws Exception {
        if (round < 1 || round > 2) throw new IllegalArgumentException("round must be 1 or 2");
        requireText(runtimeCommit, "runtimeCommit");
        requireText(benchmarkCommit, "benchmarkCommit");
        requireText(providerName, "providerName");
        requireText(model, "model");
        Objects.requireNonNull(executionOrder, "executionOrder must not be null");
        Objects.requireNonNull(providers, "providers must not be null");
        Path root = outputRoot.toAbsolutePath().normalize();
        Path roundDirectory = root.resolve(runId).resolve("round-" + round).normalize();
        if (!roundDirectory.startsWith(root)) throw new IllegalArgumentException("run output escaped outputRoot");
        if (Files.exists(roundDirectory)) throw new IOException("round output already exists; refusing to overwrite it");
        Files.createDirectories(roundDirectory);
        Path recordsPath = roundDirectory.resolve("task-results.jsonl");

        List<PlanningRunRecord> records = new ArrayList<>();
        for (PlanningBenchmarkTask task : tasks) {
            List<PlanningMode> modes = executionOrder == ExecutionOrder.REACTIVE_FIRST
                    ? List.of(PlanningMode.REACTIVE, PlanningMode.PLAN_EXECUTE)
                    : List.of(PlanningMode.PLAN_EXECUTE, PlanningMode.REACTIVE);
            for (PlanningMode mode : modes) {
                String timestamp = Instant.now().toString();
                long started = System.nanoTime();
                PlanningRuntimeHarness.RunResult result = null;
                String infrastructureError = null;
                try {
                    result = runtime.run(task, mode, providers.create(task), fixturesRoot,
                            roundDirectory.resolve("workspaces"), "session", localMavenRepository);
                    infrastructureError = result.infrastructureError();
                } catch (Exception exception) {
                    infrastructureError = exception.getClass().getSimpleName() + ": "
                            + safeError(exception.getMessage());
                }
                long duration = (System.nanoTime() - started) / 1_000_000L;
                PlanningRunRecord record = record(task, mode, round, executionOrder, runtimeCommit,
                        benchmarkCommit, providerName, model, timestamp, duration, result, infrastructureError);
                append(recordsPath, record);
                if (result != null) writeTrajectory(result, roundDirectory);
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    private static PlanningRunRecord record(
            PlanningBenchmarkTask task, PlanningMode mode, int round, ExecutionOrder order,
            String runtimeCommit, String benchmarkCommit, String provider, String model,
            String timestamp, long durationMs, PlanningRuntimeHarness.RunResult result, String error) {
        PlanningBenchmarkMetrics metrics = result == null ? null : result.metrics();
        boolean cap = error != null && error.contains("BUDGET_CAP_REACHED")
                || result != null && result.trajectory().steps().stream()
                .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                .anyMatch(message -> message.contains("BUDGET_CAP_REACHED"));
        return new PlanningRunRecord(
                "planning-v1", 1, runtimeCommit, benchmarkCommit, provider, model, mode.name(), task.id(),
                round, order.name(), task.maxProviderRequests(), Agent.MAX_ITERATIONS, timestamp,
                result != null && result.evaluation().taskOutcomeSuccess(),
                metrics == null ? 0 : metrics.providerRequests(), metrics == null ? 0 : metrics.toolSteps(),
                metrics == null ? 0 : metrics.mutationCount(), metrics == null ? 0 : metrics.targetDrift(),
                metrics == null ? 0 : metrics.typedFailures(),
                metrics == null ? 0 : metrics.repeatedIdenticalFailure(),
                metrics == null ? 0 : metrics.explicitReads(), metrics == null ? 0 : metrics.verificationAttempts(),
                metrics == null ? 0 : metrics.replanCount(), metrics == null ? 0 : metrics.planFallback(),
                metrics == null ? 0 : metrics.planStepsTotal(), metrics == null ? 0 : metrics.planStepsCompleted(),
                metrics == null ? "NONE" : metrics.planOutcome(),
                metrics != null && metrics.conversationalCompletion(), error, cap, durationMs);
    }

    private static void append(Path recordsPath, PlanningRunRecord record) throws Exception {
        Files.writeString(recordsPath, MAPPER.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void writeTrajectory(PlanningRuntimeHarness.RunResult result, Path roundDirectory)
            throws Exception {
        Path directory = roundDirectory.resolve("trajectories").resolve(result.mode().name())
                .resolve(result.taskId());
        new TrajectoryJsonWriter(directory).write(result.trajectory());
    }

    private static String safeError(String message) {
        if (message == null) return "(no details)";
        String safe = message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public enum ExecutionOrder { REACTIVE_FIRST, PLAN_FIRST }

    @FunctionalInterface
    public interface ProviderFactory {
        /** Must return a fresh client configured for the same provider/model for both modes. */
        LLMClient create(PlanningBenchmarkTask task);
    }

    public record PlanningRunRecord(
            String protocol, int schemaVersion, String runtimeCommit, String benchmarkCommit,
            String provider, String model, String mode, String taskId, int round, String executionOrder,
            int requestBudget, int runtimeMaxIterations, String timestamp, boolean success,
            int providerRequests, int toolSteps, int mutationCount, int targetDrift, int typedFailures,
            int repeatedIdenticalFailure, int explicitReads, int verificationAttempts, int replanCount,
            int planFallback, int planStepsTotal, int planStepsCompleted, String planOutcome,
            boolean conversationalCompletion, String infrastructureError, boolean requestCapReached,
            long durationMs) { }
}
