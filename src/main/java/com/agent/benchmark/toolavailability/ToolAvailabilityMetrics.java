package com.agent.benchmark.toolavailability;

import java.util.List;

public record ToolAvailabilityMetrics(
        String taskId, String workspaceClass, String language, String taskCategory, String mode,
        boolean taskSuccess, String workspaceOutcome, boolean conversationalCompletion,
        String finalValidationStatus, int providerRequests, int toolSteps, int reads, int mutations,
        boolean initialSafeRootPom, boolean finalSafeRootPom, boolean pomCreatedDuringRun,
        int mavenAvailabilityTransitionCount, int modelTurns, int mavenToolAdvertisedTurns,
        int runMavenTestAttempts, int runMavenTestExecutions, int runMavenTestSuccesses,
        int runMavenTestFailures, int environmentMismatchAttempts, int environmentMismatchExecutions,
        int environmentMismatchRejections, int appropriateMavenAttempts, int missingProjectFailures,
        int typedFailures, int repeatedFailures, boolean targetDrift, boolean requestCapHit,
        boolean infrastructureError, String infrastructureErrorCategory,
        List<ToolAvailabilitySnapshot> invocationTimeSnapshots
) {
    public ToolAvailabilityMetrics {
        invocationTimeSnapshots = invocationTimeSnapshots == null ? List.of() : List.copyOf(invocationTimeSnapshots);
    }
}
