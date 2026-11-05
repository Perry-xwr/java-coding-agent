package com.agent.diagnostics;

import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.llm.LiveTestControl;
import com.agent.llm.ModelRequestUsage;
import com.agent.llm.RequestContextMetrics;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveTestDiagnosticsTest {
    @TempDir Path temp;

    @AfterEach
    void cleanup() {
        System.clearProperty("agent.live-test-mode");
        System.clearProperty("agent.live-test-diagnostics");
        System.clearProperty("agent.live-test-capture-edit-context");
        LiveTestDiagnostics.finishRun("TEST_CLEANUP", false, "TEST");
    }

    @Test
    void traceDistinguishesProposalToolFailureFileHashesVerificationAndCompletionWithoutSecrets() throws Exception {
        System.setProperty("agent.live-test-mode", "true");
        System.setProperty("agent.live-test-diagnostics", "true");
        Path workspace = ignoredWorkspace("isolated-task");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("sample.py");
        Files.writeString(target, "def broken(:\n    pass\n");

        LiveTestDiagnostics.configureProvider("GLM", "glm-4-flash", "https://example.test/v4/chat/completions?key=DO_NOT_LOG");
        LiveTestDiagnostics.beginRun("test-run", "private prompt should not be stored", workspace);
        LiveTestDiagnostics.recordModelRequest(1, "https://example.test/v4/chat/completions?key=DO_NOT_LOG",
                "HTTP_EXCHANGE_RESERVED");
        LiveTestDiagnostics.recordRequestUsage(new ModelRequestUsage(1, "DEEPSEEK", "deepseek-flash",
                "https://example.test/chat/completions?credential=DO_NOT_LOG", "test-run", "CODE", 1,
                "SUCCEEDED", 200, true, 12, 3, 15, 8, 4,
                new RequestContextMetrics(2, 5, 321, Map.of("system", 1, "user", 1),
                        Map.of("system", 12L, "user", 5L))));
        LiveTestDiagnostics.recordProviderFailure(new java.net.SocketTimeoutException("private transport detail"));
        ToolCall call = new ToolCall("call-1", "apply_context_patch", "{}");
        LiveTestDiagnostics.recordModelResponse(true, List.of(call));
        Map<String, Object> arguments = Map.of(
                "path", "sample.py",
                "expectedContent", "def broken(:\n    pass\n",
                "edits", List.of(Map.of("search", "def broken(:", "replace", "def fixed():")));
        String before = LiveTestDiagnostics.beforeToolCall(call.id(), call.name(), arguments, "{}", workspace);
        Files.writeString(target, "def fixed():\n    pass\n");
        LiveTestDiagnostics.afterToolCall(call, arguments,
                ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "synthetic tool rejection"), before, workspace);
        ToolCall secondCall = new ToolCall("call-2", "apply_context_patch", "{}");
        LiveTestDiagnostics.afterToolCall(secondCall, arguments,
                ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, "synthetic filesystem error"), before, workspace);
        VerificationResult failedVerification = new VerificationResult(VerificationStatus.FAIL,
                Path.of("sample.py"), "python-py-compile", "SyntaxError: invalid syntax at line 1", "", 1);
        LiveTestDiagnostics.recordVerification(failedVerification);
        LiveTestDiagnostics.recordVerificationFeedbackQueued(failedVerification);
        LiveTestDiagnostics.recordRepair("sample.py", "apply_context_patch");
        LiveTestDiagnostics.recordVerification(new VerificationResult(VerificationStatus.PASS,
                Path.of("sample.py"), "python-py-compile", "", "", 2));
        assertTrue(LiveTestDiagnostics.finishRun("FINAL_ANSWER", true, "ALLOWED"));

        Path trace = workspace.resolve("p1.5b-diagnostics").resolve("test-run.jsonl");
        String jsonl = Files.readString(trace);
        assertTrue(jsonl.contains("model_request"));
        assertTrue(jsonl.contains("model_usage"));
        assertTrue(jsonl.contains("requestSequence\":1"));
        assertTrue(jsonl.contains("payloadBytes\":321"));
        assertTrue(jsonl.contains("cacheHitTokens\":8"));
        assertTrue(jsonl.contains("provider_failure"));
        assertTrue(jsonl.contains("TIMEOUT"));
        assertTrue(jsonl.contains("tool_call_proposed"));
        assertTrue(jsonl.contains("modelArgumentsParseStatus\":\"JSON_OBJECT\""));
        assertTrue(jsonl.contains("modelArgumentsSha256"));
        assertTrue(jsonl.contains("model_response"));
        assertTrue(jsonl.contains("hasToolCalls\":true"));
        assertTrue(jsonl.contains("tool_result"));
        assertTrue(jsonl.contains("fileSha256Before"));
        assertTrue(jsonl.contains("fileSha256After"));
        assertTrue(jsonl.contains("INVALID_ARGUMENTS"));
        assertTrue(jsonl.contains("TOOL_EXECUTION_ERROR"));
        assertTrue(jsonl.contains("python-py-compile"));
        assertTrue(jsonl.contains("verification_feedback_queued"));
        assertTrue(jsonl.contains("repair_followup"));
        assertTrue(jsonl.contains("completionGuard"));
        assertFalse(jsonl.contains("private prompt should not be stored"));
        assertFalse(jsonl.contains("DO_NOT_LOG"));
        assertFalse(jsonl.contains("credential"));
        assertFalse(jsonl.contains("Authorization"));
        assertFalse(jsonl.contains("def broken(:"));

        LiveTestDiagnostics.beginRun("bad-args", "private prompt", workspace);
        LiveTestDiagnostics.beforeToolCall("bad-call", "apply_context_patch", Map.of(),
                "not-json DO_NOT_LOG_SECRET", workspace);
        assertTrue(LiveTestDiagnostics.finishRun("TOOL_FAILURE", false, "BLOCKED"));
        String badArgumentsTrace = Files.readString(workspace.resolve("p1.5b-diagnostics/bad-args.jsonl"));
        assertTrue(badArgumentsTrace.contains("INVALID_OR_NON_OBJECT_JSON"));
        assertFalse(badArgumentsTrace.contains("not-json"));
        assertFalse(badArgumentsTrace.contains("DO_NOT_LOG_SECRET"));
    }

    @Test
    void traceWriteFailureIsObservableAndBlocksFurtherRequests() throws Exception {
        System.setProperty("agent.live-test-mode", "true");
        System.setProperty("agent.live-test-diagnostics", "true");
        Path workspace = ignoredWorkspace("broken-trace");
        LiveTestDiagnostics.beginRun("blocked", "task", workspace);
        Path trace = workspace.resolve("p1.5b-diagnostics").resolve("blocked.jsonl");
        Files.delete(trace);
        Files.createDirectory(trace);
        LiveTestDiagnostics.recordCompletion("FINAL_ANSWER", true, "ALLOWED");
        assertFalse(LiveTestDiagnostics.finishRun("FINAL_ANSWER", true, "ALLOWED"));
    }

    private Path ignoredWorkspace(String name) throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".gitignore"), "benchmark-runs/\n");
        Path workspace = repo.resolve("benchmark-runs").resolve(name);
        Files.createDirectories(workspace);
        return workspace;
    }
}
