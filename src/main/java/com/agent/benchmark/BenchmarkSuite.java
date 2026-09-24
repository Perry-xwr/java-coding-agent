package com.agent.benchmark;

import java.util.List;
import java.util.Objects;

public record BenchmarkSuite(String benchmarkVersion, List<BenchmarkTask> tasks) {
    public BenchmarkSuite {
        if (benchmarkVersion == null || benchmarkVersion.isBlank()) {
            throw new IllegalArgumentException("benchmarkVersion must not be blank");
        }
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
    }
}
