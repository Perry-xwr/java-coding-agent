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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningProgressTest {
    @TempDir
    Path workspace;

    @Test
    void pendingSecondRequirementBlocksFinalThenCanBeCompleted() throws Exception {
        write("MaxFinder.java", "int max = 0;\n");
        CapturingClient client = client(List.of(
                response(plan(false, false, false), call("p1", "apply_patch", patch(
                        "MaxFinder.java", "int max = 0;", "int max = Integer.MIN_VALUE;"
                ))),
                response("", call("read-1", "read_file",
                        "{\"path\":\"MaxFinder.java\"}")),
                response("", call("test-1", "run_maven_test", "{}")),
                answer("Done too early"),
                response(plan(true, true, false), call("p2", "apply_patch", patch(
                        "MaxFinder.java", "int max = Integer.MIN_VALUE;",
                        "if (values.length == 0) throw new IllegalArgumentException();\n"
                                + "int max = Integer.MIN_VALUE;"
                ))),
                response("", call("read", "read_file",
                        "{\"path\":\"MaxFinder.java\"}")),
                response(plan(true, true, true), call("t1", "run_maven_test", "{}")),
                answer("Done")
        ));

        AgentRunResult result = planningAgent(client, passingRunner(), 8)
                .runWithTrajectory("Fix negatives and reject empty arrays");

        assertEquals("Done", result.finalAnswer());
        assertEquals(2, count(result, AgentActionType.PLAN_UPDATED));
        assertEquals(1, count(result, AgentActionType.PLAN_CREATED));
        assertEquals(1, count(result, AgentActionType.PLAN_COMPLETION_FEEDBACK));
        assertEquals(3, result.trajectory().plan().completedRequirementCount());
        assertTrue(Files.readString(workspace.resolve("MaxFinder.java"))
                .contains("IllegalArgumentException"));
        assertTrue(client.sawCompactProgress());
        assertEquals(AgentActionType.PLAN_CREATED,
                result.trajectory().steps().get(0).actionType());
    }

    @Test
    void fullyCompletedRequirementsNeedNoWarning() throws Exception {
        write("App.java", "A B\n");
        CapturingClient client = client(List.of(
                response(plan(false, false, false),
                        call("p1", "apply_patch", patch("App.java", "A", "A1"))),
                response(plan(true, false, false),
                        call("p2", "apply_patch", patch("App.java", "B", "B2"))),
                response(plan(true, true, false), call("read", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(plan(true, true, true), call("t1", "run_maven_test", "{}")),
                answer("Complete")
        ));

        AgentRunResult result = planningAgent(client, passingRunner(), 6)
                .runWithTrajectory("Complete two changes and validate");

        assertEquals("Complete", result.finalAnswer());
        assertEquals(0, count(result, AgentActionType.PLAN_COMPLETION_FEEDBACK));
        assertEquals(0, result.trajectory().plan().remainingRequirementCount());
    }

    @Test
    void passingTestDoesNotCompletePendingRequirement() throws Exception {
        write("App.java", "old\n");
        CapturingClient client = client(List.of(
                response(plan(false, false, false), call("p1", "apply_patch", patch(
                        "App.java", "old", "partial"
                ))),
                response(plan(true, false, false), call("read", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(plan(true, false, false), call("t1", "run_maven_test", "{}")),
                answer("Visible test passed"),
                answer(plan(true, true, true) + "Complete")
        ));

        AgentRunResult result = planningAgent(client, passingRunner(), 6)
                .runWithTrajectory("Satisfy both behaviors");

        assertEquals(1, count(result, AgentActionType.PLAN_COMPLETION_FEEDBACK));
        assertEquals(0, result.trajectory().plan().remainingRequirementCount());
    }

    @Test
    void failedTestCanAddNewRequirementAndCompleteIt() throws Exception {
        write("App.java", "old\n");
        QueueRunner runner = new QueueRunner(List.of(
                new ProcessExecutionResult(1, false,
                        "[ERROR] AppTest.nullCase -- expected: <true> but was: <false>", false, 1),
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1)
        ));
        CapturingClient client = client(List.of(
                response(twoRequirementPlan(false, false), call("p1", "apply_patch", patch(
                        "App.java", "old", "first"
                ))),
                response(twoRequirementPlan(true, false), call("read-1", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(twoRequirementPlan(true, false), call("t1", "run_maven_test", "{}")),
                response(planWithR3(), call("read-after-failure", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(planWithR3(), call("p2", "apply_patch", patch(
                        "App.java", "first", "null-safe"
                ))),
                response(completedPlanWithR3(), call("read-2", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(completedPlanWithR3(), call("t2", "run_maven_test", "{}")),
                answer("Complete")
        ));

        AgentRunResult result = planningAgent(client, runner, 9)
                .runWithTrajectory("Fix behavior and respond to discovered failures");

        assertEquals(3, result.trajectory().plan().requirements().size());
        assertEquals(3, result.trajectory().plan().completedRequirementCount());
        assertTrue(result.trajectory().plan().requirements().stream()
                .anyMatch(requirement -> requirement.id().equals("R3")));
    }

    @Test
    void readOnlyTaskDoesNotRequirePlan() {
        CapturingClient client = client(List.of(answer("Explanation")));
        Agent agent = new Agent(
                client, new ToolRegistry(), "system", 2,
                TaskMode.READ_ONLY, true, true
        );

        AgentRunResult result = agent.runWithTrajectory("Explain this project");

        assertEquals("Explanation", result.finalAnswer());
        assertNull(result.trajectory().plan());
        assertEquals(0, count(result, AgentActionType.PLAN_COMPLETION_FEEDBACK));
        assertFalse(client.sawCompactProgress());
    }

    @Test
    void completionWarningIsBoundedToOne() throws Exception {
        write("App.java", "old\n");
        CapturingClient client = client(List.of(
                response(plan(false, false, false), call("p1", "apply_patch", patch(
                        "App.java", "old", "partial"
                ))),
                response(plan(true, false, false), call("read", "read_file",
                        "{\"path\":\"App.java\"}")),
                response(plan(true, false, false), call("t1", "run_maven_test", "{}")),
                answer("Final one"),
                answer("Final two")
        ));

        AgentRunResult result = planningAgent(client, passingRunner(), 5)
                .runWithTrajectory("Two requirements");

        assertEquals("Final two", result.finalAnswer());
        assertEquals(1, count(result, AgentActionType.PLAN_COMPLETION_FEEDBACK));
        assertEquals(2, result.trajectory().plan().remainingRequirementCount());
    }

    private Agent planningAgent(LLMClient client, ProcessRunner runner, int maxIterations) {
        return new Agent(
                client,
                ToolRegistry.withCodingTools(workspace, runner),
                "planning system",
                maxIterations,
                TaskMode.CODE_MODIFICATION,
                true,
                true
        );
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(workspace.resolve(name), content);
    }

    private static long count(AgentRunResult result, AgentActionType type) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == type)
                .count();
    }

    private static String plan(boolean r1Complete, boolean r2Complete, boolean validationComplete) {
        return "<plan_update>{\"goal\":\"Complete all task behaviors\","
                + "\"requirements\":["
                + requirement("R1", "Handle negative values", r1Complete)
                + "," + requirement("R2", "Reject empty input", r2Complete)
                + "," + requirement("R3", "Run validation", validationComplete)
                + "],\"currentFocus\":\"R2\",\"notes\":\"\"}</plan_update>";
    }

    private static String planWithR3() {
        return "<plan_update>{\"goal\":\"Complete discovered behaviors\","
                + "\"requirements\":["
                + requirement("R1", "Apply initial repair", true) + ","
                + requirement("R2", "Validate behavior", false) + ","
                + requirement("R3", "Handle null case discovered by test", false)
                + "],\"currentFocus\":\"R3\",\"notes\":\"test exposed null case\"}</plan_update>";
    }

    private static String twoRequirementPlan(boolean r1Complete, boolean r2Complete) {
        return "<plan_update>{\"goal\":\"Complete discovered behaviors\","
                + "\"requirements\":["
                + requirement("R1", "Apply initial repair", r1Complete) + ","
                + requirement("R2", "Validate behavior", r2Complete)
                + "],\"currentFocus\":\"R2\",\"notes\":\"\"}</plan_update>";
    }

    private static String completedPlanWithR3() {
        return "<plan_update>{\"goal\":\"Complete discovered behaviors\","
                + "\"requirements\":["
                + requirement("R1", "Apply initial repair", true) + ","
                + requirement("R2", "Validate behavior", true) + ","
                + requirement("R3", "Handle null case discovered by test", true)
                + "],\"currentFocus\":\"validation\",\"notes\":\"\"}</plan_update>";
    }

    private static String requirement(String id, String description, boolean complete) {
        return "{\"id\":\"" + id + "\",\"description\":\"" + description
                + "\",\"status\":\"" + (complete ? "COMPLETED" : "PENDING")
                + "\",\"evidence\":\"" + (complete ? "observed change" : "") + "\"}";
    }

    private static String patch(String path, String oldText, String newText) {
        return "{\"path\":\"" + path + "\",\"oldText\":\""
                + escape(oldText) + "\",\"newText\":\"" + escape(newText) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    private static LLMResponse response(String content, ToolCall... calls) {
        return new LLMResponse(content, List.of(calls));
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static CapturingClient client(List<LLMResponse> responses) {
        return new CapturingClient(responses);
    }

    private static ProcessRunner passingRunner() {
        return (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
    }

    private static final class CapturingClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> calls = new ArrayList<>();

        private CapturingClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            calls.add(List.copyOf(messages));
            return responses.removeFirst();
        }

        private boolean sawCompactProgress() {
            return calls.stream().flatMap(List::stream)
                    .anyMatch(message -> message.content().startsWith("Current Progress"));
        }
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
                java.time.Duration timeout,
                int maxOutputBytes
        ) {
            return results.removeFirst();
        }
    }
}
