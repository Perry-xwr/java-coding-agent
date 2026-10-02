package com.agent.benchmark.v12;

import java.util.List;
import java.util.Map;

public record V12EvaluationResult(
        boolean passed,
        List<String> failedCriteria,
        Map<String, Object> details
) {
    public V12EvaluationResult {
        failedCriteria = List.copyOf(failedCriteria);
        details = Map.copyOf(details);
    }
}
