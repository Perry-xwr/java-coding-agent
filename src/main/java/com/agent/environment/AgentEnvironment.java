package com.agent.environment;

import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolResult;

import java.util.List;

/** Execution boundary used by an Agent to discover and invoke tools. */
public interface AgentEnvironment {
    ToolResult execute(ToolCall toolCall);

    List<ToolDefinition> toolDefinitions();
}
