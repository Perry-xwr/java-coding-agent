package com.agent.benchmark.toolavailability;

import java.util.List;

public record ToolAvailabilityEvaluation(boolean success, boolean conversationalCompletion,
                                         String workspaceOutcome, String finalValidationStatus,
                                         boolean targetDrift, List<String> failures,
                                         String infrastructureError, String infrastructureErrorCategory) {
    public ToolAvailabilityEvaluation {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
