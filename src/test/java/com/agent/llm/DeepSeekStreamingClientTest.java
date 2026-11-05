package com.agent.llm;

import com.agent.CliAgentFactory;
import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekStreamingClientTest {
    private static final MediaType SSE = MediaType.get("text/event-stream; charset=utf-8");
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path workspace;

    @AfterEach
    void clearLiveTestState() {
        System.clearProperty("agent.live-test-mode");
        LiveTestControl.resetProcessInstanceForTests();
    }

    @Test
    void streamsChineseTextAndSendsStreamPlusThinkingDisabled() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        DeepSeekStreamingClient client = client(List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"你好，\"}}],\"usage\":null}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}"),
                event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{"
                        + "\"prompt_tokens\":21,\"completion_tokens\":4,\"total_tokens\":25,"
                        + "\"prompt_cache_hit_tokens\":9,\"prompt_cache_miss_tokens\":12}}"),
                done()), requestBody, requests, null);
        List<String> deltas = new ArrayList<>();

        LLMResponse response = client.stream(List.of(Message.user("say hello")), List.of(), deltas::add);

        assertEquals(List.of("你好，", "世界"), deltas);
        assertEquals("你好，世界", response.content());
        assertTrue(response.toolCalls().isEmpty());
        assertTrue(response.providerUsage().usageAvailable());
        assertEquals(21, response.providerUsage().inputTokens());
        assertEquals(4, response.providerUsage().outputTokens());
        assertEquals(25, response.providerUsage().totalTokens());
        assertEquals(9, response.providerUsage().cacheHitTokens());
        assertEquals(12, response.providerUsage().cacheMissTokens());
        assertEquals(1, requests.get());
        JsonNode payload = mapper.readTree(requestBody.get());
        assertTrue(payload.path("stream").asBoolean());
        assertEquals("disabled", payload.path("thinking").path("type").asText());
    }

    @Test
    void parsesSseEventsSplitAcrossSmallTransportReads() throws Exception {
        String body = event("{\"choices\":[{\"delta\":{\"content\":\"片段\"}}]}") + done();
        Interceptor chunked = chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(chunkedBody(body, 3)).build();
        DeepSeekStreamingClient client = client(chunked);
        List<String> deltas = new ArrayList<>();

        LLMResponse response = client.stream(List.of(Message.user("stream")), List.of(), deltas::add);

        assertEquals("片段", response.content());
        assertEquals(List.of("片段"), deltas);
    }

    @Test
    void assemblesCreateFileCallAndDoesNotExposeArgumentsAsText() throws Exception {
        DeepSeekStreamingClient client = client(List.of(
                event(toolDelta(List.of(toolChunk(0, "call-create", "create_",
                        "{\"path\":\"hello.txt\",\"content\":\"Hello ")))),
                event(toolDelta(List.of(toolChunk(0, "call-create", "file", "DeepSeek Agent\"}")))),
                done()), new AtomicReference<>(), new AtomicInteger(), null);
        List<String> deltas = new ArrayList<>();

        LLMResponse response = client.stream(List.of(Message.user("create a file")), List.of(), deltas::add);

        assertEquals(List.of(new ToolCall("call-create", "create_file",
                "{\"path\":\"hello.txt\",\"content\":\"Hello DeepSeek Agent\"}")), response.toolCalls());
        assertTrue(response.content() == null || response.content().isEmpty());
        assertTrue(deltas.isEmpty(), "tool arguments must not be sent to the user text callback");
    }

    @Test
    void assemblesInterleavedCallsByIndexAndArgumentsSplitInsideJsonStrings() throws Exception {
        String events = event(toolDelta(List.of(
                toolChunk(1, "call-read", "read_file", "{\"path\":\"b.java\"}"),
                toolChunk(0, "call-create", "create_file", "{\"path\":\"a.txt\",\"content\":\"line1\\n"))))
                + event(toolDelta(List.of(toolChunk(0, null, null, "line2\"}"))))
                + done();
        DeepSeekStreamingClient client = client(List.of(events), new AtomicReference<>(), new AtomicInteger(), null);

        LLMResponse response = client.stream(List.of(Message.user("make and read")), List.of(), ignored -> { });

        assertEquals(List.of(
                new ToolCall("call-create", "create_file",
                        "{\"path\":\"a.txt\",\"content\":\"line1\\nline2\"}"),
                new ToolCall("call-read", "read_file", "{\"path\":\"b.java\"}")), response.toolCalls());
    }

    @Test
    void acceptsToolCallThenAllowsNextStreamedAssistantTurnWithToolResultHistory() throws Exception {
        String toolCallResponse = event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]}}]}")
                + done();
        String finalResponse = event("{\"choices\":[{\"delta\":{\"content\":\"Read complete.\"},\"finish_reason\":\"stop\"}]}")
                + done();
        AtomicReference<String> lastRequest = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        DeepSeekStreamingClient client = clientResponses(List.of(toolCallResponse, finalResponse), lastRequest, requests);

        LLMResponse first = client.stream(List.of(Message.user("read README")), List.of(), ignored -> { });
        ToolCall call = first.toolCalls().get(0);
        assertEquals(1, first.toolCalls().size());
        LLMResponse second = client.stream(List.of(
                Message.user("read README"), Message.assistant(first.content(), first.toolCalls()),
                Message.tool(call.id(), "README contents")), List.of(), ignored -> { });

        assertEquals("Read complete.", second.content());
        assertTrue(second.toolCalls().isEmpty());
        assertEquals(2, requests.get());
        JsonNode payload = mapper.readTree(lastRequest.get());
        assertEquals("tool", payload.path("messages").get(2).path("role").asText());
        assertEquals("call-1", payload.path("messages").get(2).path("tool_call_id").asText());
    }

    @Test
    void agentExecutesOneStructuredToolCallThenStreamsFinalAnswer() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "DeepSeek streaming fixture\n");
        String toolCallResponse = event(toolDelta(List.of(
                toolChunk(0, "call-read", "read_file", "{\"path\":\"README.md\"}")))) + done();
        String finalResponse = event(mapper.writeValueAsString(Map.of("choices", List.of(
                Map.of("delta", Map.of("content", "I read the fixture."), "finish_reason", "stop")))))
                + done();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> finalRequest = new AtomicReference<>();
        DeepSeekStreamingClient client = clientResponses(
                List.of(toolCallResponse, finalResponse), finalRequest, requests);
        Agent agent = CliAgentFactory.createReadOnly(client, workspace, AgentEventListener.NO_OP);

        var result = agent.runWithTrajectory("Read README.md.");

        assertTrue(result.trajectory().completed());
        assertEquals(2, requests.get());
        List<com.agent.agent.AgentStep> toolSteps = result.trajectory().steps().stream()
                .filter(step -> step.actionType() == com.agent.agent.AgentActionType.TOOL_CALL)
                .toList();
        assertEquals(1, toolSteps.size());
        assertEquals("read_file", toolSteps.get(0).toolName());
        assertTrue(toolSteps.get(0).toolResult().success());
        JsonNode secondRequest = mapper.readTree(finalRequest.get());
        boolean matchingToolResultPresent = false;
        for (JsonNode message : secondRequest.path("messages")) {
            if ("tool".equals(message.path("role").asText())
                    && "call-read".equals(message.path("tool_call_id").asText())) {
                matchingToolResultPresent = true;
            }
        }
        assertTrue(matchingToolResultPresent, "next model request must contain the corresponding tool result");
    }

    @Test
    void rejectsMalformedSseJsonAndEofWithoutDone() {
        DeepSeekStreamingClient malformed = client(List.of(event("{bad json}") + done()),
                new AtomicReference<>(), new AtomicInteger(), null);
        IOException malformedError = assertThrows(IOException.class,
                () -> malformed.stream(List.of(Message.user("x")), List.of(), ignored -> { }));
        assertTrue(malformedError.getMessage().contains("Malformed DeepSeek streaming event"));

        DeepSeekStreamingClient missingDone = client(List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}")),
                new AtomicReference<>(), new AtomicInteger(), null);
        IOException truncated = assertThrows(IOException.class,
                () -> missingDone.stream(List.of(Message.user("x")), List.of(), ignored -> { }));
        assertTrue(truncated.getMessage().contains("ended before data: [DONE]"));
    }

    @Test
    void redactsConfiguredKeyFromStreamingProviderErrors() {
        DeepSeekStreamingClient client = client(List.of(
                event("{\"error\":{\"message\":\"rejected test-key\",\"code\":\"denied\"}}"), done()),
                new AtomicReference<>(), new AtomicInteger(), null);

        IOException error = assertThrows(IOException.class,
                () -> client.stream(List.of(Message.user("x")), List.of(), ignored -> { }));

        assertTrue(error.getMessage().contains("[REDACTED]"));
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test
    void interruptedStreamFailsInsteadOfReturningPartialAssistantResponse() {
        Interceptor broken = chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(throwingBody(event("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}")))
                .build();
        DeepSeekStreamingClient client = client(broken);
        List<String> visible = new ArrayList<>();

        IOException error = assertThrows(IOException.class,
                () -> client.stream(List.of(Message.user("x")), List.of(), visible::add));

        assertEquals(List.of("partial"), visible);
        assertTrue(error.getMessage().contains("simulated connection reset"));
    }

    @Test
    void rejectsConflictingIdsAndIncompleteToolArguments() throws Exception {
        String conflicting = event(toolDelta(List.of(toolChunk(0, "call-a", "read_file", "{\"path\":\"x\"}"))))
                + event(toolDelta(List.of(toolChunk(0, "call-b", null, null)))) + done();
        IOException conflict = assertThrows(IOException.class,
                () -> client(List.of(conflicting), new AtomicReference<>(), new AtomicInteger(), null)
                        .stream(List.of(Message.user("x")), List.of(), ignored -> { }));
        assertTrue(conflict.getMessage().contains("Conflicting DeepSeek"));

        String incomplete = event(toolDelta(List.of(toolChunk(0, "call-a", "read_file", "{\"path\":"))))
                + done();
        IOException invalid = assertThrows(IOException.class,
                () -> client(List.of(incomplete), new AtomicReference<>(), new AtomicInteger(), null)
                        .stream(List.of(Message.user("x")), List.of(), ignored -> { }));
        assertTrue(invalid.getMessage().contains("Incomplete tool call"));

        String duplicateIds = event(toolDelta(List.of(
                toolChunk(0, "same-call", "read_file", "{}"),
                toolChunk(1, "same-call", "read_file", "{}")))) + done();
        IOException duplicate = assertThrows(IOException.class,
                () -> client(List.of(duplicateIds), new AtomicReference<>(), new AtomicInteger(), null)
                        .stream(List.of(Message.user("x")), List.of(), ignored -> { }));
        assertTrue(duplicate.getMessage().contains("Duplicate DeepSeek streaming tool-call id"));
    }

    @Test
    void plainTextContainingJsonNeverBecomesAToolCall() throws Exception {
        String jsonText = "{\"tool\":\"create_file\",\"path\":\"x.txt\"}";
        DeepSeekStreamingClient client = client(List.of(
                event(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", jsonText)))))),
                done()), new AtomicReference<>(), new AtomicInteger(), null);
        AtomicReference<String> visible = new AtomicReference<>();

        LLMResponse response = client.stream(List.of(Message.user("x")), List.of(), visible::set);

        assertEquals(jsonText, response.content());
        assertTrue(response.toolCalls().isEmpty());
        assertEquals(jsonText, visible.get());
    }

    @Test
    void streamingRequestsRespectAttemptAndDeadlineBudget() throws Exception {
        LiveTestControl attemptBudget = new LiveTestControl(1, Duration.ofSeconds(5));
        AtomicInteger sent = new AtomicInteger();
        Interceptor responder = chain -> {
            sent.incrementAndGet();
            return sseResponse(chain.request(), done());
        };
        DeepSeekStreamingClient capped = client(responder, attemptBudget);
        capped.stream(List.of(Message.user("one")), List.of(), ignored -> { });
        IOException cap = assertThrows(IOException.class,
                () -> capped.stream(List.of(Message.user("two")), List.of(), ignored -> { }));
        assertTrue(cap.getMessage().contains("REQUEST_BUDGET_EXCEEDED"));
        assertEquals(1, sent.get());
        assertEquals(1, attemptBudget.attempts());

        LiveTestControl timeBudget = new LiveTestControl(2, Duration.ofMillis(30));
        timeBudget.beginTask();
        Thread.sleep(60);
        AtomicInteger afterDeadline = new AtomicInteger();
        DeepSeekStreamingClient timedOut = client(chain -> {
            afterDeadline.incrementAndGet();
            return sseResponse(chain.request(), done());
        }, timeBudget);
        IOException timeout = assertThrows(IOException.class,
                () -> timedOut.stream(List.of(Message.user("late")), List.of(), ignored -> { }));
        assertTrue(timeout.getMessage().contains("TASK_TIMEOUT"));
        assertEquals(0, afterDeadline.get());
    }

    private DeepSeekStreamingClient client(List<String> events, AtomicReference<String> requestBody,
                                           AtomicInteger requestCount, LiveTestControl control) {
        return clientResponses(List.of(String.join("", events)), requestBody, requestCount, control);
    }

    private DeepSeekStreamingClient clientResponses(List<String> responses, AtomicReference<String> requestBody,
                                                    AtomicInteger requestCount) {
        return clientResponses(responses, requestBody, requestCount, null);
    }

    private DeepSeekStreamingClient clientResponses(List<String> responses, AtomicReference<String> requestBody,
                                                    AtomicInteger requestCount, LiveTestControl control) {
        AtomicInteger next = new AtomicInteger();
        Interceptor responder = chain -> {
            requestCount.incrementAndGet();
            Buffer body = new Buffer();
            chain.request().body().writeTo(body);
            requestBody.set(body.readUtf8());
            int index = next.getAndIncrement();
            if (index >= responses.size()) throw new IOException("No scripted response remaining");
            return sseResponse(chain.request(), responses.get(index));
        };
        return client(responder, control);
    }

    private DeepSeekStreamingClient client(Interceptor responder) {
        return client(responder, null);
    }

    private DeepSeekStreamingClient client(Interceptor responder, LiveTestControl control) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().retryOnConnectionFailure(false);
        if (control != null) builder.addInterceptor(LiveTestControl.requestAttemptInterceptor(control));
        builder.addInterceptor(responder);
        OpenAiCompatibleClient delegate = new OpenAiCompatibleClient(
                "https://deepseek.test/chat/completions", "deepseek-flash", "test-key",
                builder.build(), mapper, true);
        return new DeepSeekStreamingClient(delegate);
    }

    private static Response sseResponse(Request request, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(ResponseBody.create(body, SSE)).build();
    }

    private static ResponseBody throwingBody(String prefix) {
        return new ResponseBody() {
            private final BufferedSource source = Okio.buffer(new Source() {
                private final Buffer data = new Buffer().writeUtf8(prefix);
                private boolean failed;
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    if (data.size() > 0) return data.read(sink, byteCount);
                    if (!failed) {
                        failed = true;
                        throw new IOException("simulated connection reset");
                    }
                    return -1;
                }
                @Override public Timeout timeout() { return Timeout.NONE; }
                @Override public void close() { }
            });
            @Override public MediaType contentType() { return SSE; }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return source; }
        };
    }

    private static ResponseBody chunkedBody(String text, int maxChunkSize) {
        Buffer bytes = new Buffer().writeUtf8(text);
        long length = bytes.size();
        BufferedSource source = Okio.buffer(new Source() {
            @Override public long read(Buffer sink, long byteCount) throws IOException {
                return bytes.read(sink, Math.min(byteCount, maxChunkSize));
            }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { bytes.clear(); }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return SSE; }
            @Override public long contentLength() { return length; }
            @Override public BufferedSource source() { return source; }
        };
    }

    private static String event(String json) { return "data: " + json + "\r\n\r\n"; }
    private static String done() { return "data: [DONE]\r\n\r\n"; }

    private String toolDelta(List<Map<String, Object>> calls) throws IOException {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", calls)))));
    }

    private static Map<String, Object> toolChunk(int index, String id, String name, String arguments) {
        Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("index", index);
        if (id != null) call.put("id", id);
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        if (name != null) function.put("name", name);
        if (arguments != null) function.put("arguments", arguments);
        call.put("function", function);
        return call;
    }
}
