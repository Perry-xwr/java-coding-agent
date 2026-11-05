package com.agent.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact serialized request-body bytes plus message counts/Java character counts (not token estimates). */
public record RequestContextMetrics(
        int messageCount,
        int toolDefinitionCount,
        long payloadBytes,
        Map<String, Integer> messageCountByRole,
        Map<String, Long> contentCharsByRole
) {
    public RequestContextMetrics {
        messageCountByRole = Map.copyOf(messageCountByRole);
        contentCharsByRole = Map.copyOf(contentCharsByRole);
    }

    public static RequestContextMetrics of(java.util.List<Message> messages,
                                           java.util.List<ToolDefinition> tools,
                                           long payloadBytes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Long> chars = new LinkedHashMap<>();
        for (Message message : messages) {
            counts.merge(message.role(), 1, Integer::sum);
            chars.merge(message.role(), (long) message.content().length(), Long::sum);
        }
        return new RequestContextMetrics(messages.size(), tools.size(), payloadBytes, counts, chars);
    }
}
