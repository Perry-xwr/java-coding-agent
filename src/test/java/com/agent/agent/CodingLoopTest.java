package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.agent.trajectory.TrajectoryJsonWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingLoopTest {
    @TempDir
    Path workspace;

    @Test
    void completesReadPatchTestFinalLoop() throws IOException {
        Path source = Files.writeString(
                workspace.resolve("Calculator.java"),
                "int add(int a, int b) { return a - b; }\n"
        );
        QueueProcessRunner runner = new QueueProcessRunner(List.of(
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 25)
        ));
        FakeLLMClient llm = new FakeLLMClient(List.of(
                toolResponse("read", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse(
                        "patch",
                        "apply_patch",
                        "{\"path\":\"Calculator.java\",\"oldText\":\"a - b\",\"newText\":\"a + b\"}"
                ),
                toolResponse("read-after-patch", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse("test", "run_maven_test", "{\"testClass\":\"CalculatorTest\"}"),
                new LLMResponse("Fixed and tested.", List.of())
        ));
        Agent agent = new Agent(llm, ToolRegistry.withCodingTools(workspace, runner));

        AgentRunResult result = agent.runWithTrajectory("Fix add() and make the test pass.");

        assertEquals("Fixed and tested.", result.finalAnswer());
        assertTrue(Files.readString(source).contains("a + b"));
        assertEquals(
                List.of("read_file", "apply_patch", "read_file", "run_maven_test"),
                toolNames(result.trajectory())
        );
        List<AgentStep> toolSteps = result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();
        assertTrue(toolSteps.get(1).toolResult().success());
        assertTrue(toolSteps.get(3).toolResult().success());
        assertEquals(TerminationReason.FINAL_ANSWER, result.trajectory().terminationReason());
    }

    @Test
    void recoversFromFailedTestByPatchingAndRetesting() throws IOException {
        Path source = Files.writeString(
                workspace.resolve("Calculator.java"),
                "int add(int a, int b) { return a - b; }\n"
        );
        QueueProcessRunner runner = new QueueProcessRunner(List.of(
                new ProcessExecutionResult(1, false, "expected 5 but was 6", false, 30),
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 20)
        ));
        FakeLLMClient llm = new FakeLLMClient(List.of(
                toolResponse("read", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse(
                        "patch-wrong",
                        "apply_patch",
                        "{\"path\":\"Calculator.java\",\"oldText\":\"a - b\",\"newText\":\"a * b\"}"
                ),
                toolResponse("read-wrong", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse("test-fail", "run_maven_test", "{\"testClass\":\"CalculatorTest\"}"),
                toolResponse("read-after-failure", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse(
                        "patch-correct",
                        "apply_patch",
                        "{\"path\":\"Calculator.java\",\"oldText\":\"a * b\",\"newText\":\"a + b\"}"
                ),
                toolResponse("read-correct", "read_file", "{\"path\":\"Calculator.java\"}"),
                toolResponse("test-pass", "run_maven_test", "{\"testClass\":\"CalculatorTest\"}"),
                new LLMResponse("Corrected after the failed test.", List.of())
        ));
        Agent agent = new Agent(llm, ToolRegistry.withCodingTools(workspace, runner));

        AgentRunResult result = agent.runWithTrajectory("Fix add() and make the test pass.");
        List<AgentStep> toolSteps = result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();

        assertEquals(
                List.of(
                        "read_file",
                        "apply_patch",
                        "read_file",
                        "run_maven_test",
                        "read_file",
                        "apply_patch",
                        "read_file",
                        "run_maven_test"
                ),
                toolNames(result.trajectory())
        );
        assertFalse(toolSteps.get(3).toolResult().success());
        assertEquals(ToolErrorCode.TEST_FAILED, toolSteps.get(3).toolResult().errorCode());
        assertEquals("expected 5 but was 6", toolSteps.get(3).toolResult().output());
        assertTrue(toolSteps.get(7).toolResult().success());
        assertTrue(Files.readString(source).contains("a + b"));
        assertTrue(agent.history().stream().anyMatch(
                message -> message.role().equals("tool")
                        && message.content().contains("TEST_FAILED")
                        && message.content().contains("expected 5 but was 6")
        ));

        Path trajectoryFile = new TrajectoryJsonWriter(
                workspace.resolve("trajectories")
        ).write(result.trajectory());
        JsonNode json = new ObjectMapper().readTree(trajectoryFile.toFile());
        assertEquals("TEST_FAILED", findJsonToolStep(json, "run_maven_test", 0)
                .path("toolResult").path("errorCode").asText());
        assertEquals("apply_patch", findJsonToolStep(json, "apply_patch", 1).path("toolName").asText());
        assertEquals("run_maven_test", findJsonToolStep(json, "run_maven_test", 1)
                .path("toolName").asText());
    }

    private static LLMResponse toolResponse(String id, String name, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, name, arguments)));
    }

    private static List<String> toolNames(AgentTrajectory trajectory) {
        return trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .map(AgentStep::toolName)
                .toList();
    }

    private static JsonNode findJsonToolStep(JsonNode trajectory, String toolName, int occurrence) {
        int found = 0;
        for (JsonNode step : trajectory.path("steps")) {
            if (toolName.equals(step.path("toolName").asText())) {
                if (found == occurrence) {
                    return step;
                }
                found++;
            }
        }
        throw new AssertionError("Missing tool step: " + toolName + " occurrence " + occurrence);
    }

    private static final class FakeLLMClient implements LLMClient {
        private final Deque<LLMResponse> responses;

        private FakeLLMClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return responses.removeFirst();
        }
    }

    private static final class QueueProcessRunner implements ProcessRunner {
        private final Deque<ProcessExecutionResult> results;

        private QueueProcessRunner(List<ProcessExecutionResult> results) {
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
