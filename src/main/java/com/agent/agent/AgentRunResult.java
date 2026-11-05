package com.agent.agent;

import com.agent.llm.ModelRequestUsageSummary;
import java.util.Objects;

public record AgentRunResult(String finalAnswer, AgentTrajectory trajectory,
                             ModelRequestUsageSummary usageSummary, TaskChangeReview changeReview) {
    public AgentRunResult {
        Objects.requireNonNull(trajectory, "trajectory must not be null");
        usageSummary = usageSummary == null ? ModelRequestUsageSummary.empty() : usageSummary;
        changeReview = changeReview == null ? TaskChangeReview.empty(trajectory.runId()) : changeReview;
    }

    public AgentRunResult(String finalAnswer, AgentTrajectory trajectory, ModelRequestUsageSummary usageSummary) {
        this(finalAnswer, trajectory, usageSummary, TaskChangeReview.empty(trajectory.runId()));
    }

    public AgentRunResult(String finalAnswer, AgentTrajectory trajectory) {
        this(finalAnswer, trajectory, ModelRequestUsageSummary.empty(), TaskChangeReview.empty(trajectory.runId()));
    }

    /** Derived on demand from the immutable execution trajectory; not part of persisted session history. */
    public TaskEvidence evidence() {
        return TaskEvidence.from(trajectory, changeReview);
    }
}
