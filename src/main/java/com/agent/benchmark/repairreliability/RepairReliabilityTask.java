package com.agent.benchmark.repairreliability;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RepairReliabilityTask(
        String id, String language, String category, String fixture,
        String instruction, Map<String, List<String>> expectedFiles,
        int maxSteps, int maxProviderRequests
) {
    public RepairReliabilityTask {
        require(id, "id"); require(language, "language"); require(category, "category");
        require(fixture, "fixture"); require(instruction, "instruction");
        Objects.requireNonNull(expectedFiles);
        if (expectedFiles.isEmpty()) throw new IllegalArgumentException("expectedFiles must not be empty");
        expectedFiles = expectedFiles.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        if (maxSteps < 1 || maxProviderRequests < 1) throw new IllegalArgumentException("caps must be positive");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
