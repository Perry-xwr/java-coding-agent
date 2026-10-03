package com.agent.benchmark.planning;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One task in the independent planning-v1 DEV protocol. */
public record PlanningBenchmarkTask(
        String id,
        String category,
        String fixture,
        String instruction,
        List<String> allowedMutationTargets,
        Map<String, List<String>> expectedFileContains,
        List<String> unchangedFiles,
        List<String> requiredTools,
        List<String> requiredReadTargets,
        List<String> forbiddenPaths,
        boolean requireMavenPass,
        boolean requireSuccessfulMutation,
        int maxProviderRequests,
        int maxToolSteps
) {
    public PlanningBenchmarkTask {
        requireText(id, "id");
        requireText(category, "category");
        requireText(fixture, "fixture");
        requireText(instruction, "instruction");
        allowedMutationTargets = copyList(allowedMutationTargets);
        Map<String, List<String>> sourceChecks = expectedFileContains == null ? Map.of()
                : Objects.requireNonNull(expectedFileContains);
        Map<String, List<String>> copiedChecks = new LinkedHashMap<>();
        sourceChecks.forEach((path, snippets) -> {
            requireText(path, "expected file path");
            if (snippets == null || snippets.isEmpty()) {
                throw new IllegalArgumentException("expected snippets must not be empty: " + path);
            }
            snippets.forEach(value -> requireText(value, "expected snippet"));
            copiedChecks.put(path, List.copyOf(snippets));
        });
        expectedFileContains = Map.copyOf(copiedChecks);
        unchangedFiles = copyList(unchangedFiles);
        requiredTools = copyList(requiredTools);
        requiredReadTargets = copyList(requiredReadTargets);
        forbiddenPaths = copyList(forbiddenPaths);
        if (maxProviderRequests < 1 || maxToolSteps < 1) {
            throw new IllegalArgumentException("task budgets must be positive");
        }
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
