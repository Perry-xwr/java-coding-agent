package com.agent.benchmark.toolavailability;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ToolAvailabilityTask(
        String id,
        ToolAvailabilityWorkspaceClass workspaceClass,
        String language,
        String taskCategory,
        String fixture,
        String instruction,
        Map<String, List<String>> expectedFileContains,
        Set<String> allowedMutationTargets,
        boolean allowPomCreation,
        int maxProviderRequests,
        int maxSteps
) {
    public ToolAvailabilityTask {
        require(id, "id");
        Objects.requireNonNull(workspaceClass, "workspaceClass must not be null");
        require(language, "language");
        require(taskCategory, "taskCategory");
        require(fixture, "fixture");
        require(instruction, "instruction");
        expectedFileContains = expectedFileContains == null ? Map.of() : Map.copyOf(expectedFileContains);
        allowedMutationTargets = allowedMutationTargets == null ? Set.of() : Set.copyOf(allowedMutationTargets);
        if (maxProviderRequests != 12 || maxSteps != 10) {
            throw new IllegalArgumentException("V1.10 tasks require equal 12-request and 10-step caps");
        }
        if (allowedMutationTargets.isEmpty() || expectedFileContains.isEmpty()) {
            throw new IllegalArgumentException("Each task needs a target and independent content assertions");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
