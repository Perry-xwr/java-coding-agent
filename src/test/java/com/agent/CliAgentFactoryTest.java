package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAgentFactoryTest {
    @TempDir
    Path workspace;

    @Test
    void chatExposesNoToolsAndAnswersWithoutModificationGuard() throws Exception {
        RecordingClient client = new RecordingClient();
        Agent chat = CliAgentFactory.createChat(client, AgentEventListener.NO_OP);

        assertEquals("Java answer", chat.run("请介绍一下 Java"));
        assertEquals(List.of(), client.toolNames().get(0));
        assertEquals(1, client.requests().size());
    }

    @Test
    void readProfileExposesOnlyReadTools() throws Exception {
        RecordingClient client = new RecordingClient();
        Agent read = CliAgentFactory.createReadOnly(client, workspace, AgentEventListener.NO_OP);

        read.run("inspect repository");

        assertEquals(List.of("list_files", "find_files", "read_file", "search_code"), client.toolNames().get(0));
        assertFalse(client.toolNames().get(0).contains("apply_patch"));
        assertFalse(client.toolNames().get(0).contains("run_maven_test"));
    }

    @Test
    void codingProfileKeepsFrozenV1ToolSetAndGuard() throws Exception {
        RecordingClient client = new RecordingClient();
        Agent code = CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP);

        code.run("modify a file");

        assertEquals(
                List.of(
                        "list_files", "find_files", "read_file", "search_code", "apply_patch",
                        "insert_before", "insert_after", "create_file", "run_maven_test"
                ),
                client.toolNames().get(0)
        );
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().get(1).stream()
                .anyMatch(message -> message.content().contains("PREMATURE_FINAL_GUARD")));
    }

    @Test
    void profileHistoriesRemainIsolatedWhenSwitching() throws Exception {
        RecordingClient chatClient = new RecordingClient();
        RecordingClient codeClient = new RecordingClient();
        Agent chat = CliAgentFactory.createChat(chatClient, AgentEventListener.NO_OP);
        Agent code = CliAgentFactory.createCoding(codeClient, workspace, AgentEventListener.NO_OP);

        chat.run("first chat question");
        code.run("modify something");
        chat.run("follow-up chat question");

        List<Message> lastChatRequest = chatClient.requests().get(1);
        assertTrue(lastChatRequest.stream().anyMatch(message -> message.content().equals("first chat question")));
        assertTrue(lastChatRequest.stream().anyMatch(message -> message.content().equals("follow-up chat question")));
        assertFalse(lastChatRequest.stream()
                .anyMatch(message -> message.content().contains("PREMATURE_FINAL_GUARD")));
    }

    private static final class RecordingClient implements LLMClient {
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<String>> toolNames = new ArrayList<>();

        @Override
        public LLMResponse chat(List<Message> messages) {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolNames.add(tools.stream().map(ToolDefinition::name).toList());
            return new LLMResponse("Java answer", List.of());
        }

        private List<List<Message>> requests() {
            return requests;
        }

        private List<List<String>> toolNames() {
            return toolNames;
        }
    }
}
