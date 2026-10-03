package com.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlmProviderNetworkingTest {
    @Test
    void providerErrorsRedactApiKeyFromExceptionAndLogs() throws Exception {
        String secret = "test-api-key-never-log";
        GlmClient client = client(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("denied " + secret,
                        MediaType.get("application/json; charset=utf-8")))
                .build(), secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            IOException error = assertThrows(IOException.class,
                    () -> client.chat(List.of(Message.user("hello"))));
            assertTrue(error.getMessage().contains("GLM HTTP provider error 401"));
            assertFalse(error.getMessage().contains(secret));
        } finally {
            System.setErr(original);
        }
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(secret));
    }

    @Test
    void classifiesConnectionRefusedResetAndTimeoutWithoutRetry() {
        assertTransportCategory(new ConnectException("Connection refused"), "GLM connection refused");
        assertTransportCategory(new SocketException("Connection reset"), "GLM connection reset");
        assertTransportCategory(new SocketTimeoutException("timeout"), "GLM transport timeout");
    }

    @Test
    void malformedProviderJsonIsReportedAsResponseParseError() {
        GlmClient client = client(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("not-json test-key", MediaType.get("application/json")))
                .build(), "test-key");

        IOException error = assertThrows(IOException.class,
                () -> client.chat(List.of(Message.user("hello"))));
        assertTrue(error.getMessage().contains("GLM response parse error"));
        assertFalse(error.toString().contains("test-key"));
    }

    private static void assertTransportCategory(IOException failure, String expected) {
        GlmClient client = client(chain -> {
            throw failure;
        }, "test-key");
        IOException error = assertThrows(IOException.class,
                () -> client.chat(List.of(Message.user("hello"))));
        assertTrue(error.getMessage().contains(expected), error::getMessage);
    }

    private static GlmClient client(Interceptor interceptor, String apiKey) {
        OkHttpClient http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(interceptor)
                .build();
        return new GlmClient(apiKey, "https://example.test/v4/chat/completions", "glm-4-flash",
                http, new ObjectMapper());
    }
}
