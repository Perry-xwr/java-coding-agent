package com.agent.llm;

import java.io.IOException;
import java.util.List;

public interface StreamingLlmClient extends LLMClient {
    LLMResponse stream(
            List<Message> messages,
            List<ToolDefinition> tools,
            LlmStreamListener listener
    ) throws IOException;
}
