package com.agent.benchmark;

import java.util.Map;
import java.util.Objects;

public record EvaluationResult(
        String taskId,
        boolean success,
        boolean testsPassed,
        boolean formatValid,
        boolean requiredChangePresent,
        boolean contentCheckPassed,
        boolean strictContentRequired,
        String failureReason,
        Map<String, Object> metrics
) {
    public EvaluationResult {
        Objects.requireNonNull(taskId, "taskId must not be null");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public EvaluationResult(
            String taskId,
            boolean success,
            boolean testsPassed,
            boolean formatValid,
            boolean requiredChangePresent,
            String failureReason,
            Map<String, Object> metrics
    ) {
        this(taskId, success, testsPassed, formatValid, requiredChangePresent,
                true, false, failureReason, metrics);
    }

    public boolean behavioralPassed() {
        return testsPassed;
    }

    public boolean finalSuccess() {
        return success;
    }
}
