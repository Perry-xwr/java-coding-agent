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
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();

    StreamingResponseAccumulator(ObjectMapper objectMapper, LlmStreamListener listener) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    void accept(String data) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (IOException exception) {
            throw new IOException("Malformed GLM streaming event", exception);
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IOException("GLM streaming error: " + safeError(error));
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
                int index = call.path("index").asInt(0);
                ToolCallBuilder builder = toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                String id = call.path("id").asText("");
                if (!id.isEmpty()) {
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
        for (Map.Entry<Integer, ToolCallBuilder> entry : toolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList()) {
            ToolCallBuilder builder = entry.getValue();
            if (builder.id == null || builder.id.isBlank()
                    || builder.name.isEmpty() || builder.arguments.isEmpty()) {
                throw new IOException("Incomplete tool call in GLM streaming response");
            }
            String arguments = builder.arguments.toString();
            try {
                JsonNode parsedArguments = objectMapper.readTree(arguments);
                if (parsedArguments == null || !parsedArguments.isObject()) {
                    throw new IOException("Incomplete tool call in GLM streaming response");
                }
            } catch (IOException exception) {
                throw new IOException("Incomplete tool call in GLM streaming response", exception);
            }
            completed.add(new ToolCall(builder.id, builder.name.toString(), arguments));
        }
        return new LLMResponse(content.toString(), completed);
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(value);
        }
    }

    private static String safeError(JsonNode error) {
        String message = error.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        String code = error.path("code").asText("");
        return code.isBlank() ? "provider error" : code;
    }

    private static final class ToolCallBuilder {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
