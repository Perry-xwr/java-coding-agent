package com.agent.agent;

import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentRequestContextManagementTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "public class FibonacciIterative {\n"
            + "    public static int fibonacci(int n) {\n"
            + "        if (n < 2) return n;\n"
            + "        int a = 0, b = 1;\n"
            + "        for (int i = 2; i <= n; i++) { int next = a + b; a = b; b = next; }\n"
            + "        return b;\n"
            + "    }\n"
            + "    public static void main(String[] args) {\n"
            + "        for (int i = 0; i < 10; i++) System.out.println(fibonacci(i));\n"
            + "    }\n"
            + "}\n";

    @TempDir Path workspace;
    private String previousSetting;

    @AfterEach
    void restoreSetting() {
        if (previousSetting == null) System.clearProperty(RequestContextSelector.ENABLED_PROPERTY);
        else System.setProperty(RequestContextSelector.ENABLED_PROPERTY, previousSetting);
    }

    @Test
    void threeTurnJavaEditKeepsPriorFileSnapshotsAndRawHistoryWithSelectionEnabled() throws Exception {
        enableSelection();
        String withFactorial = ORIGINAL.replace("    public static void main", "    public static long factorial(int n) {\n"
                + "        long result = 1;\n"
                + "        for (int i = 2; i <= n; i++) result *= i;\n"
                + "        return result;\n"
                + "    }\n    public static void main");
        String withRecursiveFactorial = withFactorial.replace(
                "        long result = 1;\n        for (int i = 2; i <= n; i++) result *= i;\n        return result;",
                "        if (n <= 1) return 1;\n        return n * factorial(n - 1);");
        ScriptClient client = new ScriptClient(
                call("create", "create_file", Map.of("path", "FibonacciIterative.java", "content", ORIGINAL)),
                answer("Created."),
                patch("patch-factorial", ORIGINAL, "    public static void main", "    public static long factorial(int n) {\n"
                        + "        long result = 1;\n        for (int i = 2; i <= n; i++) result *= i;\n"
                        + "        return result;\n    }\n    public static void main"),
                answer("Added factorial."),
                patch("patch-recursive", withFactorial,
                        "        long result = 1;\n        for (int i = 2; i <= n; i++) result *= i;\n        return result;",
                        "        if (n <= 1) return 1;\n        return n * factorial(n - 1);"),
                answer("Updated factorial."));
        Agent agent = codingAgent(client);

        AgentRunResult first = agent.runWithTrajectory("Create FibonacciIterative.java with fibonacci(int n) and main");
        AgentRunResult second = agent.runWithTrajectory("在刚刚创建的文件中添加 factorial(int n)");
        AgentRunResult third = agent.runWithTrajectory("将前面添加的 factorial 方法改为递归实现，保留斐波那契功能");
        assertTrue(first.trajectory().completed());
        assertTrue(second.trajectory().completed());
        assertTrue(third.trajectory().completed());
        for (AgentRunResult turn : List.of(first, second, third)) {
            assertTrue(turn.trajectory().steps().stream()
                    .anyMatch(step -> step.actionType() == AgentActionType.AUTO_REREAD));
            assertTrue(turn.trajectory().steps().stream().filter(step ->
                    step.actionType() == AgentActionType.POST_EDIT_VERIFICATION)
                    .allMatch(step -> "PASS".equals(step.arguments().get("status"))));
            assertTrue(turn.trajectory().steps().stream().anyMatch(step ->
                    step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                            && "PASS".equals(step.arguments().get("status"))));
        }

        String finalSource = Files.readString(workspace.resolve("FibonacciIterative.java"));
        assertTrue(finalSource.contains("fibonacci(int n)"));
        assertTrue(finalSource.contains("factorial(int n)"));
        assertTrue(finalSource.contains("return n * factorial(n - 1);"));
        assertFalse(finalSource.contains("long result = 1;"));
        assertTrue(client.requests.get(2).stream().anyMatch(message -> message.content().contains(ORIGINAL)),
                "second turn retains the complete first-turn file snapshot");
        assertTrue(client.requests.get(4).stream().anyMatch(message -> message.content().contains(withFactorial)),
                "third turn retains the complete latest factorial snapshot");
        assertTrue(agent.history().stream().anyMatch(message -> message.content().contains(ORIGINAL)));
        assertTrue(agent.history().stream().anyMatch(message -> message.content().contains(withFactorial)));
        assertEquals(6, client.requests.size(), "selection does not add model decisions");
        assertTrue(agent.history().stream().filter(message -> "assistant".equals(message.role()))
                .flatMap(message -> message.toolCalls().stream())
                .anyMatch(call -> "apply_context_patch".equals(((Map<?, ?>) call.get("function")).get("name"))));
    }

    @Test
    void enabledSelectionDoesNotChangeRawHistorySavedInSessionSnapshot() throws Exception {
        enableSelection();
        ScriptClient client = new ScriptClient(
                call("find1", "find_files", Map.of("pattern", "*.py")), answer("Found."),
                call("find2", "find_files", Map.of("pattern", "*.py")), answer("Found."));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.agent.tool.Tool() {
            @Override public String name() { return "find_files"; }
            @Override public String description() { return "find"; }
            @Override public com.agent.tool.ToolResult execute(String args) {
                return com.agent.tool.ToolResult.success("{\"files\":[\"src/a.py\"],\"truncated\":false}");
            }
        });
        Agent agent = new Agent(client, registry, "read-only repository assistant", 8, TaskMode.READ_ONLY);
        agent.runWithTrajectory("find Python files");
        agent.runWithTrajectory("find Python files");

        assertEquals(2, countToolCalls(agent.history(), "find_files"));
        assertEquals(2, countToolResults(agent.history()));
        assertEquals(1, countToolCalls(client.requests.get(3), "find_files"),
                "only the latest matching discovery call is sent on the later decision");
        assertTrue(client.requests.get(3).stream().flatMap(message -> message.toolCalls().stream())
                .anyMatch(call -> "find2".equals(call.get("id"))));
        List<Message> rawBeforeSave = agent.history();
        List<Message> selection = RequestContextSelector.select(rawBeforeSave, rawBeforeSave.size());
        assertTrue(selection.size() < rawBeforeSave.size());
        assertEquals(2, countToolCalls(rawBeforeSave, "find_files"));
        assertEquals(2, countToolResults(rawBeforeSave));
    }

    @Test
    void fullAndSelectedModesUseIdenticalFakeTasksAndCompareSerializedPayloadBytes() throws Exception {
        String previous = System.getProperty(RequestContextSelector.ENABLED_PROPERTY);
        try {
            System.setProperty(RequestContextSelector.ENABLED_PROPERTY, "false");
            ScriptClient fullClient = discoveryClient();
            Agent fullAgent = readAgent(fullClient);
            fullAgent.runWithTrajectory("find Python files");
            fullAgent.runWithTrajectory("find Python files again");
            long fullBytes = simulatedPayloadBytes(fullClient.requests);

            System.setProperty(RequestContextSelector.ENABLED_PROPERTY, "true");
            ScriptClient selectedClient = discoveryClient();
            Agent selectedAgent = readAgent(selectedClient);
            selectedAgent.runWithTrajectory("find Python files");
            selectedAgent.runWithTrajectory("find Python files again");
            long selectedBytes = simulatedPayloadBytes(selectedClient.requests);

            assertTrue(selectedBytes < fullBytes,
                    "same scripted tasks should produce a smaller total serialized message payload");
            assertTrue(selectedClient.requests.get(3).size() < fullClient.requests.get(3).size());
            assertEquals(fullClient.requests.size(), selectedClient.requests.size(),
                    "context selection must not add model decisions");
            assertEquals(2, countToolCalls(selectedAgent.history(), "find_files"));
            assertEquals(2, countToolResults(selectedAgent.history()));
        } finally {
            if (previous == null) System.clearProperty(RequestContextSelector.ENABLED_PROPERTY);
            else System.setProperty(RequestContextSelector.ENABLED_PROPERTY, previous);
        }
    }

    private static ScriptClient discoveryClient() throws Exception {
        return new ScriptClient(
                call("find1", "find_files", Map.of("pattern", "*.py")), answer("Found."),
                call("find2", "find_files", Map.of("pattern", "*.py")), answer("Found."));
    }

    private Agent readAgent(ScriptClient client) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.agent.tool.Tool() {
            @Override public String name() { return "find_files"; }
            @Override public String description() { return "find"; }
            @Override public com.agent.tool.ToolResult execute(String args) {
                return com.agent.tool.ToolResult.success("{\"files\":[\"src/a.py\"],\"truncated\":false}");
            }
        });
        return new Agent(client, registry, "read-only repository assistant", 8, TaskMode.READ_ONLY);
    }

    private static long simulatedPayloadBytes(List<List<Message>> requests) throws Exception {
        long total = 0;
        for (List<Message> messages : requests) {
            total += JSON.writeValueAsBytes(Map.of("messages", messages, "tools", List.of())).length;
        }
        return total;
    }

    private Agent codingAgent(ScriptClient client) {
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        return new Agent(client, new LocalWorkspaceEnvironment(workspace, registry),
                "test CODE prompt", 10, TaskMode.CODE_MODIFICATION,
                false, false, AgentEventListener.NO_OP, false);
    }

    private void enableSelection() {
        previousSetting = System.getProperty(RequestContextSelector.ENABLED_PROPERTY);
        System.setProperty(RequestContextSelector.ENABLED_PROPERTY, "true");
    }

    private static int countToolCalls(List<Message> messages, String name) {
        return messages.stream().filter(message -> "assistant".equals(message.role())
                && message.toolCalls().stream().anyMatch(call -> ((Map<?, ?>) call.get("function")).get("name").equals(name)))
                .mapToInt(message -> (int) message.toolCalls().stream()
                        .filter(call -> ((Map<?, ?>) call.get("function")).get("name").equals(name)).count())
                .sum();
    }

    private static int countToolResults(List<Message> messages) {
        return (int) messages.stream().filter(message -> "tool".equals(message.role())).count();
    }

    private static LLMResponse call(String id, String name, Map<String, ?> args) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, name, JSON.writeValueAsString(args))));
    }

    private static LLMResponse patch(String id, String expected, String search, String replace) throws Exception {
        return call(id, "apply_context_patch", Map.of("path", "FibonacciIterative.java",
                "expectedContent", expected, "edits", List.of(Map.of("search", search, "replace", replace))));
    }

    private static LLMResponse answer(String content) { return new LLMResponse(content, List.of()); }

    private static final class ScriptClient implements LLMClient {
        private final Deque<LLMResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private ScriptClient(LLMResponse... responses) { this.responses.addAll(List.of(responses)); }
        @Override public LLMResponse chat(List<Message> messages) {
            requests.add(List.copyOf(messages));
            if (responses.isEmpty()) throw new AssertionError("script exhausted");
            return responses.removeFirst();
        }
    }
}
