package com.agent.benchmark.memory;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic trajectory metrics for a single memory-v1 task and condition. */
public record MemoryBenchmarkMetrics(
        int providerRequests,
        int toolSteps,
        int mutationCount,
        int targetDriftCount,
        int repeatedFailureCount,
        int redundantReadCount
) {
    private static final Set<String> MUTATIONS = Set.of(
            "create_file", "apply_patch", "insert_before", "insert_after", "replace_lines"
    );

    public static MemoryBenchmarkMetrics from(
            List<MemoryRuntimeHarness.TurnResult> turns,
            List<String> allowedMutationTargets,
            int providerRequests
    ) {
        int toolSteps = 0;
        int mutations = 0;
        int drift = 0;
        int repeatedFailures = 0;
        int redundantReads = 0;
        FailureKey previousFailure = null;
        Set<String> allowed = Set.copyOf(allowedMutationTargets);

        for (MemoryRuntimeHarness.TurnResult turn : turns) {
            String lastRead = null;
            for (AgentStep step : turn.trajectory().steps()) {
                if (step.actionType() != AgentActionType.TOOL_CALL) {
                    continue;
                }
                toolSteps++;
                boolean failed = step.toolResult() != null && !step.toolResult().success();
                if (failed) {
                    FailureKey key = new FailureKey(step.toolName(), normalizedArguments(step.arguments()),
                            step.toolResult().errorCode().name());
                    if (key.equals(previousFailure)) {
                        repeatedFailures++;
                    }
                    previousFailure = key;
                    lastRead = null;
                } else {
                    previousFailure = null;
                }

                if (MUTATIONS.contains(step.toolName()) && step.toolResult() != null
                        && step.toolResult().success()) {
                    mutations++;
                    String path = path(step);
                    if (path == null || !allowed.contains(path)) {
                        drift++;
                    }
                    lastRead = null;
                } else if ("read_file".equals(step.toolName()) && step.toolResult() != null
                        && step.toolResult().success()) {
                    String path = path(step);
                    if (path != null && path.equals(lastRead)) {
                        redundantReads++;
                    }
                    lastRead = path;
                }
            }
        }
        return new MemoryBenchmarkMetrics(providerRequests, toolSteps, mutations, drift,
                repeatedFailures, redundantReads);
    }

    private static String normalizedArguments(Map<String, Object> arguments) {
        return new TreeMap<>(arguments).toString();
    }

    private static String path(AgentStep step) {
        Object metadataPath = step.toolResult() == null ? null : step.toolResult().metadata().get("path");
        Object candidate = metadataPath == null ? step.arguments().get("path") : metadataPath;
        return candidate instanceof String value ? value.replace('\\', '/') : null;
    }

    private record FailureKey(String tool, String arguments, String errorCode) {
    }
}
