package com.agent.benchmark.editreliability;

import java.util.List;
import java.util.Map;

public record EditReliabilityTask(String id, String language, EditReliabilityTaskCategory category,
                                 String fixture, String instruction, Map<String, List<String>> expectedFiles,
                                 int maxProviderRequests) {
    public EditReliabilityTask {
        if (id == null || id.isBlank() || language == null || language.isBlank()
                || fixture == null || fixture.isBlank() || instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("task identity, language, fixture, and instruction are required");
        }
        if (category == null || expectedFiles == null || expectedFiles.isEmpty() || maxProviderRequests < 1) {
            throw new IllegalArgumentException("category, expected files, and positive request cap are required");
        }
        expectedFiles = expectedFiles.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
