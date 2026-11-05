package com.agent.llm;

import com.agent.diagnostics.LiveTestDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Authenticator;
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

/** Shared OpenAI-compatible /chat/completions transport adapter. */
public final class OpenAiCompatibleClient implements LLMClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final boolean deepSeekNonThinking;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    static OpenAiCompatibleClient configuredForLiveTest(String endpoint, String model, String apiKey,
                                                        Proxy proxy, LiveTestControl control) {
        return configuredForLiveTest(endpoint, model, apiKey, proxy, control, false);
    }

    static OpenAiCompatibleClient configuredForLiveTest(String endpoint, String model, String apiKey,
                                                        Proxy proxy, LiveTestControl control,
                                                        boolean deepSeekNonThinking) {
        return new OpenAiCompatibleClient(endpoint, model, apiKey,
                liveTestHttpClient(proxy, control), new ObjectMapper(), deepSeekNonThinking);
    }

    public OpenAiCompatibleClient(String endpoint, String model, String apiKey) {
        this(endpoint, model, apiKey, configuredHttpClient(null, false), new ObjectMapper());
    }

    OpenAiCompatibleClient(String endpoint, String model, String apiKey, Proxy proxy) {
        this(endpoint, model, apiKey, proxy, false);
    }

    OpenAiCompatibleClient(String endpoint, String model, String apiKey, Proxy proxy,
                           boolean deepSeekNonThinking) {
        this(endpoint, model, apiKey, configuredHttpClient(proxy, deepSeekNonThinking),
                new ObjectMapper(), deepSeekNonThinking);
    }

    OpenAiCompatibleClient(
            String endpoint,
            String model,
            String apiKey,
            OkHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this(endpoint, model, apiKey, httpClient, objectMapper, false);
    }

    OpenAiCompatibleClient(
            String endpoint,
            String model,
            String apiKey,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            boolean deepSeekNonThinking
    ) {
        this.endpoint = requireNonBlank(endpoint, "endpoint");
        this.model = requireNonBlank(model, "model");
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.deepSeekNonThinking = deepSeekNonThinking;
    }

    @Override
    public LLMResponse chat(List<Message> messages) throws IOException {
        return chat(messages, List.of());
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        Request request = buildRequest(messages, tools, false);
        ModelRequestTelemetry.RequestHandle usageHandle = ModelRequestTelemetry.beginRequest(
                deepSeekNonThinking ? "DEEPSEEK" : "OPENAI_COMPATIBLE", model, endpoint,
                messages, tools, request.body() == null ? 0 : request.body().contentLength());
        okhttp3.Call call = httpClient.newCall(request);
        LiveTestControl control = LiveTestControl.processInstanceIfEnabled();
        if (control != null) {
            try {
                control.configureCallTimeout(call);
            } catch (IOException exception) {
                ModelRequestTelemetry.fail(usageHandle, null);
                LiveTestDiagnostics.recordProviderFailure(exception);
                throw exception;
            }
        }
        Integer responseStatus = null;
        try (Response response = call.execute()) {
            responseStatus = response.code();
            ResponseBody body = response.body();
            String responseJson = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                ModelRequestTelemetry.fail(usageHandle, response.code());
                String safeBody = apiKey == null ? responseJson : responseJson.replace(apiKey, "[REDACTED]");
                throw new IOException("OpenAI-compatible request failed with HTTP "
                        + response.code() + (control == null ? ": " + safeBody : ""));
            }
            if (responseJson.isBlank()) {
                throw new IOException("OpenAI-compatible endpoint returned an empty response body");
            }
            LLMResponse parsed = parseResponse(responseJson);
            parsed = parsed.withRequestUsage(ModelRequestTelemetry.complete(
                    usageHandle, response.code(), parsed.providerUsage()));
            LiveTestDiagnostics.recordModelResponse(!parsed.toolCalls().isEmpty(), parsed.toolCalls());
            return parsed;
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, responseStatus);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw exception;
        }
    }

    /** Streaming transport used by the DeepSeek adapter; this client remains non-streaming for other providers. */
    LLMResponse stream(List<Message> messages, List<ToolDefinition> tools, LlmStreamListener listener)
            throws IOException {
        Objects.requireNonNull(listener, "listener must not be null");
        Request request = buildRequest(messages, tools, true);
        ModelRequestTelemetry.RequestHandle usageHandle = ModelRequestTelemetry.beginRequest(
                deepSeekNonThinking ? "DEEPSEEK" : "OPENAI_COMPATIBLE", model, endpoint,
                messages, tools, request.body() == null ? 0 : request.body().contentLength());
        okhttp3.Call call = httpClient.newCall(request);
        LiveTestControl control = LiveTestControl.processInstanceIfEnabled();
        if (control != null) {
            try {
                control.configureCallTimeout(call);
            } catch (IOException exception) {
                ModelRequestTelemetry.fail(usageHandle, null);
                LiveTestDiagnostics.recordProviderFailure(exception);
                throw exception;
            }
        }
        Integer responseStatus = null;
        try (Response response = call.execute()) {
            responseStatus = response.code();
            ResponseBody body = response.body();
            if (!response.isSuccessful()) {
                ModelRequestTelemetry.fail(usageHandle, response.code());
                String responseText = body == null ? "" : body.string();
                String safeBody = apiKey == null ? responseText : responseText.replace(apiKey, "[REDACTED]");
                throw new IOException("OpenAI-compatible streaming request failed with HTTP "
                        + response.code() + (control == null ? ": " + safeBody : ""));
            }
            if (body == null) {
                throw new IOException("OpenAI-compatible endpoint returned an empty streaming response body");
            }
            StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                    objectMapper, listener, deepSeekNonThinking ? "DeepSeek" : "OpenAI-compatible", apiKey);
            LLMResponse parsed = SseResponseReader.read(body, accumulator,
                    deepSeekNonThinking ? "DeepSeek" : "OpenAI-compatible");
            parsed = parsed.withRequestUsage(ModelRequestTelemetry.complete(
                    usageHandle, response.code(), parsed.providerUsage()));
            LiveTestDiagnostics.recordModelResponse(!parsed.toolCalls().isEmpty(), parsed.toolCalls());
            return parsed;
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, responseStatus);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw exception;
        }
    }

    Request buildRequest(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        return buildRequest(messages, tools, false);
    }

    Request buildRequest(List<Message> messages, List<ToolDefinition> tools, boolean streaming) throws IOException {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.copyOf(messages));
        if (deepSeekNonThinking) {
            payload.put("thinking", Map.of("type", "disabled"));
        }
        if (streaming) payload.put("stream", true);
        if (!tools.isEmpty()) payload.put("tools", tools.stream().map(OpenAiCompatibleClient::serializeTool).toList());

        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON));
        if (apiKey != null) builder.header("Authorization", "Bearer " + apiKey);
        if (streaming) builder.header("Accept", "text/event-stream");
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
        return new LLMResponse(content, calls, ProviderTokenUsage.fromJson(root.path("usage")));
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

    private static OkHttpClient configuredHttpClient(Proxy proxy, boolean deepSeek) {
        LiveTestControl control = LiveTestControl.processInstanceIfEnabled();
        if (control != null) return liveTestHttpClient(proxy, control);
        OkHttpClient.Builder builder = defaultHttpClient(proxy).newBuilder();
        if (deepSeek) {
            builder.retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .authenticator(Authenticator.NONE)
                    .proxyAuthenticator(Authenticator.NONE);
        }
        return builder.build();
    }

    private static OkHttpClient liveTestHttpClient(Proxy proxy, LiveTestControl control) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .callTimeout(5, TimeUnit.MINUTES)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (proxy != null) builder.proxy(proxy);
        builder.addInterceptor(LiveTestControl.requestAttemptInterceptor(control));
        builder.addNetworkInterceptor(LiveTestControl.networkExchangeInterceptor(control));
        return builder.build();
    }

    OkHttpClient httpClientForTesting() { return httpClient; }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
