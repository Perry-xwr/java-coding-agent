package com.agent.benchmark.editreliability;

/** Stops a live round after the protocol's condition-level infrastructure failure threshold. */
public final class EditReliabilityInfrastructureStopRule {
    public static final int MAX_INFRASTRUCTURE_FAILURES = 3;

    private int infrastructureFailures;

    public boolean observeCondition(boolean infrastructureError) {
        if (infrastructureError) {
            infrastructureFailures++;
        }
        return infrastructureFailures >= MAX_INFRASTRUCTURE_FAILURES;
    }

    public int infrastructureFailures() {
        return infrastructureFailures;
    }
}
