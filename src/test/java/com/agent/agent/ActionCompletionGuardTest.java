package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCompletionGuardTest {
    @TempDir
    Path workspace;

    @Test
    void prematureFinalReceivesFeedbackThenPatchTestAndFinalComplete() throws Exception {
        Path source = Files.writeString(workspace.resolve("App.java"), "return a - b;");
        Agent agent = actionAgent(List.of(
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                answer("Replace subtraction with addition."),
                call("patch", "apply_patch", patch("a - b", "a + b")),
                call("test", "run_maven_test", "{}"),
                answer("Fixed and verified.")
        ), new QueueRunner(List.of(successfulTest())));

        AgentRunResult result = agent.runWithTrajectory("Fix add");

        assertEquals("Fixed and verified.", result.finalAnswer());
        assertTrue(Files.readString(source).contains("a + b"));
        assertEquals(1, feedbackCount(result, "PREMATURE_FINAL_GUARD:"));
        assertTrue(agent.history().stream().anyMatch(message ->
                message.role().equals("system")
                        && message.content().startsWith("PREMATURE_FINAL_GUARD:")));
    }

    @Test
    void patchedButUntestedFinalReceivesOneValidationReminder() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        Agent agent = actionAgent(List.of(
                call("patch", "apply_patch", patch("old", "new")),
                answer("Done."),
                call("test", "run_maven_test", "{}"),
                answer("Verified.")
        ), new QueueRunner(List.of(successfulTest())));

        AgentRunResult result = agent.runWithTrajectory("Change App");

        assertEquals("Verified.", result.finalAnswer());
        assertEquals(1, feedbackCount(result, "VALIDATION_GUARD:"));
    }

    @Test
    void failedTestFinalReceivesRecoveryFeedbackThenCanRepair() throws Exception {
        Path source = Files.writeString(workspace.resolve("App.java"), "a - b");
        Agent agent = actionAgent(List.of(
                call("patch-wrong", "apply_patch", patch("a - b", "a * b")),
                call("test-fail", "run_maven_test", "{}"),
                answer("Finished."),
                call("patch-fix", "apply_patch", patch("a * b", "a + b")),
                call("test-pass", "run_maven_test", "{}"),
                answer("Fixed after recovery.")
        ), new QueueRunner(List.of(
                new ProcessExecutionResult(1, false, "expected 5", false, 2),
                successfulTest()
        )));

        AgentRunResult result = agent.runWithTrajectory("Fix add");

        assertEquals("Fixed after recovery.", result.finalAnswer());
        assertTrue(Files.readString(source).contains("a + b"));
        assertEquals(1, feedbackCount(result, "TEST_FAILED_GUARD:"));
    }

    @Test
    void readOnlyTaskCanFinishWithoutPatch() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "content");
        Agent agent = new Agent(
                fake(List.of(
                        call("read", "read_file", "{\"path\":\"App.java\"}"),
                        answer("The file contains content.")
                )),
                ToolRegistry.withCodingTools(workspace, new QueueRunner(List.of())),
                "Read-only assistant",
                4,
                TaskMode.READ_ONLY
        );

        AgentRunResult result = agent.runWithTrajectory("Explain App.java");

        assertEquals("The file contains content.", result.finalAnswer());
        assertEquals(0, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK).count());
    }

    @Test
    void invalidArgumentsCanBeCorrectedBeforePatchTestAndFinal() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        Agent agent = actionAgent(List.of(
                call("bad-list", "list_files", "{\"path\":\"\"}"),
                call("good-list", "list_files", "{\"path\":\".\"}"),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("patch", "apply_patch", patch("old", "new")),
                call("test", "run_maven_test", "{}"),
                answer("Recovered and verified.")
        ), new QueueRunner(List.of(successfulTest())));

        AgentRunResult result = agent.runWithTrajectory("Change App");

        AgentStep invalid = result.trajectory().steps().get(0);
        assertFalse(invalid.toolResult().success());
        assertEquals("Recovered and verified.", result.finalAnswer());
        assertTrue(Files.readString(workspace.resolve("App.java")).contains("new"));
    }

    @Test
    void repeatedIdenticalFailedActionProducesOneObservableWarning() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "actual");
        String missingPatch = patch("missing", "new");
        Agent agent = actionAgent(List.of(
                call("p1", "apply_patch", missingPatch),
                call("p2", "apply_patch", missingPatch),
                call("p3", "apply_patch", patch("actual", "new")),
                call("test", "run_maven_test", "{}"),
                answer("Recovered.")
        ), new QueueRunner(List.of(successfulTest())));

        AgentRunResult result = agent.runWithTrajectory("Try patch");

        assertEquals(1, feedbackCount(result, "REPEATED_ACTION_WARNING:"));
        assertEquals("Recovered.", result.finalAnswer());
    }

    private Agent actionAgent(List<LLMResponse> responses, ProcessRunner runner) {
        return new Agent(
                fake(responses),
                ToolRegistry.withActionOrientedCodingTools(
                        workspace,
                        runner,
                        workspace.resolve(".m2/repository")
                ),
                "Action-oriented test agent",
                10,
                TaskMode.CODE_MODIFICATION
        );
    }

    private static long feedbackCount(AgentRunResult result, String prefix) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK)
                .filter(step -> step.errorMessage().startsWith(prefix))
                .count();
    }

    private static String patch(String oldText, String newText) {
        return "{\"path\":\"App.java\",\"oldText\":\"" + oldText
                + "\",\"newText\":\"" + newText + "\"}";
    }

    private static LLMResponse call(String id, String tool, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, tool, arguments)));
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static LLMClient fake(List<LLMResponse> values) {
        Deque<LLMResponse> responses = new ArrayDeque<>(values);
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                return responses.removeFirst();
            }
        };
    }

    private static ProcessExecutionResult successfulTest() {
        return new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 2);
    }

    private static final class QueueRunner implements ProcessRunner {
        private final Deque<ProcessExecutionResult> results;
        private QueueRunner(List<ProcessExecutionResult> results) {
            this.results = new ArrayDeque<>(results);
        }
        @Override
        public ProcessExecutionResult run(
                List<String> command,
                Path workingDirectory,
                Duration timeout,
                int maxOutputBytes
        ) {
            return results.removeFirst();
        }
    }
}
