package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.ProcessExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateFileAgentFlowTest {
    @TempDir
    Path workspace;

    @Test
    void fakeLlmCreatesThenReadsNewFileBeforeFinalAnswer() throws Exception {
        QueueClient client = new QueueClient(List.of(
                response("", call("create", "create_file", "{\"path\":\"hello.py\",\"content\":\"print('hello')\\n\"}")),
                response("", call("read", "read_file", "{\"path\":\"hello.py\"}")),
                response("Created and confirmed."),
                response("Created and confirmed.")
        ));
        Agent agent = new Agent(
                client,
                ToolRegistry.withCliCodingTools(
                        workspace,
                        (command, directory, timeout, maxOutput) ->
                                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1),
                        workspace.resolve(".m2/repository")
                ),
                "coding",
                6,
                TaskMode.CODE_MODIFICATION,
                true,
                false
        );

        assertEquals("Created and confirmed.", agent.run("create hello.py and read it"));
        assertEquals("print('hello')\n", Files.readString(workspace.resolve("hello.py")));
        assertTrue(client.toolNames.get(0).contains("create_file"));
    }

    private static LLMResponse response(String content, ToolCall... calls) {
        return new LLMResponse(content, List.of(calls));
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    private static final class QueueClient implements LLMClient {
        private final ArrayDeque<LLMResponse> responses;
        private final List<List<String>> toolNames = new java.util.ArrayList<>();

        private QueueClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            toolNames.add(tools.stream().map(ToolDefinition::name).toList());
            return responses.removeFirst();
        }
    }
}
