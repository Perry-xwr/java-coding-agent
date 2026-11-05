package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsChatRequestAndConvertsPlainTextResponseWithoutAuthorization() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (MockEndpoint endpoint = new MockEndpoint(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}",
                requestBody, authorization)) {
            LLMResponse response = endpoint.client(null).chat(List.of(Message.user("hi")));

            assertEquals("hello", response.content());
            assertEquals(List.of(), response.toolCalls());
            assertFalse(response.providerUsage().usageAvailable());
            assertNull(response.providerUsage().inputTokens());
            JsonNode request = mapper.readTree(requestBody.get());
            assertEquals("test-model", request.path("model").asText());
            assertEquals("user", request.path("messages").get(0).path("role").asText());
            assertEquals("hi", request.path("messages").get(0).path("content").asText());
            assertNull(authorization.get());
        }
    }

    @Test
    void parsesDeepSeekNonStreamingUsageAndExactRequestContextMetrics() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (MockEndpoint endpoint = new MockEndpoint(200,
                "{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15,"
                        + "\"prompt_cache_hit_tokens\":8,\"prompt_cache_miss_tokens\":4}}",
                requestBody, new AtomicReference<>())) {
            ModelRequestTelemetry.beginTask("usage-run", "CODE", 2);
            LLMResponse response = endpoint.deepSeekClient().chat(
                    List.of(Message.system("stable"), Message.user("task")),
                    List.of(new ToolDefinition("read_file", "read", Map.of("type", "object"))));
            ModelRequestUsageSummary summary = ModelRequestTelemetry.finishTask("usage-run");

            assertEquals(12, response.providerUsage().inputTokens());
            assertEquals(3, response.providerUsage().outputTokens());
            assertEquals(15, response.providerUsage().totalTokens());
            assertEquals(8, response.providerUsage().cacheHitTokens());
            assertEquals(4, response.providerUsage().cacheMissTokens());
            assertTrue(response.providerUsage().usageAvailable());
            assertEquals("DEEPSEEK", response.requestUsage().provider());
            assertEquals("deepseek-flash", response.requestUsage().model());
            assertEquals(2, response.requestUsage().agentTurn());
            assertEquals("CODE", response.requestUsage().profile());
            assertEquals("SUCCEEDED", response.requestUsage().status());
            assertEquals(2, response.requestUsage().context().messageCount());
            assertEquals(1, response.requestUsage().context().toolDefinitionCount());
            assertEquals(requestBody.get().getBytes(StandardCharsets.UTF_8).length,
                    response.requestUsage().context().payloadBytes());
            assertTrue(summary.usageComplete());
            assertEquals(1, summary.requests().size());
            assertEquals(15, summary.totalTokens());
            assertEquals(8, summary.cacheHitTokens());
        }
    }

    @Test
    void distinguishesProviderReportedZeroFromMissingUsageAndRecordsHttpFailureAsUnknown() throws Exception {
        try (MockEndpoint endpoint = new MockEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"zero\"}}],\"usage\":{"
                        + "\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}",
                new AtomicReference<>(), new AtomicReference<>())) {
            ModelRequestTelemetry.beginTask("zero-run", "READ", 1);
            LLMResponse response = endpoint.client(null).chat(List.of(Message.user("zero")));
            ModelRequestUsageSummary summary = ModelRequestTelemetry.finishTask("zero-run");
            assertTrue(response.providerUsage().usageAvailable());
            assertEquals(0, response.providerUsage().inputTokens());
            assertEquals(0, summary.totalTokens());
            assertTrue(summary.usageComplete());
        }
        try (MockEndpoint endpoint = new MockEndpoint(429, "{\"error\":\"limited\"}",
                new AtomicReference<>(), new AtomicReference<>())) {
            ModelRequestTelemetry.beginTask("failed-run", "READ", 2);
            assertThrows(IOException.class, () -> endpoint.client(null).chat(List.of(Message.user("fail"))));
            ModelRequestUsageSummary summary = ModelRequestTelemetry.finishTask("failed-run");
            assertEquals(1, summary.requests().size());
            assertEquals("FAILED", summary.requests().get(0).status());
            assertEquals(429, summary.requests().get(0).httpStatus());
            assertFalse(summary.requests().get(0).usageAvailable());
            assertNull(summary.totalTokens());
            assertFalse(summary.usageComplete());
        }
    }

    @Test
    void convertsToolCallToUnifiedRepresentationAndSendsSchema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (MockEndpoint endpoint = new MockEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{"
                        + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]}}]}",
                requestBody, new AtomicReference<>())) {
            ToolDefinition definition = new ToolDefinition("read_file", "Read a file", Map.of(
                    "type", "object", "properties", Map.of("path", Map.of("type", "string")),
                    "required", List.of("path")));
            LLMResponse response = endpoint.client(null).chat(List.of(Message.user("read README")), List.of(definition));

            assertNull(response.content());
            ToolCall call = response.toolCalls().get(0);
            assertEquals("call-1", call.id());
            assertEquals("read_file", call.name());
            assertEquals("{\"path\":\"README.md\"}", call.arguments());
            JsonNode sentTool = mapper.readTree(requestBody.get()).path("tools").get(0);
            assertEquals("function", sentTool.path("type").asText());
            assertEquals("read_file", sentTool.path("function").path("name").asText());
            assertEquals("object", sentTool.path("function").path("parameters").path("type").asText());
        }
    }

    @Test
    void sendsAuthorizationOnlyWhenConfigured() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (MockEndpoint endpoint = new MockEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                new AtomicReference<>(), authorization)) {
            endpoint.client("test-secret").chat(List.of(Message.user("hi")));
            assertEquals("Bearer test-secret", authorization.get());
        }
    }

    @Test
    void convertsHttpErrorsToIOExceptionWithoutLeakingConfiguredKey() throws Exception {
        try (MockEndpoint endpoint = new MockEndpoint(401, "denied test-secret", new AtomicReference<>(),
                new AtomicReference<>())) {
            IOException error = assertThrows(IOException.class,
                    () -> endpoint.client("test-secret").chat(List.of(Message.user("hi"))));
            assertTrue(error.getMessage().contains("HTTP 401"));
            assertFalse(error.getMessage().contains("test-secret"));
        }
    }

    @Test
    void malformedResponseRaisesIOException() throws Exception {
        try (MockEndpoint endpoint = new MockEndpoint(200, "not-json", new AtomicReference<>(),
                new AtomicReference<>())) {
            IOException error = assertThrows(IOException.class,
                    () -> endpoint.client(null).chat(List.of(Message.user("hi"))));
            assertInstanceOf(IOException.class, error);
        }
    }

    private static final class MockEndpoint implements AutoCloseable {
        private final HttpServer server;

        private MockEndpoint(int status, String response, AtomicReference<String> requestBody,
                             AtomicReference<String> authorization) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private OpenAiCompatibleClient client(String key) {
            return new OpenAiCompatibleClient("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions", "test-model", key);
        }

        private OpenAiCompatibleClient deepSeekClient() {
            return new OpenAiCompatibleClient("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions", "deepseek-flash", "test-key", null, true);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
