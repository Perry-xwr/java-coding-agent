package com.agent.agent;

import com.agent.tool.ToolResult;

import java.util.Map;

public interface AgentEventListener {
    AgentEventListener NO_OP = new AgentEventListener() {
    };

    default void toolStarted(String toolName, Map<String, Object> arguments) {
    }

    default void toolFinished(String toolName, ToolResult result) {
    }

    default void assistantMessageStarted() {
    }

    default void assistantTextDelta(String delta) {
    }

    default void assistantMessageFinished() {
    }
}
