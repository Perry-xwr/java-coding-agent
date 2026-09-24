package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTrajectoryTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsFinalAnswerOnlyRun() {
        Agent agent = new Agent(
                new FakeLLMClient(List.of(new LLMResponse("final", List.of()))),
                new ToolRegistry()
        );

        AgentRunResult result = agent.runWithTrajectory("question");
        AgentTrajectory trajectory = result.trajectory();

        assertNotNull(UUID.fromString(trajectory.runId()));
        assertEquals("question", trajectory.task());
        assertEquals("final", result.finalAnswer());
        assertEquals(TerminationReason.FINAL_ANSWER, trajectory.terminationReason());
        assertTrue(trajectory.completed());
        assertNull(trajectory.taskSuccess());
        assertEquals(1, trajectory.steps().size());
        assertEquals(AgentActionType.FINAL_ANSWER, trajectory.steps().get(0).actionType());
        assertTrue(trajectory.durationMs() >= 0);
    }

    @Test
    void recordsOneToolAndFinalAnswer() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "content");
        Agent agent = new Agent(
                new FakeLLMClient(List.of(
                        new LLMResponse("", List.of(
                                new ToolCall("read-1", "read_file", "{\"path\":\"README.md\"}")
                        )),
                        new LLMResponse("done", List.of())
                )),
                ToolRegistry.withFileTools(tempDir)
        );

        AgentTrajectory trajectory = agent.runWithTrajectory("read").trajectory();
        AgentStep toolStep = trajectory.steps().get(0);

        assertEquals(2, trajectory.steps().size());
        assertEquals(AgentActionType.TOOL_CALL, toolStep.actionType());
        assertEquals("read_file", toolStep.toolName());
        assertEquals("read-1", toolStep.toolCallId());
        assertEquals("README.md", toolStep.arguments().get("path"));
        assertTrue(toolStep.toolResult().success());
        assertEquals("content", toolStep.toolResult().output());
        assertTrue(toolStep.durationMs() >= 0);
        assertEquals(AgentActionType.FINAL_ANSWER, trajectory.steps().get(1).actionType());
    }

    @Test
    void recordsMultiStepToolsInOrder() throws IOException {
        Files.writeString(tempDir.resolve("Todo.java"), "// TODO\n");
        Agent agent = new Agent(
                new FakeLLMClient(List.of(
                        new LLMResponse("", List.of(
                                new ToolCall("search-1", "search_code", "{\"keyword\":\"TODO\"}")
                        )),
                        new LLMResponse("", List.of(
                                new ToolCall("read-1", "read_file", "{\"path\":\"Todo.java\"}")
                        )),
                        new LLMResponse("done", List.of())
                )),
                ToolRegistry.withFileTools(tempDir)
        );

        AgentTrajectory trajectory = agent.runWithTrajectory("search then read").trajectory();

        assertEquals(List.of("search_code", "read_file"), trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .map(AgentStep::toolName)
                .toList());
        assertEquals(AgentActionType.FINAL_ANSWER, trajectory.steps().get(2).actionType());
    }

    @Test
    void recordsToolErrorAndRecovery() {
        Agent agent = new Agent(
                new FakeLLMClient(List.of(
                        new LLMResponse("", List.of(
                                new ToolCall("missing-1", "read_file", "{\"path\":\"missing.txt\"}")
                        )),
                        new LLMResponse("not found", List.of())
                )),
                ToolRegistry.withFileTools(tempDir)
        );

        AgentTrajectory trajectory = agent.runWithTrajectory("read missing").trajectory();
        AgentStep failedStep = trajectory.steps().get(0);

        assertFalse(failedStep.toolResult().success());
        assertEquals(ToolErrorCode.FILE_NOT_FOUND, failedStep.toolResult().errorCode());
        assertEquals(TerminationReason.FINAL_ANSWER, trajectory.terminationReason());
        assertTrue(trajectory.completed());
    }

    @Test
    void recordsMaxStepsAsIncompleteTermination() {
        List<LLMResponse> responses = new ArrayList<>();
        for (int index = 0; index < Agent.MAX_ITERATIONS; index++) {
            responses.add(new LLMResponse("", List.of(
                    new ToolCall("unknown-" + index, "unknown", "{}")
            )));
        }
        Agent agent = new Agent(new FakeLLMClient(responses), new ToolRegistry());

        AgentTrajectory trajectory = agent.runWithTrajectory("loop").trajectory();

        assertEquals(TerminationReason.MAX_STEPS, trajectory.terminationReason());
        assertFalse(trajectory.completed());
        assertNull(trajectory.finalAnswer());
        assertEquals(
                AgentActionType.ERROR,
                trajectory.steps().get(Agent.MAX_ITERATIONS).actionType()
        );
        assertEquals(Agent.MAX_ITERATIONS, trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .count());
    }

    @Test
    void recordsLlmErrorTermination() {
        LLMClient failingClient = messages -> {
            throw new IOException("provider unavailable");
        };
        Agent agent = new Agent(failingClient, new ToolRegistry());

        AgentTrajectory trajectory = agent.runWithTrajectory("question").trajectory();

        assertEquals(TerminationReason.LLM_ERROR, trajectory.terminationReason());
        assertFalse(trajectory.completed());
        assertEquals(AgentActionType.ERROR, trajectory.steps().get(0).actionType());
        assertEquals("provider unavailable", trajectory.steps().get(0).errorMessage());
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
}
