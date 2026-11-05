package com.agent.agent;

public enum TerminationReason {
    FINAL_ANSWER,
    MAX_STEPS,
    LLM_ERROR,
    REQUEST_BUDGET_EXCEEDED,
    TASK_TIMEOUT,
    DIAGNOSTICS_FAILURE
}
