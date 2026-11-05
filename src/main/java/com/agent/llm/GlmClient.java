package com.agent.llm;

import com.agent.diagnostics.LiveTestDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class GlmClient implements StreamingLlmClient {
    private static final String API_KEY_ENV = "GLM_API_KEY";
    private static final String DEBUG_ENV = "GLM_DEBUG";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LiveTestControl liveTestControl;

    public GlmClient() {
        this(defaultGlmConfig());
    }

    private GlmClient(ModelProviderConfig config) {
        this(requireApiKey(config), config.endpoint(), config.model(),
                createHttpClient(config.proxy(), LiveTestControl.processInstanceIfEnabled() == null,
                        LiveTestControl.processInstanceIfEnabled()), new ObjectMapper());
    }

    static GlmClient configuredForLiveTest(String apiKey, String endpoint, String model,
                                           Proxy proxy, LiveTestControl control) {
        return new GlmClient(apiKey, endpoint, model,
                createHttpClient(proxy, false, control), new ObjectMapper());
    }

    /** Benchmark-only client: one logical request maps to one OkHttp attempt. */
    public static GlmClient forBenchmark() {
        if (LiveTestControl.enabled()) {
            throw new IllegalStateException("Benchmark clients are disabled in explicit live-test mode");
        }
        ModelProviderConfig config = defaultGlmConfig();
        return new GlmClient(requireApiKey(config), config.endpoint(), config.model(),
                createHttpClient(config.proxy(), false), new ObjectMapper());
    }

    static GlmClient configured(String apiKey, String endpoint, String model, boolean benchmark) {
        return new GlmClient(apiKey, endpoint, model,
                createHttpClient(null, !benchmark), new ObjectMapper());
    }

    static GlmClient configured(String apiKey, String endpoint, String model, boolean benchmark, Proxy proxy) {
        return new GlmClient(apiKey, endpoint, model,
                createHttpClient(proxy, !benchmark), new ObjectMapper());
    }

    GlmClient(String apiKey, String endpoint, String model,
              OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        this.endpoint = requireNonBlank(endpoint, "endpoint");
        this.model = requireNonBlank(model, "model");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.liveTestControl = LiveTestControl.processInstanceIfEnabled();
    }

    @Override
    public LLMResponse chat(List<Message> messages) throws IOException {
        return chat(messages, List.of());
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        Request request = buildRequest(messages, tools, false);
        ModelRequestTelemetry.RequestHandle usageHandle = ModelRequestTelemetry.beginRequest(
                "GLM", model, endpoint, messages, tools,
                request.body() == null ? 0 : request.body().contentLength());
        okhttp3.Call call = httpClient.newCall(request);
        try {
            configureLiveCallTimeout(call);
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, null);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw liveTestOrTransportError(exception);
        }
        Integer responseStatus = null;
        try (Response response = call.execute()) {
            responseStatus = response.code();
            if (isDebugEnabled()) {
                System.out.println("GLM HTTP status: " + response.code());
            }
            ResponseBody body = response.body();
            String responseJson = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                ModelRequestTelemetry.fail(usageHandle, response.code());
                String safeBody = redactApiKey(responseJson);
                if (liveTestControl == null) System.err.println("GLM error body: " + safeBody);
                throw new IOException("GLM HTTP provider error " + response.code()
                        + (liveTestControl == null ? ": " + safeBody : ""));
            }
            if (responseJson.isBlank()) {
                throw new IOException("GLM returned an empty response body");
            }
            try {
                LLMResponse parsed = parseResponse(responseJson);
                parsed = parsed.withRequestUsage(ModelRequestTelemetry.complete(
                        usageHandle, response.code(), parsed.providerUsage()));
                LiveTestDiagnostics.recordModelResponse(!parsed.toolCalls().isEmpty(), parsed.toolCalls());
                return parsed;
            } catch (JsonProcessingException exception) {
                throw new IOException("GLM response parse error");
            }
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, responseStatus);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw liveTestOrTransportError(exception);
        }
    }

    @Override
    public LLMResponse stream(
            List<Message> messages,
            List<ToolDefinition> tools,
            LlmStreamListener listener
    ) throws IOException {
        Objects.requireNonNull(listener, "listener must not be null");
        Request request = buildRequest(messages, tools, true);
        ModelRequestTelemetry.RequestHandle usageHandle = ModelRequestTelemetry.beginRequest(
                "GLM", model, endpoint, messages, tools,
                request.body() == null ? 0 : request.body().contentLength());
        okhttp3.Call call = httpClient.newCall(request);
        try {
            configureLiveCallTimeout(call);
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, null);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw liveTestOrTransportError(exception);
        }
        Integer responseStatus = null;
        try (Response response = call.execute()) {
            responseStatus = response.code();
            if (isDebugEnabled()) {
                System.out.println("GLM HTTP status: " + response.code());
            }
            ResponseBody body = response.body();
            if (!response.isSuccessful()) {
                ModelRequestTelemetry.fail(usageHandle, response.code());
                String errorBody = body == null ? "" : body.string();
                String safeBody = redactApiKey(errorBody);
                if (liveTestControl == null) System.err.println("GLM error body: " + safeBody);
                throw new IOException("GLM streaming request failed with HTTP " + response.code()
                        + (liveTestControl == null ? ": " + safeBody : ""));
            }
            if (body == null) {
                throw new IOException("GLM returned an empty streaming response body");
            }
            StreamingResponseAccumulator accumulator =
                    new StreamingResponseAccumulator(objectMapper, listener, "GLM", apiKey);
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        body.byteStream(), StandardCharsets.UTF_8))) {
                    StringBuilder eventData = new StringBuilder();
                    String line;
                    boolean done = false;
                    while (!done && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            done = processEvent(eventData, accumulator);
                        } else if (line.startsWith("data:")) {
                            if (!eventData.isEmpty()) eventData.append('\n');
                            eventData.append(line.substring(5).stripLeading());
                        }
                    }
                    if (!done && !eventData.isEmpty()) done = processEvent(eventData, accumulator);
                    if (!done) {
                        throw new IOException("GLM streaming response ended before data: [DONE]");
                    }
                }
                LLMResponse parsed = accumulator.finish().withRequestUsage(
                        ModelRequestTelemetry.complete(usageHandle, response.code(),
                                accumulator.providerUsage()));
                LiveTestDiagnostics.recordModelResponse(!parsed.toolCalls().isEmpty(), parsed.toolCalls());
                return parsed;
            } catch (IOException exception) {
                throw exception;
            }
        } catch (IOException exception) {
            ModelRequestTelemetry.fail(usageHandle, responseStatus);
            LiveTestDiagnostics.recordProviderFailure(exception);
            throw liveTestOrTransportError(exception);
        }
    }

    private static boolean processEvent(StringBuilder eventData, StreamingResponseAccumulator accumulator)
            throws IOException {
        if (eventData.isEmpty()) return false;
        String data = eventData.toString();
        eventData.setLength(0);
        if ("[DONE]".equals(data)) return true;
        accumulator.accept(data);
        return false;
    }

    private Request buildRequest(
            List<Message> messages,
            List<ToolDefinition> tools,
            boolean streaming
    ) throws IOException {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.copyOf(messages));
        if (streaming) {
            payload.put("stream", true);
        }
        if (!tools.isEmpty()) {
            payload.put("tools", tools.stream().map(GlmClient::serializeTool).toList());
        }

        String json = objectMapper.writeValueAsString(payload);
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, JSON));
        if (streaming) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private static Map<String, Object> serializeTool(ToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.parameters());

        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("type", "function");
        serialized.put("function", function);
        return serialized;
    }

    private LLMResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("GLM response does not contain a choice");
        }

        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isNull()
                ? null
                : message.path("content").asText(null);

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        function.path("name").asText(),
                        function.path("arguments").asText()
                ));
            }
        }
        return new LLMResponse(content, toolCalls, ProviderTokenUsage.fromJson(root.path("usage")));
    }

    private static ModelProviderConfig defaultGlmConfig() {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment();
        if (!ModelProviderConfig.GLM.equals(config.provider())) {
            throw new IllegalStateException("GlmClient requires MODEL_PROVIDER=glm");
        }
        return config;
    }

    private static String requireApiKey(ModelProviderConfig config) {
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable " + API_KEY_ENV + " is not set");
        }
        return apiKey;
    }

    private static OkHttpClient createHttpClient(Proxy proxy, boolean retryOnConnectionFailure) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .retryOnConnectionFailure(retryOnConnectionFailure)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (proxy != null) builder.proxy(proxy);
        return builder.build();
    }

    private static OkHttpClient createHttpClient(Proxy proxy, boolean retryOnConnectionFailure,
                                                  LiveTestControl control) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .retryOnConnectionFailure(retryOnConnectionFailure)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(5, TimeUnit.MINUTES)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (control != null) builder.authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);
        if (proxy != null) builder.proxy(proxy);
        if (control != null) {
            builder.addInterceptor(LiveTestControl.requestAttemptInterceptor(control));
            builder.addNetworkInterceptor(LiveTestControl.networkExchangeInterceptor(control));
        }
        return builder.build();
    }

    private void configureLiveCallTimeout(okhttp3.Call call) throws IOException {
        if (liveTestControl != null) liveTestControl.configureCallTimeout(call);
    }

    OkHttpClient httpClientForTesting() { return httpClient; }

    LiveTestControl liveTestControlForTesting() { return liveTestControl; }

    private String redactApiKey(String value) {
        return apiKey.isEmpty() ? value : value.replace(apiKey, "[REDACTED]");
    }

    private static IOException classifyTransportError(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return new IOException("GLM transport timeout", exception);
            }
            if (cause instanceof ConnectException) {
                String message = cause.getMessage();
                if (message != null && message.toLowerCase().contains("refused")) {
                    return new IOException("GLM connection refused", exception);
                }
            }
            if (cause instanceof SocketException) {
                String message = cause.getMessage();
                if (message != null && message.toLowerCase().contains("reset")) {
                    return new IOException("GLM connection reset", exception);
                }
            }
        }
        return exception;
    }

    private static IOException liveTestOrTransportError(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof LiveTestControlException controlException) return controlException;
        }
        LiveTestControl control = LiveTestControl.processInstanceIfEnabled();
        if (control != null && control.remainingNanos() == 0) {
            return new LiveTestControlException(LiveTestControlException.Code.TASK_TIMEOUT,
                    "TASK_TIMEOUT: five-minute live-test deadline expired.");
        }
        return classifyTransportError(exception);
    }

    private static boolean isDebugEnabled() {
        return Boolean.parseBoolean(System.getenv(DEBUG_ENV));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
