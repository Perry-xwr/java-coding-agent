package com.agent.benchmark.v12;

import java.util.List;
import java.util.Objects;

public record V12BenchmarkSuite(
        String benchmarkVersion,
        String protocolStatus,
        String evaluatorVisibility,
        List<V12Task> tasks
) {
    public V12BenchmarkSuite {
        if (!"v1.2".equals(benchmarkVersion)) {
            throw new IllegalArgumentException("Expected benchmarkVersion v1.2");
        }
        if (!"FROZEN_BEFORE_FIRST_LIVE_RUN".equals(protocolStatus)) {
            throw new IllegalArgumentException("Unexpected protocol status");
        }
        if (!"RUNTIME_HIDDEN_NOT_SECRET".equals(evaluatorVisibility)) {
            throw new IllegalArgumentException("Unexpected evaluator visibility");
        }
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
    }
}
