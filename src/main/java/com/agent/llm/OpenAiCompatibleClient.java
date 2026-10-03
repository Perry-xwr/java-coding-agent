package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Non-streaming adapter for OpenAI-compatible /chat/completions endpoints. */
public final class OpenAiCompatibleClient implements LLMClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(String endpoint, String model, String apiKey) {
        this(endpoint, model, apiKey, defaultHttpClient(null), new ObjectMapper());
    }

    OpenAiCompatibleClient(String endpoint, String model, String apiKey, Proxy proxy) {
        this(endpoint, model, apiKey, defaultHttpClient(proxy), new ObjectMapper());
    }

    OpenAiCompatibleClient(
            String endpoint,
            String model,
            String apiKey,
            OkHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.endpoint = requireNonBlank(endpoint, "endpoint");
        this.model = requireNonBlank(model, "model");
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public LLMResponse chat(List<Message> messages) throws IOException {
        return chat(messages, List.of());
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        Request request = buildRequest(messages, tools);
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseJson = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                String safeBody = apiKey == null ? responseJson : responseJson.replace(apiKey, "[REDACTED]");
                throw new IOException("OpenAI-compatible request failed with HTTP "
                        + response.code() + ": " + safeBody);
            }
            if (responseJson.isBlank()) {
                throw new IOException("OpenAI-compatible endpoint returned an empty response body");
            }
            return parseResponse(responseJson);
        }
    }

    private Request buildRequest(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.copyOf(messages));
        if (!tools.isEmpty()) payload.put("tools", tools.stream().map(OpenAiCompatibleClient::serializeTool).toList());

        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON));
        if (apiKey != null) builder.header("Authorization", "Bearer " + apiKey);
        return builder.build();
    }

    private LLMResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("OpenAI-compatible response does not contain a choice");
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isNull()
                ? null
                : message.path("content").asText(null);
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                JsonNode arguments = function.path("arguments");
                String argumentText = arguments.isTextual()
                        ? arguments.asText()
                        : objectMapper.writeValueAsString(arguments);
                calls.add(new ToolCall(
                        call.path("id").asText(""),
                        function.path("name").asText(""),
                        argumentText
                ));
            }
        }
        return new LLMResponse(content, calls);
    }

    private static Map<String, Object> serializeTool(ToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.parameters());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "function");
        result.put("function", function);
        return result;
    }

    private static OkHttpClient defaultHttpClient(Proxy proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (proxy != null) builder.proxy(proxy);
        return builder.build();
    }

    OkHttpClient httpClientForTesting() { return httpClient; }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
