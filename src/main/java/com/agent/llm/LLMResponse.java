package com.agent.llm;

import java.util.List;

public record LLMResponse(
        String content,
        List<ToolCall> toolCalls,
        ProviderTokenUsage providerUsage,
        ModelRequestUsage requestUsage
) {
    public LLMResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        providerUsage = providerUsage == null ? ProviderTokenUsage.unknown() : providerUsage;
    }

    public LLMResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, ProviderTokenUsage.unknown(), null);
    }

    public LLMResponse(String content, List<ToolCall> toolCalls, ProviderTokenUsage providerUsage) {
        this(content, toolCalls, providerUsage, null);
    }

    public LLMResponse withRequestUsage(ModelRequestUsage usage) {
        return new LLMResponse(content, toolCalls, providerUsage, usage);
    }
}
