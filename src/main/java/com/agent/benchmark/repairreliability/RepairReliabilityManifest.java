package com.agent.benchmark.repairreliability;

import java.util.List;
import java.util.Objects;

public record RepairReliabilityManifest(String version, int providerRequestCap,
                                        List<RepairReliabilityTask> tasks) {
    public RepairReliabilityManifest {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        if (providerRequestCap < 1) throw new IllegalArgumentException("providerRequestCap must be positive");
        tasks = List.copyOf(Objects.requireNonNull(tasks));
        if (tasks.size() != 12) throw new IllegalArgumentException("V1.9 repair DEV requires exactly 12 tasks");
        if (tasks.stream().map(RepairReliabilityTask::id).distinct().count() != tasks.size()) {
            throw new IllegalArgumentException("task ids must be unique");
        }
    }
}
