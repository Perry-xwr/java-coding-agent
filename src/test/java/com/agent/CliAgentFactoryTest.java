package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.PlanningMode;
import com.agent.environment.AgentEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    void codingProfileFiltersMavenForStandaloneWorkspaceAndKeepsGuard() throws Exception {
        RecordingClient client = new RecordingClient();
        Agent code = CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP);

        var result = code.runWithTrajectory("modify a file");

        assertEquals(
                List.of(
                        "list_files", "find_files", "read_file", "search_code", "apply_patch",
                        "insert_before", "insert_after", "create_file"
                ),
                client.toolNames().get(0)
        );
        assertFalse(result.trajectory().completed());
        assertEquals(Agent.MAX_ITERATIONS, client.requests().size());
        assertFalse(client.requests().get(0).stream()
                .anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));
        assertTrue(client.requests().get(1).stream()
                .anyMatch(message -> message.content().startsWith("WORKSPACE_CHANGE_REQUIRED:")));
    }

    @Test
    void codingProfileAdvertisesMavenWhenWorkspaceHasRootPom() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
        RecordingClient client = new RecordingClient();
        Agent code = CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP);

        var result = code.runWithTrajectory("modify a file");

        assertFalse(result.trajectory().completed());
        assertTrue(client.toolNames().get(0).contains("run_maven_test"));
        assertTrue(client.toolNames().get(0).contains("apply_patch"));
    }

    @Test
    void injectedEnvironmentAndProviderAreUsedAndPlanningModeIsForwarded() {
        ScriptedClient client = new ScriptedClient(toolCall("probe", Map.of()),
                new LLMResponse("Done.", List.of()), new LLMResponse("Done.", List.of()));
        AtomicInteger executions = new AtomicInteger();
        AgentEnvironment environment = new AgentEnvironment() {
            @Override
            public ToolResult execute(ToolCall call) {
                assertEquals("probe", call.name());
                executions.incrementAndGet();
                return ToolResult.success("environment-used");
            }

            @Override
            public List<ToolDefinition> toolDefinitions() {
                return List.of(new ToolDefinition("probe", "test probe", Map.of("type", "object")));
            }
        };
        Agent injected = CliAgentFactory.createCoding(client, environment, AgentEventListener.NO_OP,
                PlanningMode.REACTIVE);
        injected.runWithTrajectory("Run a deterministic injected environment probe");
        assertEquals(1, executions.get());
        assertTrue(client.toolNamesSeen.stream().anyMatch(names -> names.contains("probe")));
        assertEquals(3, client.requests);

        Agent planned = CliAgentFactory.createCoding(new RecordingClient(), environment, AgentEventListener.NO_OP,
                PlanningMode.PLAN_EXECUTE);
        assertEquals(PlanningMode.PLAN_EXECUTE, planned.planningMode());
    }

    @Test
    void defaultCodingPathUsesBuiltInVerifierRatherThanBenchmarkNoOp() throws Exception {
        Path source = workspace.resolve("FactoryDefault.py");
        java.nio.file.Files.writeString(source, "def value():\n    return 1\n");
        ScriptedClient client = new ScriptedClient(
                toolCall("read_file", Map.of("path", "FactoryDefault.py")),
                toolCall("apply_patch", Map.of("path", "FactoryDefault.py", "oldText", "return 1", "newText", "return 2")),
                new LLMResponse("Updated.", List.of()), new LLMResponse("Updated.", List.of()));
        Agent agent = CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP);

        var result = agent.runWithTrajectory("Fix the value function");

        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == com.agent.agent.AgentActionType.POST_EDIT_VERIFICATION
                        && "python-py-compile".equals(step.arguments().get("verifier"))));
    }

    @Test
    void injectedProfilesRemainReactiveUnlessPlanningIsExplicitlySelected() {
        RecordingClient reactiveClient = new RecordingClient();
        CliSessions reactive = CliAgentFactory.createProfiles(
                reactiveClient, workspace, AgentEventListener.NO_OP);
        assertEquals(PlanningMode.REACTIVE, reactive.code().planningMode());

        RecordingClient planningClient = new RecordingClient();
        CliSessions planExecute = CliAgentFactory.createProfiles(
                planningClient, workspace, AgentEventListener.NO_OP, PlanningMode.PLAN_EXECUTE);
        assertEquals(PlanningMode.PLAN_EXECUTE, planExecute.code().planningMode());
        assertEquals(PlanningMode.REACTIVE, planExecute.chat().planningMode());
        assertEquals(PlanningMode.REACTIVE, planExecute.read().planningMode());

        RecordingClient adaptiveClient = new RecordingClient();
        CliSessions adaptive = CliAgentFactory.createProfiles(
                adaptiveClient, workspace, AgentEventListener.NO_OP, PlanningMode.ADAPTIVE);
        assertEquals(PlanningMode.ADAPTIVE, adaptive.code().planningMode());
        assertEquals(PlanningMode.REACTIVE, adaptive.chat().planningMode());
        assertEquals(PlanningMode.REACTIVE, adaptive.read().planningMode());
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

    private static LLMResponse toolCall(String name, Map<String, Object> arguments) {
        try {
            return new LLMResponse("", List.of(new ToolCall("factory-" + name, name,
                    new ObjectMapper().writeValueAsString(arguments))));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<String>> toolNamesSeen = new ArrayList<>();
        private int requests;
        private ScriptedClient(LLMResponse... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public LLMResponse chat(List<Message> messages) {
            requests++;
            if (responses.isEmpty()) return new LLMResponse("Finished.", List.of());
            return responses.removeFirst();
        }
        @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            toolNamesSeen.add(tools.stream().map(ToolDefinition::name).toList());
            return chat(messages);
        }
    }
}
