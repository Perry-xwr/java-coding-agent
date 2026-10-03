package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.Agent;
import com.agent.agent.PlanningMode;
import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Independent 3-mode DEV runner. Providers are always supplied explicitly by the caller. */
public final class AdaptivePlanningBenchmarkRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<PlanningMode> MODES = List.of(
            PlanningMode.REACTIVE, PlanningMode.PLAN_EXECUTE, PlanningMode.ADAPTIVE);
    private final AdaptivePlanningRuntimeHarness runtime = new AdaptivePlanningRuntimeHarness();

    public List<RunRecord> runDev(List<AdaptivePlanningTask> tasks, String runtimeCommit,
                                  String benchmarkCommit, String providerName, String model,
                                  Path fixturesRoot, Path outputRoot, String runId,
                                  Path localMavenRepository, ProviderFactory providers) throws IOException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        requireText(runtimeCommit, "runtimeCommit");
        requireText(benchmarkCommit, "benchmarkCommit");
        requireText(providerName, "providerName");
        requireText(model, "model");
        Objects.requireNonNull(providers, "providers must not be null");
        if (tasks.size() != 9) throw new IllegalArgumentException("DEV run requires exactly 9 tasks");

        Path output = outputRoot.toAbsolutePath().normalize().resolve(runId).normalize();
        Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
        if (!output.startsWith(normalizedOutputRoot)) throw new IOException("runId escaped output root");
        if (Files.exists(output)) throw new IOException("refusing to overwrite existing benchmark run");
        Files.createDirectories(output.resolve("trajectories"));
        Path resultsPath = output.resolve("task-results.jsonl");
        List<RunRecord> records = new ArrayList<>();

        for (AdaptivePlanningTask task : tasks) {
            for (PlanningMode mode : MODES) {
                long started = System.nanoTime();
                String timestamp = Instant.now().toString();
                AdaptivePlanningRuntimeHarness.RunResult result = null;
                String error = null;
                try {
                    result = runtime.run(task, mode, Objects.requireNonNull(providers.create(task),
                                    "provider factory returned null"), fixturesRoot,
                            output.resolve("workspaces"), "conditions", localMavenRepository);
                    error = result.infrastructureError();
                } catch (Exception exception) {
                    error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                }
                long durationMs = (System.nanoTime() - started) / 1_000_000L;
                String trajectoryPath = null;
                if (result != null) {
                    Path directory = output.resolve("trajectories").resolve(mode.name()).resolve(task.id());
                    trajectoryPath = directory.resolve(result.trajectory().runId() + ".json").toString();
                    new AdaptivePlanningTrajectoryWriter(directory).write(result.trajectory());
                }
                RunRecord record = record(task, mode, runtimeCommit, benchmarkCommit, providerName, model,
                        1, ExecutionOrder.R_P_A, records.size() + 1, timestamp, durationMs,
                        result, error, trajectoryPath);
                append(resultsPath, record);
                records.add(record);
            }
        }
        writeSummary(output.resolve("run-summary.json"), records, runId, runtimeCommit,
                benchmarkCommit, providerName, model);
        return List.copyOf(records);
    }

    /** Executes one frozen live round under benchmark-commit/round-N, refusing to overwrite prior data. */
    public List<RunRecord> runRound(List<AdaptivePlanningTask> tasks, int round, ExecutionOrder order,
                                    String runtimeCommit, String benchmarkCommit, String providerName,
                                    String model, Path fixturesRoot, Path outputRoot,
                                    Path localMavenRepository, ProviderFactory providers) throws IOException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(providers, "providers must not be null");
        requireText(runtimeCommit, "runtimeCommit");
        requireText(benchmarkCommit, "benchmarkCommit");
        requireText(providerName, "providerName");
        requireText(model, "model");
        if (round < 1 || round > 2) throw new IllegalArgumentException("round must be 1 or 2");
        if (tasks.size() != 9) throw new IllegalArgumentException("DEV round requires exactly 9 tasks");
        if (!benchmarkCommit.matches("[A-Za-z0-9_-]{7,64}")) {
            throw new IllegalArgumentException("benchmarkCommit must be a simple commit identifier");
        }

        Path root = outputRoot.toAbsolutePath().normalize();
        Path benchmarkRoot = root.resolve(benchmarkCommit).normalize();
        Path roundDirectory = benchmarkRoot.resolve("round-" + round).normalize();
        if (!benchmarkRoot.startsWith(root) || !roundDirectory.startsWith(benchmarkRoot)) {
            throw new IOException("adaptive benchmark output escaped its root");
        }
        if (Files.exists(roundDirectory)) throw new IOException("refusing to overwrite existing round output");
        Files.createDirectories(roundDirectory.resolve("trajectories"));

        List<RunRecord> records = new ArrayList<>();
        Path resultsPath = roundDirectory.resolve("task-results.jsonl");
        List<PlanningMode> modes = order == ExecutionOrder.R_P_A
                ? MODES : List.of(PlanningMode.ADAPTIVE, PlanningMode.PLAN_EXECUTE, PlanningMode.REACTIVE);
        int conditionIndex = 0;
        for (AdaptivePlanningTask task : tasks) {
            for (PlanningMode mode : modes) {
                conditionIndex++;
                long started = System.nanoTime();
                String timestamp = Instant.now().toString();
                AdaptivePlanningRuntimeHarness.RunResult result = null;
                String error = null;
                try {
                    result = runtime.run(task, mode, Objects.requireNonNull(providers.create(task),
                                    "provider factory returned null"), fixturesRoot,
                            roundDirectory.resolve("workspaces"), "condition-" + conditionIndex,
                            localMavenRepository);
                    error = result.infrastructureError();
                } catch (Exception exception) {
                    error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                }
                long durationMs = (System.nanoTime() - started) / 1_000_000L;
                String trajectoryPath = null;
                if (result != null) {
                    Path directory = roundDirectory.resolve("trajectories").resolve(mode.name()).resolve(task.id());
                    trajectoryPath = directory.resolve(result.trajectory().runId() + ".json").toString();
                    new AdaptivePlanningTrajectoryWriter(directory).write(result.trajectory());
                }
                RunRecord record = record(task, mode, runtimeCommit, benchmarkCommit, providerName, model,
                        round, order, conditionIndex, timestamp, durationMs, result, error, trajectoryPath);
                append(resultsPath, record);
                records.add(record);
            }
        }
        writeSummary(roundDirectory.resolve("run-summary.json"), records,
                "round-" + round, runtimeCommit, benchmarkCommit, providerName, model);
        return List.copyOf(records);
    }

    private static RunRecord record(AdaptivePlanningTask task, PlanningMode mode, String runtimeCommit,
                                    String benchmarkCommit, String provider, String model, int round,
                                    ExecutionOrder order, int conditionIndex, String timestamp, long durationMs,
                                    AdaptivePlanningRuntimeHarness.RunResult result, String error,
                                    String trajectoryPath) {
        AdaptivePlanningMetrics metrics = result == null ? null : result.metrics();
        List<String> evaluationFailures = result == null ? List.of("CONDITION_DID_NOT_RUN")
                : result.evaluation().failures();
        boolean taskSuccess = result != null && result.evaluation().taskSuccess();
        boolean capReached = error != null && error.contains("BUDGET_CAP_REACHED")
                || result != null && result.trajectory().steps().stream()
                .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                .anyMatch(message -> message.contains("BUDGET_CAP_REACHED"));
        return new RunRecord("adaptive-planning-v1", 1, runtimeCommit, benchmarkCommit, round, order.name(),
                conditionIndex,
                provider, model,
                mode.name(), task.id(), task.taskClass().name(), task.expectedAdaptiveMode().name(),
                task.maxProviderRequests(), Agent.MAX_ITERATIONS, timestamp, taskSuccess,
                result == null ? "NO_WORKSPACE_RESULT" : result.evaluation().workspaceOutcome(),
                result != null && result.evaluation().conversationalCompletion(),
                metrics == null ? null : metrics.effectiveMode().name(),
                metrics == null ? null : metrics.routingConfidence(),
                metrics == null ? List.of() : metrics.routingReasonCodes(),
                metrics == null ? null : metrics.routingMatch(),
                metrics == null ? 0 : metrics.providerRequests(), metrics == null ? 0 : metrics.toolSteps(),
                metrics == null ? 0 : metrics.mutationCount(), metrics == null ? 0 : metrics.targetDrift(),
                metrics == null ? 0 : metrics.typedFailures(), metrics == null ? 0 : metrics.repeatedFailures(),
                metrics == null ? 0 : metrics.explicitReads(), metrics == null ? 0 : metrics.verificationAttempts(),
                metrics == null ? 0 : metrics.planCreated(), metrics == null ? 0 : metrics.planFallback(),
                metrics == null ? 0 : metrics.replanCount(), metrics == null ? 0 : metrics.planStepsTotal(),
                metrics == null ? 0 : metrics.planStepsCompleted(), evaluationFailures, error,
                capReached, durationMs, trajectoryPath);
    }

    private static void writeSummary(Path file, List<RunRecord> records, String runId,
                                     String runtimeCommit, String benchmarkCommit,
                                     String provider, String model) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("protocol", "adaptive-planning-v1");
        summary.put("runId", runId);
        summary.put("runtimeCommit", runtimeCommit);
        summary.put("benchmarkCommit", benchmarkCommit);
        summary.put("provider", provider);
        summary.put("model", model);
        summary.put("conditions", records.size());
        summary.put("taskSuccesses", records.stream().filter(RunRecord::taskSuccess).count());
        summary.put("byMode", byMode(records));
        List<RunRecord> adaptive = records.stream().filter(record -> "ADAPTIVE".equals(record.configuredMode())).toList();
        summary.put("adaptiveRouteReactive", adaptive.stream().filter(record -> "REACTIVE".equals(record.effectiveMode())).count());
        summary.put("adaptiveRoutePlan", adaptive.stream().filter(record -> "PLAN_EXECUTE".equals(record.effectiveMode())).count());
        summary.put("routeMatchesTaskClass", adaptive.stream().filter(record -> Boolean.TRUE.equals(record.routingMatch())).count());
        summary.put("planningFalsePositive", adaptive.stream().filter(record ->
                !"COMPLEX".equals(record.taskClass()) && "PLAN_EXECUTE".equals(record.effectiveMode())).count());
        summary.put("planningFalseNegative", adaptive.stream().filter(record ->
                "COMPLEX".equals(record.taskClass()) && "REACTIVE".equals(record.effectiveMode())).count());
        summary.put("simpleTaskRequestOverhead", simpleOverhead(records));
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Map<String, Object> byMode(List<RunRecord> records) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PlanningMode mode : MODES) {
            List<RunRecord> group = records.stream().filter(record -> mode.name().equals(record.configuredMode())).toList();
            out.put(mode.name(), Map.of("conditions", group.size(),
                    "taskSuccesses", group.stream().filter(RunRecord::taskSuccess).count(),
                    "providerRequests", group.stream().mapToInt(RunRecord::providerRequests).sum()));
        }
        return out;
    }

    private static Map<String, Object> simpleOverhead(List<RunRecord> records) {
        List<Map<String, Object>> deltas = new ArrayList<>();
        records.stream().filter(record -> "SIMPLE".equals(record.taskClass()))
                .map(RunRecord::taskId).distinct().forEach(taskId -> {
                    Map<String, RunRecord> modes = new LinkedHashMap<>();
                    records.stream().filter(record -> record.taskId().equals(taskId))
                            .forEach(record -> modes.put(record.configuredMode(), record));
                    if (modes.keySet().containsAll(MODES.stream().map(Enum::name).toList())) {
                        int reactive = modes.get("REACTIVE").providerRequests();
                        deltas.add(Map.of("taskId", taskId,
                                "planExecuteMinusReactive", modes.get("PLAN_EXECUTE").providerRequests() - reactive,
                                "adaptiveMinusReactive", modes.get("ADAPTIVE").providerRequests() - reactive));
                    }
                });
        return Map.of("perTask", List.copyOf(deltas), "aggregatePlanExecuteMinusReactive",
                deltas.stream().mapToInt(row -> (Integer) row.get("planExecuteMinusReactive")).sum(),
                "aggregateAdaptiveMinusReactive",
                deltas.stream().mapToInt(row -> (Integer) row.get("adaptiveMinusReactive")).sum());
    }

    private static void append(Path file, RunRecord record) throws IOException {
        Files.writeString(file, MAPPER.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    /** Fresh client per condition; this API has no implicit or default live provider. */
    @FunctionalInterface
    public interface ProviderFactory {
        LLMClient create(AdaptivePlanningTask task);
    }

    public enum ExecutionOrder { R_P_A, A_P_R }

    public record RunRecord(String protocol, int schemaVersion, String runtimeCommit, String benchmarkCommit,
                            int round, String executionOrder, int conditionIndex, String provider, String model,
                            String configuredMode, String taskId, String taskClass,
                            String expectedAdaptiveMode, int requestBudget, int runtimeMaxIterations,
                            String timestamp, boolean taskSuccess, String workspaceOutcome,
                            boolean conversationalCompletion, String effectiveMode, String routeConfidence,
                            List<String> routeReasonCodes, Boolean routingMatch, int providerRequests,
                            int toolSteps, int mutationCount, int targetDrift, int typedFailures,
                            int repeatedFailures, int explicitReads, int verificationAttempts, int planCreated,
                            int planFallback, int replanCount, int planStepsTotal, int planStepsCompleted,
                            List<String> evaluatorFailures, String infrastructureError, boolean requestCapReached,
                            long durationMs, String trajectoryPath) {
        public RunRecord {
            routeReasonCodes = List.copyOf(routeReasonCodes);
            evaluatorFailures = List.copyOf(evaluatorFailures);
        }
    }
}
