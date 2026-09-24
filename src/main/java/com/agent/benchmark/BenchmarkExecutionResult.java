package com.agent.benchmark;

import java.util.List;

public record BenchmarkExecutionResult(
        ExperimentMetadata experiment,
        List<BenchmarkRunRecord> records,
        BenchmarkMetrics metrics
) {
    public BenchmarkExecutionResult {
        records = List.copyOf(records);
    }
}
