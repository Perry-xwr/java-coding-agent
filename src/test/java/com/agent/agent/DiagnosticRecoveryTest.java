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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticRecoveryTest {
    @TempDir
    Path workspace;

    @Test
    void missingImportDiagnosticLeadsToRereadTargetedPatchAndRetest() throws Exception {
        Path source = Files.writeString(
                workspace.resolve("Matcher.java"),
                "package bench;\nclass Matcher { boolean same(String a,String b) { return Objects.equals(a,b); } }\n"
        );
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("test-1", "run_maven_test", "{}"),
                call("read", "read_file", "{\"path\":\"Matcher.java\"}"),
                call("patch", "apply_patch", patch("Matcher.java",
                        "package bench;",
                        "package bench;\nimport java.util.Objects;"
                )),
                call("test-2", "run_maven_test", "{}"),
                answer("Imported and verified.")
        ));
        QueueRunner runner = new QueueRunner(List.of(
                failed(compilerFailure("Matcher.java", 2, "class java.util.Objects")),
                passed()
        ));

        AgentRunResult result = diagnosticAgent(llm, runner, 8)
                .runWithTrajectory("Fix the missing import");

        assertEquals("Imported and verified.", result.finalAnswer());
        assertTrue(Files.readString(source).contains("import java.util.Objects;"));
        AgentStep failedTest = toolSteps(result).stream()
                .filter(step -> "run_maven_test".equals(step.toolName()))
                .findFirst().orElseThrow();
        assertEquals("COMPILATION", failedTest.toolResult().metadata().get("diagnosticType"));
        String observation = llm.histories().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst().orElseThrow().content();
        assertTrue(observation.startsWith("TEST FAILED\n\nKey diagnostics:"));
        assertTrue(observation.indexOf("cannot find symbol") < observation.indexOf("Relevant output"));
    }

    @Test
    void malformedBraceRecoveryRereadsCurrentFileBeforeRepair() throws Exception {
        Path source = Files.writeString(workspace.resolve("Range.java"), "class Range { int sum(){ return 1; } }");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("patch-bad", "apply_patch", patch("Range.java", "return 1;", "return 1; {")),
                call("test-fail", "run_maven_test", "{}"),
                call("read-current", "read_file", "{\"path\":\"Range.java\"}"),
                call("patch-fix", "apply_patch", patch("Range.java", "return 1; {", "return 1;")),
                call("test-pass", "run_maven_test", "{}"),
                answer("Syntax repaired and verified.")
        ));
        QueueRunner runner = new QueueRunner(List.of(
                failed("[ERROR] C:\\work\\Range.java:[1,38] reached end of file while parsing"),
                passed()
        ));

        AgentRunResult result = diagnosticAgent(llm, runner, 9)
                .runWithTrajectory("Repair Range");

        assertEquals("Syntax repaired and verified.", result.finalAnswer());
        assertEquals("class Range { int sum(){ return 1; } }", Files.readString(source));
        assertEquals(1, feedbackCount(result, "PATCH_FRESHNESS_WARNING:"));
        assertTrue(toolSteps(result).stream().anyMatch(step ->
                "read_file".equals(step.toolName())
                        && "Range.java".equals(step.arguments().get("path"))));
    }

    @Test
    void patchWithoutRereadGetsOneFreshnessWarning() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("patch-1", "apply_patch", patch("old", "broken")),
                call("test-1", "run_maven_test", "{}"),
                call("patch-2", "apply_patch", patch("broken", "fixed")),
                call("test-2", "run_maven_test", "{}"),
                answer("Recovered.")
        ));

        AgentRunResult result = diagnosticAgent(
                llm,
                new QueueRunner(List.of(
                        failed("[ERROR] C:\\work\\App.java:[1,1] illegal start of expression"),
                        passed()
                )),
                8
        ).runWithTrajectory("Fix App");

        assertEquals(1, feedbackCount(result, "PATCH_FRESHNESS_WARNING:"));
        assertEquals("Recovered.", result.finalAnswer());
    }

    @Test
    void noEffectPatchIsFailureAndDoesNotSatisfyCompletion() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "same");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("noop", "apply_patch", patch("same", "same")),
                answer("Done."),
                answer("Still done.")
        ));

        AgentRunResult result = diagnosticAgent(llm, new QueueRunner(List.of()), 4)
                .runWithTrajectory("Change App");

        AgentStep patch = toolSteps(result).get(0);
        assertFalse(patch.toolResult().success());
        assertEquals(ToolErrorCode.NO_EFFECT_CHANGE, patch.toolResult().errorCode());
        assertEquals(1, feedbackCount(result, "PREMATURE_FINAL_GUARD:"));
    }

    @Test
    void approachingLimitEmitsOnlyOneBudgetWarning() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "content");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("s1", "search_code", "{\"keyword\":\"missing-one\"}"),
                call("s2", "search_code", "{\"keyword\":\"missing-two\"}"),
                answer("No change."),
                answer("Stopping.")
        ));

        AgentRunResult result = diagnosticAgent(llm, new QueueRunner(List.of()), 4)
                .runWithTrajectory("Change App");

        assertEquals(1, feedbackCount(result, "STEP_BUDGET_WARNING:"));
        assertEquals(1, feedbackCount(result, "PREMATURE_FINAL_GUARD:"));
        assertEquals("Stopping.", result.finalAnswer());
    }

    @Test
    void threeContextActionsAfterFailureProduceOneSearchChurnWarning() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "old");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(
                call("patch-bad", "apply_patch", patch("old", "broken")),
                call("test-fail", "run_maven_test", "{}"),
                call("read", "read_file", "{\"path\":\"App.java\"}"),
                call("search-1", "search_code", "{\"keyword\":\"broken\"}"),
                call("search-2", "search_code", "{\"keyword\":\"class\"}"),
                call("patch-fix", "apply_patch", patch("broken", "fixed")),
                call("test-pass", "run_maven_test", "{}"),
                answer("Recovered.")
        ));

        AgentRunResult result = diagnosticAgent(
                llm,
                new QueueRunner(List.of(
                        failed("[ERROR] C:\\work\\App.java:[1,1] illegal start of expression"),
                        passed()
                )),
                10
        ).runWithTrajectory("Fix App");

        assertEquals(1, feedbackCount(result, "SEARCH_CHURN_WARNING:"));
        assertEquals("Recovered.", result.finalAnswer());
    }

    @Test
    void readOnlyModeReceivesNoDiagnosticBudgetOrFreshnessFeedback() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "content");
        RecordingFakeLLM llm = new RecordingFakeLLM(List.of(answer("Explanation.")));
        Agent agent = new Agent(
                llm,
                ToolRegistry.withCodingTools(workspace, new QueueRunner(List.of())),
                "read only",
                2,
                TaskMode.READ_ONLY,
                true
        );

        AgentRunResult result = agent.runWithTrajectory("Explain App");

        assertEquals("Explanation.", result.finalAnswer());
        assertEquals(0, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK).count());
    }

    private Agent diagnosticAgent(LLMClient llm, ProcessRunner runner, int maxSteps) {
        return new Agent(
                llm,
                ToolRegistry.withActionOrientedCodingTools(
                        workspace,
                        runner,
                        workspace.resolve(".m2/repository")
                ),
                "diagnostic recovery",
                maxSteps,
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

    private static String patch(String oldText, String newText) {
        return patch("App.java", oldText, newText);
    }

    private static String patch(String path, String oldText, String newText) {
        return "{\"path\":\"" + path
                + "\",\"oldText\":\"" + escape(oldText)
                + "\",\"newText\":\"" + escape(newText) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    private static LLMResponse call(String id, String tool, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, tool, arguments)));
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static ProcessExecutionResult failed(String output) {
        return new ProcessExecutionResult(1, false, output, false, 3);
    }

    private static ProcessExecutionResult passed() {
        return new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 3);
    }

    private static String compilerFailure(String file, int line, String symbol) {
        return "[ERROR] C:\\work\\" + file + ":[" + line + ",20] cannot find symbol\n"
                + "[ERROR]   symbol:   " + symbol;
    }

    private static final class RecordingFakeLLM implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> histories = new ArrayList<>();
        private RecordingFakeLLM(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }
        @Override
        public LLMResponse chat(List<Message> messages) {
            histories.add(List.copyOf(messages));
            return responses.removeFirst();
        }
        private List<List<Message>> histories() {
            return histories;
        }
    }

    private static final class QueueRunner implements ProcessRunner {
        private final Deque<ProcessExecutionResult> results;
        private QueueRunner(List<ProcessExecutionResult> results) {
            this.results = new ArrayDeque<>(results);
        }
        @Override
        public ProcessExecutionResult run(
                List<String> command, Path workingDirectory, Duration timeout, int maxOutputBytes
        ) {
            return results.removeFirst();
        }
    }
}
