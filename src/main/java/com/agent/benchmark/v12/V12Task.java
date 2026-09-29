package com.agent.benchmark.v12;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.agent.CliMode;

/** A frozen, versioned task contract for the V1.2 runtime benchmark. */
public record V12Task(
        String id,
        V12Split split,
        String taskType,
        Map<String, String> initialFixture,
        List<String> userInstructions,
        List<CliMode> requestedModes,
        List<String> expectedBehavior,
        V12Evaluator evaluator,
        int maxSteps,
        List<String> capabilities,
        List<String> successCriteria
) {
    public V12Task {
        require(id, "id");
        Objects.requireNonNull(split, "split must not be null");
        require(taskType, "taskType");
        initialFixture = Map.copyOf(Objects.requireNonNull(initialFixture, "initialFixture must not be null"));
        if (initialFixture.isEmpty()) {
            throw new IllegalArgumentException("initialFixture must not be empty");
        }
        userInstructions = immutableNonEmpty(userInstructions, "userInstructions");
        requestedModes = requestedModes == null || requestedModes.isEmpty()
                ? java.util.Collections.nCopies(userInstructions.size(), CliMode.AUTO)
                : List.copyOf(requestedModes);
        if (requestedModes.size() != userInstructions.size()) {
            throw new IllegalArgumentException("requestedModes must match userInstructions");
        }
        expectedBehavior = immutableNonEmpty(expectedBehavior, "expectedBehavior");
        Objects.requireNonNull(evaluator, "evaluator must not be null");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        capabilities = immutableNonEmpty(capabilities, "capabilities");
        successCriteria = immutableNonEmpty(successCriteria, "successCriteria");
    }

    private static List<String> immutableNonEmpty(List<String> values, String name) {
        values = List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return values;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
