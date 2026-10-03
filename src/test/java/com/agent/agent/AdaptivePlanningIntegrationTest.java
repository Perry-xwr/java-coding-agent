package com.agent.agent;

import com.agent.environment.AgentEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePlanningIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void adaptiveSimpleTaskMakesNoPlanningRequestAndMatchesReactiveRequestCount() {
        String task = "Replace TODO with DONE in notes.txt";
        RecordingClient reactiveClient = editClient();
        AgentRunResult reactive = agent(reactiveClient, PlanningMode.REACTIVE).runWithTrajectory(task);

        RecordingClient adaptiveClient = editClient();
        AgentRunResult adaptive = agent(adaptiveClient, PlanningMode.ADAPTIVE).runWithTrajectory(task);

        assertTrue(reactive.trajectory().completed());
        assertTrue(adaptive.trajectory().completed());
        assertEquals(reactiveClient.requests.size(), adaptiveClient.requests.size());
        assertFalse(adaptiveClient.requests.get(0).stream()
                .anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));
        AgentStep routed = adaptive.trajectory().steps().get(0);
        assertEquals(AgentActionType.PLANNING_ROUTED, routed.actionType());
        assertEquals("ADAPTIVE", routed.arguments().get("configuredMode"));
        assertEquals("REACTIVE", routed.arguments().get("effectiveMode"));
    }

    @Test
    void adaptiveComplexTaskStartsWithPlanRequestWithoutTools() {
        RecordingClient client = new RecordingClient(List.of(
                answer("{\"goal\":\"Update and verify notes\",\"steps\":["
                        + "{\"id\":\"S1\",\"description\":\"Edit notes.txt\"},"
                        + "{\"id\":\"S2\",\"description\":\"Reread and verify\"}]}") ,
                editCall(),
                answer("Updated and verified.")));

        AgentRunResult result = agent(client, PlanningMode.ADAPTIVE)
                .runWithTrajectory("Find notes.txt, edit it, then run tests and fix any failures");

        assertTrue(result.trajectory().completed());
        assertEquals(3, client.requests.size());
        assertTrue(client.requests.get(0).stream()
                .anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));
        assertTrue(client.toolRequests.get(0).isEmpty());
        assertEquals(AgentActionType.PLANNING_ROUTED, result.trajectory().steps().get(0).actionType());
        assertEquals("PLAN_EXECUTE", result.trajectory().steps().get(0).arguments().get("effectiveMode"));
    }

    @Test
    void adaptiveDecisionUsesCurrentRawTurnRatherThanComposedHistoryContext() {
        RecordingClient client = editClient();
        String composedInput = "Trusted context: read Config.java, edit Settings.java, then run tests.\n\n"
                + "User request:\nReplace TODO with DONE in notes.txt";
        AgentRunResult result = agent(client, PlanningMode.ADAPTIVE)
                .runWithTrajectory(composedInput, "Replace TODO with DONE in notes.txt");

        assertTrue(result.trajectory().completed());
        assertEquals("REACTIVE", result.trajectory().steps().get(0).arguments().get("effectiveMode"));
        assertEquals(2, client.requests.size());
    }

    @Test
    void explicitModesBypassAdaptiveRouting() {
        RecordingClient reactive = editClient();
        AgentRunResult reactiveResult = agent(reactive, PlanningMode.REACTIVE)
                .runWithTrajectory("Find notes.txt, edit it, then run tests and fix failures");
        assertTrue(reactiveResult.trajectory().steps().stream()
                .noneMatch(step -> step.actionType() == AgentActionType.PLANNING_ROUTED));
        assertFalse(reactive.requests.get(0).stream()
                .anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));

        RecordingClient planned = new RecordingClient(List.of(
                answer("{\"goal\":\"Edit notes\",\"steps\":[{\"id\":\"S1\","
                        + "\"description\":\"Edit and reread notes.txt\"}]}") ,
                editCall(), answer("done")));
        AgentRunResult plannedResult = agent(planned, PlanningMode.PLAN_EXECUTE)
                .runWithTrajectory("Replace TODO with DONE in notes.txt");
        assertTrue(plannedResult.trajectory().steps().stream()
                .noneMatch(step -> step.actionType() == AgentActionType.PLANNING_ROUTED));
        assertTrue(planned.requests.get(0).stream()
                .anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));
        assertTrue(planned.toolRequests.get(0).isEmpty());
    }

    private Agent agent(RecordingClient client, PlanningMode mode) {
        AgentEnvironment environment = new AgentEnvironment() {
            @Override
            public ToolResult execute(ToolCall call) {
                if (call.name().equals("read_file")) {
                    return ToolResult.success("latest notes content");
                }
                return ToolResult.success("changed", Map.of("changed", true));
            }

            @Override
            public List<ToolDefinition> toolDefinitions() {
                return List.of(new ToolDefinition("apply_patch", "apply edit", Map.of()),
                        new ToolDefinition("read_file", "read file", Map.of()));
            }
        };
        return new Agent(client, environment, "test CODE prompt", 8,
                TaskMode.CODE_MODIFICATION, true, AgentEventListener.NO_OP, false, mode);
    }

    private static RecordingClient editClient() {
        return new RecordingClient(List.of(editCall(), answer("Updated and verified.")));
    }

    private static LLMResponse editCall() {
        return new LLMResponse("", List.of(new ToolCall("edit-1", "apply_patch",
                "{\"path\":\"notes.txt\",\"oldText\":\"TODO\",\"newText\":\"DONE\"}")));
    }

    private static LLMResponse answer(String text) {
        return new LLMResponse(text, List.of());
    }

    private static final class RecordingClient implements LLMClient {
        private final List<LLMResponse> script;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolRequests = new ArrayList<>();

        private RecordingClient(List<LLMResponse> script) {
            this.script = script;
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolRequests.add(List.copyOf(tools));
            if (requests.size() > script.size()) {
                throw new AssertionError("unexpected provider request " + requests.size());
            }
            return script.get(requests.size() - 1);
        }
    }
}
