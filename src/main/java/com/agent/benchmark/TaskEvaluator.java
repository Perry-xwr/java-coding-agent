package com.agent.benchmark;

import com.agent.agent.AgentRunResult;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface TaskEvaluator {
    EvaluationResult evaluate(
            BenchmarkTask task,
            Path workspace,
            AgentRunResult run
    ) throws IOException;
}
