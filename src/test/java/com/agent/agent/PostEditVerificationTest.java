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
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostEditVerificationTest {
    @TempDir
    Path workspace;

    @Test
    void mutationCannotFinalizeUntilChangedFileIsReread() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                answer("Done."),
                call("read", "read_file", path("App.java")),
                call("test", "run_maven_test", "{}"),
                answer("Verified.")
        ), List.of(passed())).runWithTrajectory("Update App.java");

        assertTrue(result.trajectory().completed());
        assertEquals("Verified.", result.finalAnswer());
        assertEquals(1, feedbackCount(result, "POST_MUTATION_READ_GUARD:"));
    }

    @Test
    void nonCodeMutationOnlyRequiresReread() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("README.md", "old", "new")),
                call("read", "read_file", path("README.md")),
                answer("README updated and reread.")
        ), List.of()).runWithTrajectory("Update README.md");

        assertTrue(result.trajectory().completed());
        assertEquals("new", Files.readString(workspace.resolve("README.md")));
        assertEquals(0, toolCount(result, "run_maven_test"));
    }

    @Test
    void javaMutationRereadAndPassingMavenTestCanComplete() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                call("read", "read_file", path("App.java")),
                call("test", "run_maven_test", "{}"),
                answer("Java change verified.")
        ), List.of(passed())).runWithTrajectory("Update App.java");

        assertTrue(result.trajectory().completed());
        assertEquals("Java change verified.", result.finalAnswer());
    }

    @Test
    void failedMavenTestPreventsSuccessfulCompletion() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("App.java", "old", "broken")),
                call("read", "read_file", path("App.java")),
                call("test", "run_maven_test", "{}"),
                answer("Done."),
                answer("Still done.")
        ), List.of(failed())).runWithTrajectory("Update App.java");

        assertFalse(result.trajectory().completed());
        assertEquals(1, feedbackCount(result, "TEST_FAILED_GUARD:"));
        assertEquals(1, feedbackCount(result, "TEST_VERIFICATION_FAILURE:"));
        assertTrue(result.finalAnswer().contains("latest Maven test did not pass"));
    }

    @Test
    void javaMutationWithoutMavenEvidenceCannotClaimVerified() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        AgentRunResult result = agent(List.of(
                call("patch", "apply_patch", patch("App.java", "old", "new")),
                call("read", "read_file", path("App.java")),
                answer("Tests passed."),
                answer("The task is complete.")
        ), List.of()).runWithTrajectory("Update App.java");

        assertFalse(result.trajectory().completed());
        assertEquals(1, feedbackCount(result, "JAVA_VERIFICATION_GUARD:"));
        assertEquals(1, feedbackCount(result, "JAVA_VERIFICATION_FAILURE:"));
        assertFalse(result.finalAnswer().contains("Tests passed"));
    }

    @Test
    void createdNonCodeFileCanCompleteAfterReread() throws Exception {
        AgentRunResult result = agent(List.of(
                call("create", "create_file", "{\"path\":\"notes.txt\",\"content\":\"hello\"}"),
                call("read", "read_file", path("notes.txt")),
                answer("Created and reread.")
        ), List.of()).runWithTrajectory("Create notes.txt");

        assertTrue(result.trajectory().completed());
        assertEquals("hello", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test
    void infrastructureFailureAllowsOnlyAnExplicitPartialReport() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        ProcessRunner unavailable = (command, directory, timeout, maxOutput) -> {
            throw new IOException("Maven executable unavailable");
        };
        AgentRunResult result = new Agent(
                fake(List.of(
                        call("patch", "apply_patch", patch("App.java", "old", "new")),
                        call("read", "read_file", path("App.java")),
                        call("test", "run_maven_test", "{}"),
                        answer("Done."),
                        answer("The change was written and reread, but Maven could not start.")
                )),
                ToolRegistry.withCliCodingTools(
                        workspace, unavailable, workspace.resolve(".m2/repository")
                ),
                "post-edit verification test",
                8,
                TaskMode.CODE_MODIFICATION,
                true
        ).runWithTrajectory("Update App.java");

        assertTrue(result.trajectory().completed());
        assertEquals(1, feedbackCount(result, "TEST_INFRASTRUCTURE_WARNING:"));
        assertEquals("The change was written and reread, but Maven could not start.",
                result.finalAnswer());
    }

    private Agent agent(List<LLMResponse> responses, List<ProcessExecutionResult> processResults) {
        return new Agent(
                fake(responses),
                ToolRegistry.withCliCodingTools(
                        workspace,
                        new QueueRunner(processResults),
                        workspace.resolve(".m2/repository")
                ),
                "post-edit verification test",
                12,
                TaskMode.CODE_MODIFICATION,
                true
        );
    }

    private static long feedbackCount(AgentRunResult result, String prefix) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK)
                .filter(step -> step.errorMessage().startsWith(prefix))
                .count();
    }

    private static long toolCount(AgentRunResult result, String toolName) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .filter(step -> toolName.equals(step.toolName()))
                .count();
    }

    private static String patch(String path, String oldText, String newText) {
        return "{\"path\":\"" + path + "\",\"oldText\":\"" + oldText
                + "\",\"newText\":\"" + newText + "\"}";
    }

    private static String path(String path) {
        return "{\"path\":\"" + path + "\"}";
    }

    private static LLMResponse call(String id, String tool, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, tool, arguments)));
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

    private static ProcessExecutionResult failed() {
        return new ProcessExecutionResult(1, false, "Tests run: 1, Failures: 1", false, 2);
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
