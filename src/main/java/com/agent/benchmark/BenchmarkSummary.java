package com.agent.benchmark;

import java.util.List;

public record BenchmarkSummary(
        ExperimentMetadata experiment,
        BenchmarkMetrics metrics,
        List<String> taskIds
) {
    public BenchmarkSummary {
        taskIds = List.copyOf(taskIds);
    }
}
