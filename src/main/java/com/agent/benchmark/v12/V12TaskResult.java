package com.agent.benchmark.v12;

import java.util.List;
import java.util.Map;

public record V12TaskResult(
        String taskId,
        V12Split split,
        String taskType,
        V12Evaluator evaluator,
        String benchmarkVersion,
        String runtimeCommit,
        String provider,
        String timestamp,
        boolean success,
        String completionState,
        boolean evaluatorPass,
        String finalStatus,
        List<V12TurnResult> turns,
        Map<String, Object> metrics,
        V12FailureCategory firstObservableFailure,
        V12FailureCategory finalFailure,
        V12FailureOwner failureOwner,
        String shortEvidence
) {
    public V12TaskResult {
        turns = List.copyOf(turns);
        metrics = Map.copyOf(metrics);
    }
}
