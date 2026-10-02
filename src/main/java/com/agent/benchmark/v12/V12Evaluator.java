package com.agent.benchmark.v12;

/** The deterministic checks performed after the Agent workspace is closed. */
public enum V12Evaluator {
    ROUTE_AND_COMPLETION,
    TOOL_TRAJECTORY,
    FILE_CONTENT,
    HIDDEN_FILE_CONTENT,
    HIDDEN_MAVEN,
    HIDDEN_MAVEN_WITH_RECOVERY
}
