package com.agent.benchmark.planning;

import com.agent.agent.AgentActionType;
import com.agent.agent.PlanningMode;
import com.agent.agent.TerminationReason;
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

class PlanningRuntimeHarnessTest {
    @TempDir Path temp;

    @Test
    void caseOneBothSucceedAndPlanningConsumesOneAdditionalRequest() throws Exception {
        PlanningBenchmarkTask task = createTask();
        var result = harness().runPair(task, pairedClients(
                        new ScriptedClient(List.of(tool("create_file", "{\"path\":\"result.txt\",\"content\":\"expected\"}"), answer("Done"))),
                        new ScriptedClient(List.of(answer(plan("create result")),
                                tool("create_file", "{\"path\":\"result.txt\",\"content\":\"expected\"}"), answer("Done")))),
                fixtureRoot(), temp.resolve("runs"), "case1", temp.resolve("m2"));

        assertTrue(result.reactive().evaluation().taskOutcomeSuccess());
        assertTrue(result.planExecute().evaluation().taskOutcomeSuccess());
        assertEquals(result.reactive().metrics().providerRequests() + 1,
                result.planExecute().metrics().providerRequests());
        assertEquals(task.maxProviderRequests(), result.sharedRequestCap());
        assertEquals(0, count(result.reactive(), AgentActionType.PLAN_CREATED));
        assertEquals(0, count(result.reactive(), AgentActionType.REPLAN));
        assertEquals(1, result.planExecute().metrics().planStepsTotal());
    }

    @Test
    void caseTwoPlanningUsesTypedFailureEvidenceToReplanAndComplete() throws Exception {
        PlanningBenchmarkTask task = createTask();
        var reactive = new ScriptedClient(List.of(
                tool("read_file", "{\"path\":\"missing.txt\"}"), answer("Done")));
        var planned = new ScriptedClient(List.of(
                answer(plan("inspect and create")),
                tool("read_file", "{\"path\":\"missing.txt\"}"),
                answer(plan("create result after missing source")),
                tool("create_file", "{\"path\":\"result.txt\",\"content\":\"expected\"}"),
                answer("Done")));
        var result = harness().runPair(task, clients(reactive, planned),
                fixtureRoot(), temp.resolve("runs"), "case2", temp.resolve("m2"));

        assertFalse(result.reactive().evaluation().taskOutcomeSuccess());
        assertTrue(result.planExecute().evaluation().taskOutcomeSuccess());
        assertEquals(1, result.planExecute().metrics().replanCount());
        assertTrue(result.planExecute().metrics().typedFailures() >= 1);
        assertEquals(5, result.planExecute().metrics().providerRequests());
    }

    @Test
    void caseThreeMalformedPlanFallsBackAndReactiveExecutionCanSucceed() throws Exception {
        PlanningBenchmarkTask task = createTask();
        var planned = runSingle(task, PlanningMode.PLAN_EXECUTE, List.of(
                answer("not a JSON plan"),
                tool("create_file", "{\"path\":\"result.txt\",\"content\":\"expected\"}"),
                answer("Done")), "case3");
        assertTrue(planned.evaluation().taskOutcomeSuccess());
        assertEquals(1, planned.metrics().planFallback());
        assertEquals("FALLBACK_REACTIVE", planned.metrics().planOutcome());
        assertEquals(3, planned.metrics().providerRequests());
    }

    @Test
    void caseFourReplanRequestIsIncludedInBudgetAndMetric() throws Exception {
        PlanningBenchmarkTask task = createTask();
        var result = runSingle(task, PlanningMode.PLAN_EXECUTE, List.of(
                answer(plan("read then create")),
                tool("read_file", "{\"path\":\"missing.txt\"}"),
                answer(plan("create after observed missing file")),
                tool("create_file", "{\"path\":\"result.txt\",\"content\":\"expected\"}"),
                answer("Done")), "case4");
        assertTrue(result.evaluation().taskOutcomeSuccess());
        assertEquals(1, result.metrics().replanCount());
        assertEquals(5, result.metrics().providerRequests());
    }

    @Test
    void targetDriftFailsEvenWhenModelClaimsSuccess() throws Exception {
        PlanningBenchmarkTask task = new PlanningBenchmarkTask("drift", "test", "fixture",
                "create result", List.of("result.txt"), Map.of("result.txt", List.of("expected")),
                List.of(), List.of(), List.of(), List.of(), false, false, 6, 12);
        var result = runSingle(task, PlanningMode.REACTIVE, List.of(
                tool("create_file", "{\"path\":\"other.txt\",\"content\":\"unexpected\"}"), answer("Done")), "drift");
        assertFalse(result.evaluation().taskOutcomeSuccess());
        assertEquals(1, result.metrics().targetDrift());
    }

    private PlanningRuntimeHarness.RunResult runSingle(PlanningBenchmarkTask task, PlanningMode mode,
                                                       List<LLMResponse> responses, String runId) throws Exception {
        return harness().run(task, mode, new ScriptedClient(responses), fixtureRoot(), temp.resolve("runs"),
                runId, temp.resolve("m2"));
    }

    private PlanningRuntimeHarness harness() { return new PlanningRuntimeHarness(); }

    private static java.util.function.Supplier<LLMClient> pairedClients(LLMClient reactive, LLMClient planned) {
        return clients(reactive, planned);
    }

    private static java.util.function.Supplier<LLMClient> clients(LLMClient reactive, LLMClient planned) {
        Deque<LLMClient> clients = new ArrayDeque<>(List.of(reactive, planned));
        return clients::removeFirst;
    }

    private PlanningBenchmarkTask createTask() {
        return new PlanningBenchmarkTask("create-result", "scripted", "fixture", "Create result.txt",
                List.of("result.txt"), Map.of("result.txt", List.of("expected")), List.of(), List.of(),
                List.of(), List.of(), false, true, 8, 16);
    }

    private Path fixtureRoot() throws IOException {
        Path root = temp.resolve("fixtures");
        Files.createDirectories(root.resolve("fixture"));
        Files.writeString(root.resolve("fixture/seed.txt"), "seed\n");
        return root;
    }

    private static int count(PlanningRuntimeHarness.RunResult run, AgentActionType type) {
        return (int) run.trajectory().steps().stream().filter(step -> step.actionType() == type).count();
    }

    private static String plan(String text) {
        return "{\"goal\":\"complete request\",\"steps\":[{\"id\":\"S1\",\"description\":\"" + text + "\"}]}";
    }

    private static LLMResponse answer(String text) { return new LLMResponse(text, List.of()); }

    private static LLMResponse tool(String name, String arguments) {
        return new LLMResponse("", List.of(new ToolCall("call-" + name, name, arguments)));
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> replies;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolDefinitions = new ArrayList<>();

        private ScriptedClient(List<LLMResponse> replies) { this.replies = new ArrayDeque<>(replies); }

        @Override public LLMResponse chat(List<Message> messages) throws IOException {
            return chat(messages, List.of());
        }

        @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            requests.add(List.copyOf(messages));
            toolDefinitions.add(List.copyOf(tools));
            if (replies.isEmpty()) throw new IOException("scripted provider responses exhausted");
            return replies.removeFirst();
        }
    }
}
