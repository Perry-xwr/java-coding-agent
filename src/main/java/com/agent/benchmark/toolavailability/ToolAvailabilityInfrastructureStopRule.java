package com.agent.benchmark.toolavailability;

/** A live run stops at three condition-level infrastructure failures. */
public final class ToolAvailabilityInfrastructureStopRule {
    public static final int MAX_INFRASTRUCTURE_FAILURES = 3;
    private int failures;

    public boolean observeCondition(boolean infrastructureFailure) {
        if (infrastructureFailure) failures++;
        return failures >= MAX_INFRASTRUCTURE_FAILURES;
    }

    public int failures() { return failures; }
}
