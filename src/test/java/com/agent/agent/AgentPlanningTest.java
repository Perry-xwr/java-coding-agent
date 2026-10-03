package com.agent.agent;

import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.environment.AgentEnvironment;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanningTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void planExecutePlansFirstWithoutToolsThenUsesEnvironmentAndReread() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(
                answer(plan("创建 hello.py", "创建文件", "创建后重新读取文件")),
                tool("create", "create_file", "{\"path\":\"hello.py\",\"content\":\"print('hi')\\n\"}"),
                answer("Created hello.py and confirmed it.")));
        Agent agent = codingAgent(client, PlanningMode.PLAN_EXECUTE, fileEnvironment());

        AgentRunResult result = agent.runWithTrajectory("Create hello.py");

        assertTrue(result.trajectory().completed());
        assertEquals("print('hi')\n", Files.readString(workspace.resolve("hello.py")));
        assertEquals(3, client.requests.size());
        assertTrue(client.toolRequests.get(0).isEmpty(), "PLAN must not expose tools");
        assertTrue(client.requests.get(0).stream().anyMatch(m -> m.content().contains("[PLANNING_PHASE]")));
        assertTrue(client.requests.get(1).stream().anyMatch(m -> m.content().contains("Current Execution Plan")));
        assertEquals(1, count(result, AgentActionType.PLAN_CREATED));
        assertEquals(0, count(result, AgentActionType.PLAN_UPDATED),
                "structured PLAN_EXECUTE must not enable legacy inline plan updates");
        assertTrue(count(result, AgentActionType.PLAN_STEP_UPDATE) >= 3);
        assertEquals("COMPLETED", outcome(result));
        assertTrue(result.trajectory().steps().stream().anyMatch(s -> s.actionType() == AgentActionType.AUTO_REREAD));
    }

    @Test
    void malformedPlanFallsBackToReactiveWithoutCrashingOrCallingToolsDuringPlan() {
        ScriptedClient client = new ScriptedClient(List.of(
                answer("not json"),
                tool("create", "create_file", "{\"path\":\"fallback.txt\",\"content\":\"ok\"}"),
                answer("Created with reactive execution.")));

        AgentRunResult result = codingAgent(client, PlanningMode.PLAN_EXECUTE, fileEnvironment())
                .runWithTrajectory("Create fallback.txt");

        assertTrue(result.trajectory().completed());
        assertEquals(1, count(result, AgentActionType.PLAN_FALLBACK));
        assertEquals("FALLBACK_REACTIVE", outcome(result));
        assertTrue(client.toolRequests.get(0).isEmpty());
        assertEquals("ok", read("fallback.txt"));
    }

    @Test
    void typedFailuresTriggerAtMostOneReplan() {
        ScriptedClient client = new ScriptedClient(List.of(
                answer(plan("Recover missing path", "Read requested file", "Create fallback file")),
                tool("missing-1", "read_file", "{\"path\":\"missing.txt\"}"),
                answer(plan("Continue after observed failure", "Try read", "Create result")),
                tool("missing-2", "read_file", "{\"path\":\"missing.txt\"}"),
                tool("create", "create_file", "{\"path\":\"recovered.txt\",\"content\":\"done\"}"),
                answer("Recovered.")));

        AgentRunResult result = codingAgent(client, PlanningMode.PLAN_EXECUTE, fileEnvironment())
                .runWithTrajectory("Create a recovery result if the source is missing");

        assertTrue(result.trajectory().completed());
        assertEquals(1, count(result, AgentActionType.REPLAN));
        assertEquals(2, client.requests.stream().filter(AgentPlanningTest::isPlanningRequest).count());
        assertTrue(client.requests.stream().filter(AgentPlanningTest::isPlanningRequest)
                .allMatch(messages -> client.toolRequests.get(client.requests.indexOf(messages)).isEmpty()));
        assertEquals("done", read("recovered.txt"));
    }

    @Test
    void failedVerificationCanTriggerTheSingleBoundedReplan() {
        ScriptedClient client = new ScriptedClient(List.of(
                answer(plan("Verify and recover", "Run verification", "Create report")),
                tool("test-1", "run_maven_test", "{}"),
                answer(plan("Continue after test failure", "Retry verification", "Create report")),
                tool("test-2", "run_maven_test", "{}"),
                tool("create", "create_file", "{\"path\":\"verification.txt\",\"content\":\"done\"}"),
                answer("Completed after bounded recovery.")));

        AgentRunResult result = codingAgent(client, PlanningMode.PLAN_EXECUTE, fileEnvironment())
                .runWithTrajectory("Create a verification report after checking the workspace");

        assertTrue(result.trajectory().completed());
        assertEquals(1, count(result, AgentActionType.REPLAN));
        assertEquals(2, client.requests.stream().filter(AgentPlanningTest::isPlanningRequest).count());
        assertEquals("done", read("verification.txt"));
    }

    @Test
    void planningRequestUsesTheSameBudgetedClient() {
        ScriptedClient delegate = new ScriptedClient(List.of(
                answer(plan("Create file", "Create file", "Read file after creation")),
                tool("create", "create_file", "{\"path\":\"budget.txt\",\"content\":\"ok\"}"),
                answer("done")));
        ProviderBudget budget = new ProviderBudget(3);
        BudgetedLlmClient budgeted = new BudgetedLlmClient(delegate, budget, 3);

        AgentRunResult result = codingAgent(budgeted, PlanningMode.PLAN_EXECUTE, fileEnvironment())
                .runWithTrajectory("Create budget.txt");

        assertTrue(result.trajectory().completed());
        assertEquals(3, budget.used());
        assertEquals(3, budgeted.used());
        assertEquals(3, delegate.requests.size());
    }

    @Test
    void planningModeDefaultsToReactiveAndRejectsUnknownConfiguration() {
        assertEquals(PlanningMode.REACTIVE, PlanningMode.fromValue(null));
        assertEquals(PlanningMode.REACTIVE, PlanningMode.fromValue(""));
        assertEquals(PlanningMode.PLAN_EXECUTE, PlanningMode.fromValue("plan-execute"));
        assertEquals(PlanningMode.ADAPTIVE, PlanningMode.fromValue("adaptive"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PlanningMode.fromValue("planner"));
    }

    private Agent codingAgent(LLMClient client, PlanningMode mode, AgentEnvironment environment) {
        return new Agent(client, environment, "test CODE system", 8,
                TaskMode.CODE_MODIFICATION, true, AgentEventListener.NO_OP, false, mode);
    }

    private AgentEnvironment fileEnvironment() {
        List<ToolDefinition> definitions = List.of(
                new ToolDefinition("create_file", "create a new file", Map.of()),
                new ToolDefinition("read_file", "read a file", Map.of()),
                new ToolDefinition("run_maven_test", "run tests", Map.of()));
        return new AgentEnvironment() {
            @Override
            public ToolResult execute(ToolCall call) {
                try {
                    if (call.name().equals("run_maven_test")) {
                        return ToolResult.failure(ToolErrorCode.TEST_FAILED, "assertion failed");
                    }
                    var args = MAPPER.readTree(call.arguments());
                    Path path = workspace.resolve(args.get("path").asText()).normalize();
                    if (call.name().equals("read_file")) {
                        if (!Files.isRegularFile(path)) {
                            return ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, "No such file");
                        }
                        return ToolResult.success(Files.readString(path));
                    }
                    if (Files.exists(path)) {
                        return ToolResult.failure(ToolErrorCode.FILE_ALREADY_EXISTS, "Already exists");
                    }
                    Files.writeString(path, args.get("content").asText());
                    return ToolResult.success("created", Map.of("changed", true));
                } catch (IOException exception) {
                    return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, exception.getMessage());
                }
            }

            @Override
            public List<ToolDefinition> toolDefinitions() {
                return definitions;
            }

            @Override
            public VerificationResult verifyPostEdit(String relativePath, long mutationSequence) {
                return new VerificationResult(VerificationStatus.NOT_APPLICABLE,
                        Path.of(relativePath), "scripted-environment", "", "", mutationSequence);
            }
        };
    }

    private String read(String name) {
        try {
            return Files.readString(workspace.resolve(name));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String plan(String goal, String first, String second) {
        return "{\"goal\":\"" + goal + "\",\"steps\":["
                + "{\"id\":1,\"description\":\"" + first + "\"},"
                + "{\"id\":2,\"description\":\"" + second + "\"}]}";
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static LLMResponse tool(String id, String name, String args) {
        return new LLMResponse("", List.of(new ToolCall(id, name, args)));
    }

    private static long count(AgentRunResult result, AgentActionType type) {
        return result.trajectory().steps().stream().filter(step -> step.actionType() == type).count();
    }

    private static String outcome(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.PLAN_STEP_UPDATE)
                .map(step -> step.arguments().get("outcome"))
                .filter(java.util.Objects::nonNull).map(Object::toString).findFirst().orElse("");
    }

    private static boolean isPlanningRequest(List<Message> messages) {
        return messages.stream().anyMatch(message -> message.content().contains("[PLANNING_PHASE]"));
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolRequests = new ArrayList<>();

        private ScriptedClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) throws IOException {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolRequests.add(List.copyOf(tools));
            return responses.removeFirst();
        }
    }
}
