package com.agent.benchmark;

import java.util.List;
import java.util.Objects;

public record BenchmarkTask(
        String id,
        TaskCategory category,
        TaskDifficulty difficulty,
        String description,
        String fixture,
        List<String> expectedFiles,
        EvaluationType evaluationType,
        int maxSteps,
        List<String> tags,
        String targetTest,
        BenchmarkSplit split,
        List<String> requiredTools
) {
    public BenchmarkTask {
        requireText(id, "id");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        requireText(description, "description");
        requireText(fixture, "fixture");
        expectedFiles = List.copyOf(Objects.requireNonNull(expectedFiles, "expectedFiles"));
        Objects.requireNonNull(evaluationType, "evaluationType must not be null");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        Objects.requireNonNull(split, "split must not be null");
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public boolean requiresModification() {
        return switch (category) {
            case BUG_FIX, LOGIC_FIX, TEST_FIX, SMALL_REFACTOR, MULTI_STEP_DEBUG -> true;
        };
    }
}
