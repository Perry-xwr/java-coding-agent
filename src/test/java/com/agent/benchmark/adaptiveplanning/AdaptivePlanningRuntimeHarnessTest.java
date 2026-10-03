package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.AgentActionType;
import com.agent.agent.PlanningMode;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePlanningRuntimeHarnessTest {
    private static final Path FIXTURES = Path.of("benchmark/adaptive-planning-v1").toAbsolutePath().normalize();

    @TempDir Path temp;

    @Test
    void simpleTaskComparesAllModesAndAdaptiveReactiveHasNoPlanningRequestOverhead() throws Exception {
        AdaptivePlanningTask task = load("simple_01_note_literal");
        AdaptivePlanningRuntimeHarness harness = new AdaptivePlanningRuntimeHarness();

        var reactive = run(harness, task, PlanningMode.REACTIVE, new ScriptedClient(editScript()));
        var planned = run(harness, task, PlanningMode.PLAN_EXECUTE, new ScriptedClient(withPlan(editScript())));
        var adaptiveClient = new ScriptedClient(editScript());
        var adaptive = run(harness, task, PlanningMode.ADAPTIVE, adaptiveClient);

        assertTrue(reactive.evaluation().taskSuccess());
        assertTrue(planned.evaluation().taskSuccess());
        assertTrue(adaptive.evaluation().taskSuccess());
        assertEquals(PlanningMode.REACTIVE, adaptive.metrics().effectiveMode());
        assertEquals(reactive.metrics().providerRequests(), adaptive.metrics().providerRequests());
        assertEquals(reactive.metrics().providerRequests() + 1, planned.metrics().providerRequests());
        assertEquals(0, adaptive.metrics().planCreated());
        assertEquals(PlanningMode.ADAPTIVE, adaptive.configuredMode());
    }

    @Test
    void complexAdaptiveConditionStartsWithPlanningRequestWithoutTools() throws Exception {
        AdaptivePlanningTask task = customComplexTask("Find notes.txt, update it, then verify the result.");
        ScriptedClient client = new ScriptedClient(List.of(
                answer(plan("inspect and update note", "Read notes.txt", "Replace draft with final")),
                tool("read", "read_file", "{\"path\":\"notes.txt\"}"),
                tool("edit", "apply_patch", patch("Status: draft", "Status: final")),
                answer("Updated the note.")));

        var result = run(new AdaptivePlanningRuntimeHarness(), task, PlanningMode.ADAPTIVE, client);

        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures().toString());
        assertEquals(PlanningMode.PLAN_EXECUTE, result.metrics().effectiveMode());
        assertTrue(client.requests.get(0).stream().anyMatch(message -> message.content().contains("[PLANNING_PHASE]")));
        assertTrue(client.toolRequests.get(0).isEmpty(), "planning request must not expose tools");
        assertEquals(1, result.metrics().planCreated());
        assertEquals(4, result.metrics().providerRequests());
        assertEquals(0, client.requests.stream().filter(AdaptivePlanningRuntimeHarnessTest::isRouterRequest).count());
    }

    @Test
    void routeMismatchCanStillHaveSuccessfulWorkspaceOutcome() throws Exception {
        AdaptivePlanningTask task = new AdaptivePlanningTask("mismatch", AdaptivePlanningTaskClass.SIMPLE,
                "simple_01_note_literal", "Find notes.txt, replace draft with final, then verify the result.",
                PlanningMode.REACTIVE, List.of("notes.txt"), Map.of("notes.txt", List.of("Status: final")),
                List.of(), List.of(), List.of(), true, false, 12, 20);
        ScriptedClient client = new ScriptedClient(List.of(
                answer(plan("edit the note", "Read notes.txt", "Replace the status")),
                tool("read", "read_file", "{\"path\":\"notes.txt\"}"),
                tool("edit", "apply_patch", patch("Status: draft", "Status: final")),
                answer("Updated the note.")));

        var result = run(new AdaptivePlanningRuntimeHarness(), task, PlanningMode.ADAPTIVE, client);

        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures().toString());
        assertFalse(result.metrics().routingMatch());
        assertEquals(PlanningMode.PLAN_EXECUTE, result.metrics().effectiveMode());
    }

    @Test
    void malformedAdaptivePlanFallsBackAndRequestAccountingIncludesPlanAttempt() throws Exception {
        AdaptivePlanningTask task = customComplexTask("Find notes.txt, update it, then verify the result.");
        ScriptedClient client = new ScriptedClient(List.of(
                answer("not a structured plan"),
                tool("read", "read_file", "{\"path\":\"notes.txt\"}"),
                tool("edit", "apply_patch", patch("Status: draft", "Status: final")),
                answer("Updated after falling back to reactive execution.")));

        var result = run(new AdaptivePlanningRuntimeHarness(), task, PlanningMode.ADAPTIVE, client);

        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures().toString());
        assertEquals(1, result.metrics().planFallback());
        assertEquals(4, result.metrics().providerRequests());
        assertTrue(client.toolRequests.get(0).isEmpty());
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.PLAN_FALLBACK));
    }

    private AdaptivePlanningRuntimeHarness.RunResult run(AdaptivePlanningRuntimeHarness harness,
            AdaptivePlanningTask task, PlanningMode mode, ScriptedClient client) throws Exception {
        return harness.run(task, mode, client, FIXTURES, temp.resolve("runs"),
                task.id() + "-" + mode.name(), temp.resolve("m2"));
    }

    private AdaptivePlanningTask load(String id) throws Exception {
        return new AdaptivePlanningTaskLoader().load(FIXTURES.resolve("manifest.json")).stream()
                .filter(task -> task.id().equals(id)).findFirst().orElseThrow();
    }

    private static AdaptivePlanningTask customComplexTask(String instruction) {
        return new AdaptivePlanningTask("scripted-complex", AdaptivePlanningTaskClass.COMPLEX,
                "simple_01_note_literal", instruction, PlanningMode.PLAN_EXECUTE,
                List.of("notes.txt"), Map.of("notes.txt", List.of("Status: final")),
                List.of(), List.of(), List.of(), true, false, 12, 20);
    }

    private static List<LLMResponse> editScript() {
        return List.of(tool("read", "read_file", "{\"path\":\"notes.txt\"}"),
                tool("edit", "apply_patch", patch("Status: draft", "Status: final")), answer("Updated the note."));
    }

    private static List<LLMResponse> withPlan(List<LLMResponse> execution) {
        List<LLMResponse> script = new ArrayList<>();
        script.add(answer(plan("update note", "Read notes.txt", "Replace the status")));
        script.addAll(execution);
        return script;
    }

    private static String patch(String oldText, String newText) {
        return "{\"path\":\"notes.txt\",\"oldText\":\"" + oldText + "\",\"newText\":\"" + newText + "\"}";
    }

    private static String plan(String goal, String first, String second) {
        return "{\"goal\":\"" + goal + "\",\"steps\":["
                + "{\"id\":\"S1\",\"description\":\"" + first + "\"},"
                + "{\"id\":\"S2\",\"description\":\"" + second + "\"}]}";
    }

    private static boolean isRouterRequest(List<Message> messages) {
        return messages.stream().anyMatch(message -> message.content().contains("[ADAPTIVE_ROUTER]"));
    }

    private static LLMResponse answer(String content) { return new LLMResponse(content, List.of()); }

    private static LLMResponse tool(String id, String name, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, name, arguments)));
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolRequests = new ArrayList<>();

        private ScriptedClient(List<LLMResponse> responses) { this.responses = new ArrayDeque<>(responses); }

        @Override public LLMResponse chat(List<Message> messages) throws IOException {
            return chat(messages, List.of());
        }

        @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            requests.add(List.copyOf(messages));
            toolRequests.add(List.copyOf(tools));
            if (responses.isEmpty()) throw new IOException("scripted provider responses exhausted");
            return responses.removeFirst();
        }
    }
}
