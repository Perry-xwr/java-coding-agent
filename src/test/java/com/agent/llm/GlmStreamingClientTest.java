package com.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlmStreamingClientTest {
    private static final MediaType SSE = MediaType.get("text/event-stream; charset=utf-8");

    @Test
    void streamsTextChineseNewlinesAndAccumulatesSplitToolCall() throws Exception {
        String events = event("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}")
                + event("{\"choices\":[{\"delta\":{\"content\":\"lo 世界\\n\"}}]}")
                + event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read_\",\"arguments\":\"{\\\"pa\"}}]}}]}")
                + event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"file\",\"arguments\":\"th\\\":\\\"README.md\\\"}\"}}]}}]}")
                + "data: [DONE]\n\n";
        AtomicReference<String> requestJson = new AtomicReference<>();
        GlmClient client = client(events, requestJson);
        List<String> deltas = new ArrayList<>();

        LLMResponse response = client.stream(
                List.of(Message.user("hello")),
                List.of(),
                deltas::add
        );

        assertEquals(List.of("Hel", "lo 世界\n"), deltas);
        assertEquals("Hello 世界\n", response.content());
        assertEquals(List.of(new ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}")),
                response.toolCalls());
        assertTrue(requestJson.get().contains("\"stream\":true"));
    }

    @Test
    void malformedEventFailsClearlyWithoutCompletingResponse() {
        GlmClient client = client(event("{not-json}"), new AtomicReference<>());

        IOException error = assertThrows(IOException.class, () -> client.stream(
                List.of(Message.user("hello")), List.of(), LlmStreamListener.NO_OP
        ));

        assertTrue(error.getMessage().contains("Malformed GLM streaming event"));
    }

    @Test
    void incompleteToolCallIsRejected() {
        String events = event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}")
                + "data: [DONE]\n\n";
        GlmClient client = client(events, new AtomicReference<>());

        IOException error = assertThrows(IOException.class, () -> client.stream(
                List.of(Message.user("hello")), List.of(), LlmStreamListener.NO_OP
        ));

        assertTrue(error.getMessage().contains("Incomplete tool call"));
    }

    private static GlmClient client(String responseText, AtomicReference<String> requestJson) {
        Interceptor interceptor = chain -> {
            Request request = chain.request();
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestJson.set(buffer.readUtf8());
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(responseText, SSE))
                    .build();
        };
        return new GlmClient(
                "test-key",
                "https://example.test/chat/completions",
                "test-model",
                new OkHttpClient.Builder().addInterceptor(interceptor).build(),
                new ObjectMapper()
        );
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }
}
