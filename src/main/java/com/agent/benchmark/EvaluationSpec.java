package com.agent.benchmark;

import java.util.List;

public record EvaluationSpec(
        String taskId,
        List<String> requiredContains,
        List<String> forbiddenContains,
        boolean strictContentCheck
) {
    public EvaluationSpec {
        requiredContains = requiredContains == null ? List.of() : List.copyOf(requiredContains);
        forbiddenContains = forbiddenContains == null ? List.of() : List.copyOf(forbiddenContains);
    }
}
