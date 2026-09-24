package com.agent.agent;

import com.agent.tool.ToolResult;

import java.util.Map;

public record AgentStep(
        int stepIndex,
        AgentActionType actionType,
        String toolName,
        String toolCallId,
        String rawArguments,
        Map<String, Object> arguments,
        ToolResult toolResult,
        String finalAnswer,
        String errorMessage,
        long startedAtEpochMs,
        long durationMs
) {
    public AgentStep {
        if (stepIndex < 1) {
            throw new IllegalArgumentException("stepIndex must be positive");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
