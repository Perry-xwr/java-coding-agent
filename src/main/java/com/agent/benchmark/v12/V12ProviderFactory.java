package com.agent.benchmark.v12;

import com.agent.llm.LLMClient;

@FunctionalInterface
public interface V12ProviderFactory {
    LLMClient create(V12Task task);
}
