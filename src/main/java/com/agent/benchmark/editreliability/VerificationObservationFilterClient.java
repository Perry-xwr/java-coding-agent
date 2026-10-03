package com.agent.benchmark.editreliability;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Hides only the V1.8 observation in the reread-only ablation condition. */
final class VerificationObservationFilterClient implements LLMClient {
    private final LLMClient delegate;

    VerificationObservationFilterClient(LLMClient delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public LLMResponse chat(List<Message> messages) throws IOException {
        return delegate.chat(filter(messages));
    }

    @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        return delegate.chat(filter(messages), tools);
    }

    private static List<Message> filter(List<Message> messages) {
        return messages.stream().filter(message -> !("system".equals(message.role())
                        && message.content().startsWith("POST_EDIT_VERIFICATION:")))
                .map(message -> "system".equals(message.role())
                        && message.content().startsWith("POST_EDIT_CONVERGENCE:")
                        ? Message.system("POST_EDIT_CONVERGENCE: The latest successful mutation has been reread. "
                        + "If the requested change is present, finish the task now instead of making unrequested "
                        + "cleanup or cosmetic edits. If the changed file is Java, run the required Maven "
                        + "verification before finalizing. Continue editing only when the reread or verification "
                        + "shows that the requested change is incorrect or incomplete.")
                        : message)
                .toList();
    }
}
