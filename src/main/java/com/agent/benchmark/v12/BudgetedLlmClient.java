package com.agent.benchmark.v12;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class BudgetedLlmClient implements LLMClient {
    private final LLMClient delegate;
    private final ProviderBudget runBudget;
    private final int taskCap;
    private int taskUsed;

    public BudgetedLlmClient(LLMClient delegate, ProviderBudget runBudget, int taskCap) {
        this.delegate = Objects.requireNonNull(delegate);
        this.runBudget = Objects.requireNonNull(runBudget);
        if (taskCap < 1) throw new IllegalArgumentException("taskCap must be positive");
        this.taskCap = taskCap;
    }

    @Override public LLMResponse chat(List<Message> messages) throws IOException {
        return chat(messages, List.of());
    }

    @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        if (++taskUsed > taskCap) throw new IOException("BUDGET_CAP_REACHED: per-task provider request cap " + taskCap);
        runBudget.acquire();
        return delegate.chat(messages, tools);
    }

    public int used() { return taskUsed; }
}
