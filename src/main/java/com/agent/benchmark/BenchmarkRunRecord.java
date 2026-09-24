package com.agent.benchmark;

import com.agent.agent.AgentRunResult;

import java.util.Objects;

public record BenchmarkRunRecord(
        BenchmarkTask task,
        BaselineType baseline,
        AgentRunResult run,
        EvaluationResult evaluation,
        FailureCategory failureCategory,
        boolean evaluatorError
) {
    public BenchmarkRunRecord {
        Objects.requireNonNull(task);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(run);
        Objects.requireNonNull(evaluation);
        Objects.requireNonNull(failureCategory);
    }
}
