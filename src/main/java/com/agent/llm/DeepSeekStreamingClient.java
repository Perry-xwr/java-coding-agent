package com.agent.llm;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Streaming DeepSeek facade over the existing OpenAI-compatible request/response adapter. */
final class DeepSeekStreamingClient implements StreamingLlmClient {
    private final OpenAiCompatibleClient delegate;

    DeepSeekStreamingClient(OpenAiCompatibleClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public LLMResponse chat(List<Message> messages) throws IOException {
        return delegate.chat(messages);
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        return delegate.chat(messages, tools);
    }

    @Override
    public LLMResponse stream(List<Message> messages, List<ToolDefinition> tools,
                              LlmStreamListener listener) throws IOException {
        return delegate.stream(messages, tools, listener);
    }

    OpenAiCompatibleClient delegateForTesting() {
        return delegate;
    }
}
