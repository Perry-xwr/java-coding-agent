package com.agent.agent;

import java.util.List;
import java.util.Objects;

public record AgentTrajectory(
        String runId,
        String task,
        List<AgentStep> steps,
        String finalAnswer,
        TerminationReason terminationReason,
        boolean completed,
        Boolean taskSuccess,
        long startedAtEpochMs,
        long durationMs,
        AgentPlan plan
) {
    public AgentTrajectory {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(task, "task must not be null");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        Objects.requireNonNull(terminationReason, "terminationReason must not be null");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }

    public AgentTrajectory(
            String runId,
            String task,
            List<AgentStep> steps,
            String finalAnswer,
            TerminationReason terminationReason,
            boolean completed,
            Boolean taskSuccess,
            long startedAtEpochMs,
            long durationMs
    ) {
        this(runId, task, steps, finalAnswer, terminationReason, completed,
                taskSuccess, startedAtEpochMs, durationMs, null);
    }
}
