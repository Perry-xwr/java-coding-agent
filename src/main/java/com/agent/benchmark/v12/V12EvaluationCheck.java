package com.agent.benchmark.v12;

import com.agent.CliMode;
import java.util.List;

public record V12EvaluationCheck(
        String taskId,
        List<CliMode> routes,
        List<String> requiredTools,
        List<String> forbiddenTools,
        List<String> orderedToolSubsequence,
        List<V12FileAssertion> files,
        boolean requireCompletion,
        boolean requireMavenFailure,
        boolean requireMavenFinalPass,
        boolean requireRecoveryAfterFailure
) {
    public V12EvaluationCheck {
        routes = routes == null ? List.of() : List.copyOf(routes);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        orderedToolSubsequence = orderedToolSubsequence == null ? List.of() : List.copyOf(orderedToolSubsequence);
        files = files == null ? List.of() : List.copyOf(files);
    }
}
