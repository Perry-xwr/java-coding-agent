package com.agent.agent;

import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerifierRegistry;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PythonVerificationAgentFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "def fibonacci(n):\n    print('Solution does not exist')\n";
    private static final String INVALID = "def fibonacci(n):\n"
            + "    print('Solution does not exist')def hanoi(n):\n    return n\n\n";
    private static final String VALID = "def fibonacci(n):\n"
            + "    print('Solution does not exist')\n\n\n"
            + "def hanoi(n):\n    return n\n\n";

    @TempDir Path workspace;

    @Test
    void syntaxFailureIsFedBackThenContextPatchRepairIsReverifiedAgainstLatestVersion() throws Exception {
        Files.writeString(workspace.resolve("algorithms.py"), ORIGINAL);
        LocalWorkspaceEnvironment environment = realPythonEnvironment();
        VerificationResult available = environment.verifyPostEdit("algorithms.py", 0);
        assumeTrue(available.status() != VerificationStatus.UNAVAILABLE, available.unavailableReason());

        ScriptClient client = new ScriptClient(
                tool("bad-insert", "insert_after", Map.of(
                        "path", "algorithms.py",
                        "anchor", "print('Solution does not exist')",
                        "content", "def hanoi(n):\n    return n\n")),
                tool("fresh-read", "read_file", Map.of("path", "algorithms.py")),
                tool("repair", "apply_context_patch", Map.of(
                        "path", "algorithms.py",
                        "expectedContent", INVALID,
                        "edits", List.of(Map.of(
                                "search", "print('Solution does not exist')def hanoi(n):\n    return n",
                                "replace", "print('Solution does not exist')\n\n\ndef hanoi(n):\n    return n")))),
                answer("Repaired; Python syntax verification passed."));

        AgentRunResult result = agent(environment, client).runWithTrajectory(
                "Fix the syntax error in algorithms.py and add hanoi");

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().toString());
        assertEquals(VALID, Files.readString(workspace.resolve("algorithms.py")));
        assertEquals(List.of("FAIL", "PASS"), verificationStatuses(result));
        assertTrue(client.observedMessages.stream().anyMatch(message -> message.contains("SyntaxError")
                && message.contains("line 2")), client.observedMessages.toString());
        assertTrue(client.observedMessages.stream().anyMatch(message ->
                message.contains("POST_EDIT_VERIFICATION_FAILED")));
        assertFalse(Files.exists(workspace.resolve("__pycache__")));
        try (var files = Files.walk(workspace)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".pyc")));
        }
    }

    @Test
    void persistentPythonSyntaxFailureCannotCompleteEvenWhenFakeModelClaimsSuccess() throws Exception {
        Files.writeString(workspace.resolve("algorithms.py"), ORIGINAL);
        LocalWorkspaceEnvironment environment = realPythonEnvironment();
        VerificationResult available = environment.verifyPostEdit("algorithms.py", 0);
        assumeTrue(available.status() != VerificationStatus.UNAVAILABLE, available.unavailableReason());
        ScriptClient client = new ScriptClient(
                tool("bad-insert", "insert_after", Map.of(
                        "path", "algorithms.py",
                        "anchor", "print('Solution does not exist')",
                        "content", "def hanoi(n):\n    return n\n")),
                answer("The Python syntax is valid and the task is complete."),
                answer("Still complete."));

        AgentRunResult result = agent(environment, client).runWithTrajectory("Add hanoi to algorithms.py");

        assertFalse(result.trajectory().completed());
        assertEquals(List.of("FAIL"), verificationStatuses(result));
        assertEquals(INVALID, Files.readString(workspace.resolve("algorithms.py")));
        assertTrue(client.observedMessages.stream().anyMatch(message ->
                message.contains("POST_EDIT_VERIFICATION_FAILED") && message.contains("SyntaxError")));
    }

    @Test
    void everyCliMutationToolUsesTheSamePostEditVerificationPathForPython() throws Exception {
        List<String> verifiedPaths = new ArrayList<>();
        var verifier = new com.agent.environment.verification.CodeVerifier() {
            @Override public String id() { return "python-test-verifier"; }
            @Override public boolean supports(Path file, Path root) {
                return file.getFileName().toString().endsWith(".py");
            }
            @Override public VerificationResult verify(Path file, Path root, long sequence) {
                verifiedPaths.add(root.relativize(file).toString());
                return new VerificationResult(VerificationStatus.PASS, file, id(), "", "", sequence);
            }
        };
        String original = "value = 1\n";
        for (String toolName : List.of("create_file", "apply_patch", "apply_context_patch",
                "insert_before", "insert_after")) {
            Path file = workspace.resolve(toolName + ".py");
            if (!toolName.equals("create_file")) Files.writeString(file, original);
            ScriptClient client = new ScriptClient(tool(toolName, toolName, argumentsFor(toolName,
                    file.getFileName().toString(), original)), answer("Updated."));
            ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace,
                    new DefaultProcessRunner(), workspace.resolve(".m2/repository"));
            LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, registry,
                    new VerifierRegistry(List.of(verifier)));

            AgentRunResult result = agent(environment, client).runWithTrajectory("Update " + file.getFileName());

            assertTrue(result.trajectory().completed(), toolName + ": " + result.trajectory().terminationReason());
            assertEquals(1, result.trajectory().steps().stream().filter(step ->
                    step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                            && "PASS".equals(step.arguments().get("status"))).count(), toolName);
        }
        assertEquals(List.of("create_file.py", "apply_patch.py", "apply_context_patch.py",
                "insert_before.py", "insert_after.py"), verifiedPaths);
    }

    private LocalWorkspaceEnvironment realPythonEnvironment() {
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace,
                new DefaultProcessRunner(), workspace.resolve(".m2/repository"));
        return new LocalWorkspaceEnvironment(workspace, registry,
                BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
    }

    private Agent agent(LocalWorkspaceEnvironment environment, LLMClient client) {
        return new Agent(client, environment, "test system prompt", 8, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);
    }

    private static Map<String, ?> argumentsFor(String toolName, String path, String original) {
        return switch (toolName) {
            case "create_file" -> Map.of("path", path, "content", "value = 2\n");
            case "apply_patch" -> Map.of("path", path, "oldText", "value = 1", "newText", "value = 2");
            case "apply_context_patch" -> Map.of("path", path, "expectedContent", original,
                    "edits", List.of(Map.of("search", "value = 1", "replace", "value = 2")));
            case "insert_before" -> Map.of("path", path, "anchor", "value = 1", "content", "# added\n");
            case "insert_after" -> Map.of("path", path, "anchor", "value = 1", "content", "\nvalue = 2");
            default -> throw new IllegalArgumentException("Unexpected tool: " + toolName);
        };
    }

    private static List<String> verificationStatuses(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION)
                .map(step -> Objects.toString(step.arguments().get("status"), ""))
                .toList();
    }

    private static LLMResponse tool(String id, String name, Map<String, ?> arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, name, JSON.writeValueAsString(arguments))));
    }

    private static LLMResponse answer(String value) { return new LLMResponse(value, List.of()); }

    private static final class ScriptClient implements LLMClient {
        private final Deque<LLMResponse> responses = new ArrayDeque<>();
        private final List<String> observedMessages = new ArrayList<>();

        private ScriptClient(LLMResponse... scriptedResponses) {
            responses.addAll(List.of(scriptedResponses));
        }

        @Override public LLMResponse chat(List<Message> messages) {
            observedMessages.add(messages.stream().map(Message::content).reduce("", (a, b) -> a + "\n" + b));
            if (responses.isEmpty()) throw new AssertionError(
                    "Fake model response script exhausted; observed=" + observedMessages);
            return responses.removeFirst();
        }
    }
}
