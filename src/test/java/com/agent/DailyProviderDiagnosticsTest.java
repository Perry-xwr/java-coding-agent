package com.agent;

import com.agent.llm.DailyDeepSeekClient;
import com.agent.TuiSessionController;
import com.agent.tui.runtime.UiEventLoop;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DailyProviderDiagnosticsTest {
    @TempDir Path temp;

    private Response response(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("offline fixture").body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }

    private void submit(TuiSessionController controller, String message) throws Exception {
        controller.loop().edit(UiEventLoop.Edit.TEXT, message);
        assertNotNull(controller.submit());
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (controller.loop().snapshot().current().turn().running() && System.nanoTime() < deadline) {
            controller.pump(); Thread.sleep(2);
        }
        controller.pump();
    }

    @Test void httpFailureIsSafelyClassifiedAndSurvivesRestart() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("daily"));
        AtomicInteger requests = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 requests.incrementAndGet();
                 return response(request, 503, "PRIVATE_BODY_MUST_NOT_BE_STORED");
             });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            submit(controller, "解释缓存；不要修改文件。 PRIVATE_PROMPT_SENTINEL");
            assertEquals("FAILED", controller.loop().snapshot().current().turn().status());
            assertEquals(1, requests.get(), "failure must not trigger a retry");
            var saved = new DailyProviderDiagnosticStore(workspace).read();
            assertNotNull(saved);
            assertEquals("FAILED: LLM_ERROR", saved.taskStatus());
            assertEquals("503", saved.httpStatus());
            assertEquals("HTTP_STATUS", saved.failureCategory());
            assertEquals(1, saved.httpAttempts());
            String file = Files.readString(workspace.resolve(".agent-cli/diagnostics/daily-provider-last.json"));
            assertFalse(file.contains("PRIVATE_BODY_MUST_NOT_BE_STORED"));
            assertFalse(file.contains("PRIVATE_PROMPT_SENTINEL"));
        }
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> { throw new AssertionError("restart must not call provider"); });
             var adapter = new DailyAgentAdapter(lease, client, null)) {
            String status = adapter.validationStatus();
            assertTrue(status.contains("last=FAILED: LLM_ERROR http=503 provider=FAILED failure=HTTP_STATUS attempts=1"), status);
            assertEquals(1, requests.get());
        }
    }

    @Test void transportFailureRecordsUnknownHttpWithoutRawException() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("transport"));
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> { throw new IOException("PRIVATE_TRANSPORT_TEXT"); });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            submit(controller, "解释缓存；不要修改文件。 PRIVATE_PROMPT_SENTINEL");
            var saved = new DailyProviderDiagnosticStore(workspace).read();
            assertNotNull(saved);
            assertEquals("UNKNOWN", saved.httpStatus());
            assertEquals("PROVIDER_IO_ERROR", saved.failureCategory());
            String file = Files.readString(workspace.resolve(".agent-cli/diagnostics/daily-provider-last.json"));
            assertFalse(file.contains("PRIVATE_TRANSPORT_TEXT"));
            assertFalse(file.contains("PRIVATE_PROMPT_SENTINEL"));
        }
    }
}
