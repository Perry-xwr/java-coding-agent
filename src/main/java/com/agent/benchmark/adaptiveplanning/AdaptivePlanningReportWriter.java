package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.PlanningMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Descriptive round and two-round aggregates; it deliberately computes no composite score. */
public final class AdaptivePlanningReportWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdaptivePlanningReportWriter() { }

    public static void write(Path directory, List<AdaptivePlanningBenchmarkRunner.RunRecord> records,
                             Map<String, String> hashes, String runtimeCommit, String benchmarkCommit,
                             String provider, String model) throws IOException {
        Files.createDirectories(directory);
        List<Integer> rounds = records.stream().map(AdaptivePlanningBenchmarkRunner.RunRecord::round)
                .filter(round -> round > 0).distinct().sorted().toList();
        for (int round : rounds) {
            writeJson(directory.resolve("round-" + round + "-descriptive.json"),
                    summary(records.stream().filter(row -> row.round() == round).toList(), false,
                            runtimeCommit, benchmarkCommit, provider, model));
        }
        if (rounds.size() == 2) {
            writeJson(directory.resolve("two-round-descriptive.json"),
                    summary(records, true, runtimeCommit, benchmarkCommit, provider, model));
        }
        writeJson(directory.resolve("freeze-identity.json"), Map.of(
                "runtimeCommit", runtimeCommit, "benchmarkCommit", benchmarkCommit,
                "provider", provider, "model", model, "filesSha256", hashes));
    }

    private static Map<String, Object> summary(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows,
                                               boolean repeatedTasks, String runtimeCommit,
                                               String benchmarkCommit, String provider, String model) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocol", "adaptive-planning-v1");
        out.put("runtimeCommit", runtimeCommit);
        out.put("benchmarkCommit", benchmarkCommit);
        out.put("provider", provider);
        out.put("model", model);
        out.put("rounds", rows.stream().map(AdaptivePlanningBenchmarkRunner.RunRecord::round).distinct().sorted().toList());
        out.put("taskRuns", rows.size());
        out.put("sameNineDevTasksRepeated", repeatedTasks);
        out.put("byMode", byMode(rows));
        out.put("adaptiveRoutingByTaskClass", routingByClass(rows));
        out.put("simpleTaskRequestOverhead", simpleOverhead(rows));
        List<AdaptivePlanningBenchmarkRunner.RunRecord> adaptive = rows.stream()
                .filter(row -> "ADAPTIVE".equals(row.configuredMode())).toList();
        out.put("adaptivePlanExecuteConditions", adaptive.stream()
                .filter(row -> "PLAN_EXECUTE".equals(row.effectiveMode())).count());
        out.put("adaptivePlanFallbacks", sum(adaptive, AdaptivePlanningBenchmarkRunner.RunRecord::planFallback));
        out.put("adaptiveReplans", sum(adaptive, AdaptivePlanningBenchmarkRunner.RunRecord::replanCount));
        out.put("infrastructureFailureCount", rows.stream().filter(row -> row.infrastructureError() != null).count());
        out.put("infrastructureFailureTaskIds", rows.stream().filter(row -> row.infrastructureError() != null)
                .map(AdaptivePlanningBenchmarkRunner.RunRecord::taskId).toList());
        return out;
    }

    private static Map<String, Object> byMode(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (PlanningMode mode : List.of(PlanningMode.REACTIVE, PlanningMode.PLAN_EXECUTE, PlanningMode.ADAPTIVE)) {
            List<AdaptivePlanningBenchmarkRunner.RunRecord> group = rows.stream()
                    .filter(row -> mode.name().equals(row.configuredMode())).toList();
            long successes = group.stream().filter(AdaptivePlanningBenchmarkRunner.RunRecord::taskSuccess).count();
            int requests = sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::providerRequests);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("taskRuns", group.size());
            stats.put("successes", successes);
            stats.put("successRate", group.isEmpty() ? null : (double) successes / group.size());
            stats.put("providerRequestsTotal", requests);
            stats.put("providerRequestsAverage", average(group, AdaptivePlanningBenchmarkRunner.RunRecord::providerRequests));
            stats.put("toolStepsTotal", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::toolSteps));
            stats.put("toolStepsAverage", average(group, AdaptivePlanningBenchmarkRunner.RunRecord::toolSteps));
            stats.put("mutations", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::mutationCount));
            stats.put("targetDrift", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::targetDrift));
            stats.put("typedFailures", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::typedFailures));
            stats.put("repeatedFailures", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::repeatedFailures));
            stats.put("explicitReads", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::explicitReads));
            stats.put("verificationAttempts", sum(group, AdaptivePlanningBenchmarkRunner.RunRecord::verificationAttempts));
            stats.put("requestsPerSuccessfulTaskRun", successes == 0 ? null : (double) requests / successes);
            result.put(mode.name(), stats);
        }
        return result;
    }

    private static Map<String, Object> routingByClass(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<AdaptivePlanningBenchmarkRunner.RunRecord> adaptive = rows.stream()
                .filter(row -> "ADAPTIVE".equals(row.configuredMode())).toList();
        for (AdaptivePlanningTaskClass taskClass : AdaptivePlanningTaskClass.values()) {
            List<AdaptivePlanningBenchmarkRunner.RunRecord> group = adaptive.stream()
                    .filter(row -> taskClass.name().equals(row.taskClass())).toList();
            out.put(taskClass.name(), Map.of(
                    "routeReactive", group.stream().filter(row -> "REACTIVE".equals(row.effectiveMode())).count(),
                    "routePlanExecute", group.stream().filter(row -> "PLAN_EXECUTE".equals(row.effectiveMode())).count(),
                    "routingMatches", group.stream().filter(row -> Boolean.TRUE.equals(row.routingMatch())).count(),
                    "falsePositives", group.stream().filter(row -> !"COMPLEX".equals(row.taskClass())
                            && "PLAN_EXECUTE".equals(row.effectiveMode())).count(),
                    "falseNegatives", group.stream().filter(row -> "COMPLEX".equals(row.taskClass())
                            && "REACTIVE".equals(row.effectiveMode())).count()));
        }
        return out;
    }

    private static Map<String, Object> simpleOverhead(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows) {
        List<Map<String, Object>> deltas = new ArrayList<>();
        rows.stream().filter(row -> "SIMPLE".equals(row.taskClass())).map(AdaptivePlanningBenchmarkRunner.RunRecord::round)
                .distinct().sorted().forEach(round -> rows.stream().filter(row -> row.round() == round
                        && "SIMPLE".equals(row.taskClass())).map(AdaptivePlanningBenchmarkRunner.RunRecord::taskId)
                        .distinct().forEach(task -> {
                            Map<String, AdaptivePlanningBenchmarkRunner.RunRecord> modes = new TreeMap<>();
                            rows.stream().filter(row -> row.round() == round && row.taskId().equals(task))
                                    .forEach(row -> modes.put(row.configuredMode(), row));
                            if (modes.size() == 3) {
                                int reactive = modes.get("REACTIVE").providerRequests();
                                deltas.add(Map.of("round", round, "taskId", task,
                                        "planExecuteMinusReactive", modes.get("PLAN_EXECUTE").providerRequests() - reactive,
                                        "adaptiveMinusReactive", modes.get("ADAPTIVE").providerRequests() - reactive));
                            }
                        }));
        return Map.of("perTaskAndRound", List.copyOf(deltas),
                "planExecuteMinusReactiveTotal", deltas.stream().mapToInt(row -> (Integer) row.get("planExecuteMinusReactive")).sum(),
                "adaptiveMinusReactiveTotal", deltas.stream().mapToInt(row -> (Integer) row.get("adaptiveMinusReactive")).sum());
    }

    private static int sum(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows,
                           java.util.function.ToIntFunction<AdaptivePlanningBenchmarkRunner.RunRecord> value) {
        return rows.stream().mapToInt(value).sum();
    }

    private static double average(List<AdaptivePlanningBenchmarkRunner.RunRecord> rows,
                                  java.util.function.ToIntFunction<AdaptivePlanningBenchmarkRunner.RunRecord> value) {
        return rows.isEmpty() ? 0 : (double) sum(rows, value) / rows.size();
    }

    private static void writeJson(Path path, Object content) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content),
                StandardCharsets.UTF_8);
    }
}
