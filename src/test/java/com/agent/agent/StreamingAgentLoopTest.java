package com.agent.agent;

import com.agent.llm.LLMResponse;
import com.agent.llm.LlmStreamListener;
import com.agent.llm.Message;
import com.agent.llm.StreamingLlmClient;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingAgentLoopTest {
    @TempDir
    Path workspace;

    @Test
    void streamsTextExecutesCompletedToolOnceAndStreamsFinalResponse() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "streaming project");
        FakeStreamingClient client = new FakeStreamingClient(List.of(
                new Turn(
                        List.of("我先", "读取文件。"),
                        new LLMResponse("我先读取文件。", List.of(new ToolCall(
                                "call-1", "read_file", "{\"path\":\"README.md\"}"
                        )))
                ),
                new Turn(
                        List.of("项目", "支持流式输出。"),
                        new LLMResponse("项目支持流式输出。", List.of())
                )
        ));
        RecordingEvents events = new RecordingEvents();
        Agent agent = new Agent(
                client,
                ToolRegistry.withFileTools(workspace),
                "read files",
                5,
                TaskMode.READ_ONLY,
                false,
                false,
                events,
                true
        );

        AgentRunResult result = agent.runWithTrajectory("read README");

        assertEquals("项目支持流式输出。", result.finalAnswer());
        assertEquals(List.of("我先", "读取文件。", "项目", "支持流式输出。"), events.deltas);
        assertEquals(1, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .count());
        assertEquals(2, client.requests.size());
        List<Message> secondRequest = client.requests.get(1);
        assertTrue(secondRequest.stream().anyMatch(message ->
                message.role().equals("assistant") && message.content().equals("我先读取文件。")));
        assertTrue(secondRequest.stream().anyMatch(message ->
                message.role().equals("tool") && message.content().equals("streaming project")));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                "项目支持流式输出。".equals(step.finalAnswer())));
    }

    private record Turn(List<String> deltas, LLMResponse response) {
    }

    private static final class FakeStreamingClient implements StreamingLlmClient {
        private final List<Turn> turns;
        private final List<List<Message>> requests = new ArrayList<>();
        private int index;

        private FakeStreamingClient(List<Turn> turns) {
            this.turns = turns;
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            throw new AssertionError("blocking API must not be used by streaming CLI agent");
        }

        @Override
        public LLMResponse stream(
                List<Message> messages,
                List<ToolDefinition> tools,
                LlmStreamListener listener
        ) throws IOException {
            requests.add(List.copyOf(messages));
            Turn turn = turns.get(index++);
            turn.deltas().forEach(listener::onTextDelta);
            return turn.response();
        }
    }

    private static final class RecordingEvents implements AgentEventListener {
        private final List<String> deltas = new ArrayList<>();

        @Override
        public void assistantTextDelta(String delta) {
            deltas.add(delta);
        }
    }
}
