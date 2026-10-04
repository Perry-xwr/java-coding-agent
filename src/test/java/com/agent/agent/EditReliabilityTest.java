package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
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

class EditReliabilityTest {
    @TempDir
    Path workspace;

    @Test
    void invalidPatchArgumentsCannotFinalizeSuccessAndRecoveryRereads() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("invalid", "apply_patch", patch("App.java", "", "new")),
                answer("The file was updated successfully."),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                call("read-after-patch", "read_file", "{\"path\":\"App.java\"}"),
                call("test", "run_maven_test", "{}"),
                answer("Recovered and verified.")
        ), List.of(passed())).runWithTrajectory("Update App.java");

        assertEquals("Recovered and verified.", result.finalAnswer());
        assertEquals("new", Files.readString(workspace.resolve("App.java")));
        assertEquals(1, feedbackCount(result, "EDIT_RECOVERY_INVALID_ARGUMENTS:"));
        assertEquals(1, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD)
                .filter(step -> "WORKSPACE_CHANGE_REQUIRED".equals(step.errorMessage())).count());
    }

    @Test
    void repeatedNoEffectRequiresRereadBeforeAnotherEdit() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "same");
        AgentRunResult result = agent(List.of(
                call("noop-1", "apply_patch", patch("App.java", "same", "same")),
                call("noop-2", "apply_patch", patch("App.java", "same", "same")),
                call("blocked", "apply_patch", patch("App.java", "same", "new")),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("patch", "apply_patch", patch("App.java", "same", "new")),
                call("read-after-patch", "read_file", "{\"path\":\"App.java\"}"),
                call("test", "run_maven_test", "{}"),
                answer("Recovered after rereading.")
        ), List.of(passed())).runWithTrajectory("Change App.java");

        assertEquals("new", Files.readString(workspace.resolve("App.java")));
        assertEquals(2, toolSteps(result).stream()
                .filter(step -> step.toolResult().errorCode() == ToolErrorCode.NO_EFFECT_CHANGE)
                .count());
        assertTrue(toolSteps(result).stream().anyMatch(step ->
                step.toolResult().errorCode() == ToolErrorCode.STALE_EDIT_CONTEXT));
        assertTrue(feedbackCount(result, "EDIT_RECOVERY_NO_EFFECT_CHANGE:") >= 2);
    }

    @Test
    void existingCreateTargetRecoversThroughReadAndPatch() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("create", "create_file", "{\"path\":\"App.java\",\"content\":\"new\"}"),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                call("read-after-patch", "read_file", "{\"path\":\"App.java\"}"),
                call("test", "run_maven_test", "{}"),
                answer("Existing file updated and verified.")
        ), List.of(passed())).runWithTrajectory("Create or update App.java");

        assertEquals("new", Files.readString(workspace.resolve("App.java")));
        assertEquals(1, feedbackCount(result, "CREATE_RECOVERY_FILE_EXISTS:"));
        assertTrue(result.trajectory().completed());
    }

    @Test
    void allMutationFailuresProduceStructuredFailureInsteadOfSuccess() {
        List<LLMResponse> responses = new java.util.ArrayList<>();
        responses.add(call("invalid", "apply_patch", patch("App.java", "", "new")));
        int maxIterations = 12;
        for (int index = 0; index < maxIterations; index++) {
            responses.add(answer("The modification is complete."));
        }
        AgentRunResult result = agent(responses, List.of()).runWithTrajectory("Modify App.java");

        assertFalse(result.trajectory().completed());
        assertEquals(TerminationReason.MAX_STEPS, result.trajectory().terminationReason());
        assertEquals(maxIterations - 1, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD).count());
    }

    @Test
    void successfulMutationCanFinalizeNormally() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("test", "run_maven_test", "{}"),
                answer("Updated and verified.")
        ), List.of(passed())).runWithTrajectory("Update App.java");

        assertTrue(result.trajectory().completed());
        assertEquals("Updated and verified.", result.finalAnswer());
    }

    @Test
    void alreadySatisfiedFileCanFinishAfterFreshReadEvidence() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "desired");
        AgentRunResult result = agent(List.of(
                call("read-1", "read_file", "{\"path\":\"App.java\"}"),
                answer("It already satisfies the request."),
                call("read-2", "read_file", "{\"path\":\"App.java\"}"),
                answer("No modification is required; the current file already satisfies the request.")
        ), List.of()).runWithTrajectory("Ensure App.java contains desired");

        assertTrue(result.trajectory().completed());
        assertEquals("No modification is required; the current file already satisfies the request.",
                result.finalAnswer());
        assertEquals(1, feedbackCount(result, "PREMATURE_FINAL_GUARD:"));
    }

    @Test
    void successfulMutationStopsRemainingWritesInSameToolCallBatch() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                calls(
                        new ToolCall("patch", "apply_patch", patch("App.java", "old", "new")),
                        new ToolCall("unsafe-followup", "apply_patch", patch("App.java", "new", "wrong"))
                ),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("test", "run_maven_test", "{}"),
                answer("Updated and verified.")
        ), List.of(passed())).runWithTrajectory("Update App.java");

        assertEquals("new", Files.readString(workspace.resolve("App.java")));
        assertFalse(toolSteps(result).stream().anyMatch(step ->
                "unsafe-followup".equals(step.toolCallId())));
        assertEquals(0, feedbackCount(result, "POST_MUTATION_READ_REQUIRED:"));
        assertEquals(1, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.AUTO_REREAD).count());
    }

    private Agent agent(List<LLMResponse> responses, List<ProcessExecutionResult> processResults) {
        return new Agent(
                fake(responses),
                ToolRegistry.withCliCodingTools(
                        workspace,
                        new QueueRunner(processResults),
                        workspace.resolve(".m2/repository")
                ),
                "edit reliability test",
                12,
                TaskMode.CODE_MODIFICATION,
                true
        );
    }

    private static List<AgentStep> toolSteps(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();
    }

    private static long feedbackCount(AgentRunResult result, String prefix) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK)
                .filter(step -> step.errorMessage().startsWith(prefix))
                .count();
    }

    private static String patch(String path, String oldText, String newText) {
        return "{\"path\":\"" + path + "\",\"oldText\":\"" + oldText
                + "\",\"newText\":\"" + newText + "\"}";
    }

    private static LLMResponse call(String id, String tool, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, tool, arguments)));
    }

    private static LLMResponse calls(ToolCall... calls) {
        return new LLMResponse("", List.of(calls));
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static LLMClient fake(List<LLMResponse> responses) {
        Deque<LLMResponse> queue = new ArrayDeque<>(responses);
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                return queue.removeFirst();
            }
        };
    }

    private static ProcessExecutionResult passed() {
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
