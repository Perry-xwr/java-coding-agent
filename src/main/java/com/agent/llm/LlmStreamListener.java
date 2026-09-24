package com.agent.llm;

@FunctionalInterface
public interface LlmStreamListener {
    LlmStreamListener NO_OP = delta -> { };

    void onTextDelta(String delta);
}
