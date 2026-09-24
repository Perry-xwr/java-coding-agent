package com.agent.agent;

public enum AgentActionType {
    PLAN_CREATED,
    PLAN_UPDATED,
    PLAN_COMPLETION_FEEDBACK,
    TOOL_CALL,
    RUNTIME_FEEDBACK,
    FINAL_ANSWER,
    ERROR
}
