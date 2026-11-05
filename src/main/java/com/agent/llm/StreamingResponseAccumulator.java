package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StreamingResponseAccumulator {
    private final ObjectMapper objectMapper;
    private final LlmStreamListener listener;
    private final String providerName;
    private final String apiKey;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
    private ProviderTokenUsage providerUsage = ProviderTokenUsage.unknown();

    StreamingResponseAccumulator(ObjectMapper objectMapper, LlmStreamListener listener) {
        this(objectMapper, listener, "LLM provider");
    }

    StreamingResponseAccumulator(ObjectMapper objectMapper, LlmStreamListener listener, String providerName) {
        this(objectMapper, listener, providerName, null);
    }

    StreamingResponseAccumulator(ObjectMapper objectMapper, LlmStreamListener listener,
                                 String providerName, String apiKey) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.providerName = Objects.requireNonNull(providerName, "providerName must not be null");
        this.apiKey = apiKey;
    }

    void accept(String data) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (IOException exception) {
            throw new IOException("Malformed " + providerName + " streaming event", exception);
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IOException(providerName + " streaming error: " + safeError(error));
        }
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()) {
            providerUsage = ProviderTokenUsage.fromJson(usage);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode delta = choices.get(0).path("delta");
        JsonNode text = delta.get("content");
        if (text != null && !text.isNull()) {
            String value = text.asText();
            if (!value.isEmpty()) {
                content.append(value);
                listener.onTextDelta(value);
            }
        }
        JsonNode calls = delta.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode indexNode = call.get("index");
                if (indexNode == null || !indexNode.canConvertToInt() || indexNode.asInt() < 0) {
                    throw new IOException("Invalid " + providerName + " streaming tool-call index");
                }
                int index = indexNode.asInt();
                ToolCallBuilder builder = toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                String id = call.path("id").asText("");
                if (!id.isEmpty()) {
                    if (builder.id != null && !builder.id.equals(id)) {
                        throw new IOException("Conflicting " + providerName + " streaming tool-call id");
                    }
                    builder.id = id;
                }
                JsonNode function = call.path("function");
                append(builder.name, function.path("name").asText(""));
                append(builder.arguments, function.path("arguments").asText(""));
            }
        }
    }

    LLMResponse finish() throws IOException {
        List<ToolCall> completed = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Map.Entry<Integer, ToolCallBuilder> entry : toolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList()) {
            ToolCallBuilder builder = entry.getValue();
            if (builder.id == null || builder.id.isBlank()
                    || builder.name.isEmpty() || builder.arguments.isEmpty()) {
                throw new IOException("Incomplete tool call in " + providerName + " streaming response");
            }
            if (!ids.add(builder.id)) {
                throw new IOException("Duplicate " + providerName + " streaming tool-call id");
            }
            String arguments = builder.arguments.toString();
            try {
                JsonNode parsedArguments = objectMapper.readTree(arguments);
                if (parsedArguments == null || !parsedArguments.isObject()) {
                    throw new IOException("Incomplete tool call in " + providerName + " streaming response");
                }
            } catch (IOException exception) {
                throw new IOException("Incomplete tool call in " + providerName + " streaming response", exception);
            }
            completed.add(new ToolCall(builder.id, builder.name.toString(), arguments));
        }
        return new LLMResponse(content.toString(), completed, providerUsage);
    }

    ProviderTokenUsage providerUsage() {
        return providerUsage;
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(value);
        }
    }

    private String safeError(JsonNode error) {
        String message = error.path("message").asText("");
        if (!message.isBlank()) {
            return redact(message);
        }
        String code = error.path("code").asText("");
        return code.isBlank() ? "provider error" : code;
    }

    private String redact(String value) {
        return apiKey == null || apiKey.isBlank() ? value : value.replace(apiKey, "[REDACTED]");
    }

    private static final class ToolCallBuilder {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
