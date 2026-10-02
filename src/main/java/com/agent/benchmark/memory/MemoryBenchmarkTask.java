package com.agent.benchmark.memory;

import com.agent.CliMode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Independent multi-turn task contract for the memory-v1 DEV protocol. */
public record MemoryBenchmarkTask(
        String id,
        String category,
        String fixture,
        List<String> turns,
        List<CliMode> expectedRoutes,
        List<String> allowedMutationTargets,
        Map<String, String> expectedFileContents,
        List<String> unchangedFiles,
        List<String> requiredTools,
        List<String> requiredReadTargets,
        boolean requireCompletion,
        boolean ambiguityRequiresNoMutation,
        int maxProviderRequests,
        int maxToolSteps
) {
    public MemoryBenchmarkTask {
        requireText(id, "id");
        requireText(category, "category");
        requireText(fixture, "fixture");
        turns = nonEmpty(turns, "turns");
        expectedRoutes = nonEmpty(expectedRoutes, "expectedRoutes");
        if (expectedRoutes.size() != turns.size()) {
            throw new IllegalArgumentException("expectedRoutes must match turns");
        }
        allowedMutationTargets = immutable(allowedMutationTargets);
        expectedFileContents = Map.copyOf(Objects.requireNonNull(
                expectedFileContents, "expectedFileContents must not be null"));
        unchangedFiles = immutable(unchangedFiles);
        requiredTools = immutable(requiredTools);
        requiredReadTargets = immutable(requiredReadTargets);
        if (maxProviderRequests < turns.size()) {
            throw new IllegalArgumentException("maxProviderRequests must allow at least one request per turn");
        }
        if (maxToolSteps < 0) {
            throw new IllegalArgumentException("maxToolSteps must not be negative");
        }
    }

    private static <T> List<T> nonEmpty(List<T> values, String name) {
        List<T> copy = immutable(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
