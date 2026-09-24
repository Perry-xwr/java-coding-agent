package com.agent.agent;

import java.util.Objects;

public record AgentRunResult(String finalAnswer, AgentTrajectory trajectory) {
    public AgentRunResult {
        Objects.requireNonNull(trajectory, "trajectory must not be null");
    }
}
