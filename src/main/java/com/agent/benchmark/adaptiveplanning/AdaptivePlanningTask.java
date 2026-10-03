package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.PlanningMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One independent DEV task; expected routing is evaluator metadata, never a success input. */
public record AdaptivePlanningTask(
        String id,
        AdaptivePlanningTaskClass taskClass,
        String fixture,
        String instruction,
        PlanningMode expectedAdaptiveMode,
        List<String> allowedMutationTargets,
        Map<String, List<String>> expectedFileContains,
        List<String> requiredReadTargets,
        List<String> unchangedFiles,
        List<String> forbiddenPaths,
        boolean requireMutation,
        boolean requireMavenPass,
        int maxProviderRequests,
        int maxToolSteps
) {
    public AdaptivePlanningTask {
        requireText(id, "id");
        Objects.requireNonNull(taskClass, "taskClass must not be null");
        requireText(fixture, "fixture");
        requireText(instruction, "instruction");
        Objects.requireNonNull(expectedAdaptiveMode, "expectedAdaptiveMode must not be null");
        if (expectedAdaptiveMode == PlanningMode.ADAPTIVE) {
            throw new IllegalArgumentException("expectedAdaptiveMode must be an effective mode");
        }
        allowedMutationTargets = copy(allowedMutationTargets);
        requiredReadTargets = copy(requiredReadTargets);
        unchangedFiles = copy(unchangedFiles);
        forbiddenPaths = copy(forbiddenPaths);
        Map<String, List<String>> checks = new LinkedHashMap<>();
        if (expectedFileContains != null) {
            expectedFileContains.forEach((path, snippets) -> {
                requireText(path, "expected file path");
                if (snippets == null || snippets.isEmpty()) {
                    throw new IllegalArgumentException("expected snippets must not be empty");
                }
                snippets.forEach(value -> requireText(value, "expected snippet"));
                checks.put(path, List.copyOf(snippets));
            });
        }
        expectedFileContains = Map.copyOf(checks);
        if (maxProviderRequests < 1 || maxToolSteps < 1) {
            throw new IllegalArgumentException("task budgets must be positive");
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
