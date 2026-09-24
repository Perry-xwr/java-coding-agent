package com.agent.benchmark;

import com.agent.agent.AgentRunResult;

import java.nio.file.Path;

@FunctionalInterface
public interface BenchmarkAgentExecutor {
    AgentRunResult run(BenchmarkTask task, Path workspace, BaselineType baseline);
}
