package com.agent.agent;

import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ApplyContextPatchAgentFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "public class Algorithms {\n"
            + "    int fibonacci(int n) {\n        return n;\n    }\n}\n";
    @TempDir Path workspace;

    @Test
    void toolCallIsTrackedAsMutationAndAutoVerified() throws Exception {
        Files.writeString(workspace.resolve("Algorithms.java"), ORIGINAL);
        String updated = ORIGINAL.replace("    }\n}\n", "    }\n\n    void hanoi() {}\n}\n");
        ScriptClient client = new ScriptClient(
                tool("r1", "read_file", Map.of("path", "Algorithms.java")),
                patch("p1", ORIGINAL, "    }\n}", "    }\n\n    void hanoi() {}\n}"),
                answer("Added hanoi."));
        AgentRunResult result = agent(client).runWithTrajectory("Add a hanoi method to Algorithms.java");

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().toString());
        assertEquals(updated, Files.readString(workspace.resolve("Algorithms.java")));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.AUTO_REREAD && "read_file".equals(step.toolName())));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "PASS".equals(step.arguments().get("status"))));
        assertEquals(1, result.changeReview().changedFiles().size());
        assertEquals("Algorithms.java", result.changeReview().changedFiles().get(0).path());
        assertTrue(result.changeReview().changedFiles().get(0).diff().contains("+    void hanoi() {}"));
        assertEquals(result.changeReview().changedFiles(), result.evidence().changedFiles());
    }

    @Test
    void javacFailureFromBadContextEditIsFedBackAndFakeModelCanRepair() throws Exception {
        Files.writeString(workspace.resolve("Algorithms.java"), ORIGINAL);
        String broken = ORIGINAL.replace("        return n;\n", "        return n;\n        void hanoi() {}\n");
        String clientFixSearch = "        return n;\n        void hanoi() {}\n    }\n}";
        String clientFixReplace = "        return n;\n    }\n\n    void hanoi() {}\n}";
        ScriptClient client = new ScriptClient(
                tool("r1", "read_file", Map.of("path", "Algorithms.java")),
                patch("p1", ORIGINAL, "        return n;", "        return n;\n        void hanoi() {}"),
                tool("r2", "read_file", Map.of("path", "Algorithms.java")),
                patch("p2", broken, clientFixSearch, clientFixReplace),
                answer("Added hanoi and compiler verification passed."));

        AgentRunResult result = agent(client).runWithTrajectory("Add a hanoi method to Algorithms.java");
        List<String> verificationStatuses = result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION)
                .map(step -> Objects.toString(step.arguments().get("status"), ""))
                .toList();

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().toString());
        assertEquals(List.of("FAIL", "PASS"), verificationStatuses);
        assertTrue(client.observed.stream().anyMatch(messages -> messages.contains("POST_EDIT_VERIFICATION_FAILED")
                && (messages.contains("illegal start") || messages.contains("not allowed here")
                || messages.contains("expected"))));
        String finalSource = Files.readString(workspace.resolve("Algorithms.java"));
        assertTrue(finalSource.contains("void hanoi() {}"));
        assertTrue(finalSource.contains("int fibonacci(int n)"));
        assertTrue(finalSource.indexOf("void hanoi") > finalSource.indexOf("    }\n"));
    }

    private Agent agent(LLMClient client) {
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        return new Agent(client,
                new LocalWorkspaceEnvironment(workspace, registry), "test system prompt", 10,
                TaskMode.CODE_MODIFICATION, true, false, AgentEventListener.NO_OP, false);
    }

    private static LLMResponse patch(String id, String expected, String search, String replace) throws Exception {
        return tool(id, "apply_context_patch", Map.of("path", "Algorithms.java", "expectedContent", expected,
                "edits", List.of(Map.of("search", search, "replace", replace))));
    }

    private static LLMResponse tool(String id, String name, Map<String, ?> args) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, name, JSON.writeValueAsString(args))));
    }

    private static LLMResponse answer(String text) { return new LLMResponse(text, List.of()); }

    private static final class ScriptClient implements LLMClient {
        private final Deque<LLMResponse> responses = new ArrayDeque<>();
        private final List<String> observed = new ArrayList<>();

        private ScriptClient(LLMResponse... responses) { this.responses.addAll(List.of(responses)); }

        @Override public LLMResponse chat(List<Message> messages) {
            observed.add(messages.stream().map(Message::content).reduce("", (a, b) -> a + "\n" + b));
            if (responses.isEmpty()) throw new AssertionError("Fake LLM response script exhausted");
            return responses.removeFirst();
        }
    }
}
