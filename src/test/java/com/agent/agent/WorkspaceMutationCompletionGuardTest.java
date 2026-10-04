package com.agent.agent;

import com.agent.CliAgentFactory;
import com.agent.CliIntentRouter;
import com.agent.CliMode;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.Tool;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMutationCompletionGuardTest {
    @TempDir
    Path workspace;

    @Test
    void hanoiCreateThenFibonacciFollowUpCannotCompleteFromDirectFinal() throws Exception {
        String hanoi = "def hanoi(n, source, target, auxiliary):\n"
                + "    if n == 1:\n"
                + "        print(source, '->', target)\n";
        String fibonacci = "\n\ndef fibonacci(n):\n"
                + "    if n < 2:\n"
                + "        return n\n"
                + "    return fibonacci(n - 1) + fibonacci(n - 2)";
        ScriptedClient client = new ScriptedClient(List.of(
                call("create-hanoi", "create_file", Map.of("path", "hanoi_tower_solution.py", "content", hanoi)),
                answer("Hanoi function created."),
                answer("已经在同一文件中添加 fibonacci 函数。"),
                call("read-hanoi", "read_file", Map.of("path", "hanoi_tower_solution.py")),
                call("insert-fibonacci", "insert_after", Map.of(
                        "path", "hanoi_tower_solution.py",
                        "anchor", "        print(source, '->', target)",
                        "content", fibonacci)),
                answer("Fibonacci was added and verified.")
        ));
        Agent agent = codingAgent(client, workspace, passRunner());

        CliIntentRouter router = new CliIntentRouter();
        assertEquals(CliMode.CODE, router.route("请在该目录下实现一个函数，用于解决汉诺塔问题").mode());
        AgentRunResult firstTurn = agent.runWithTrajectory("请在该目录下实现一个函数，用于解决汉诺塔问题");
        Path source = workspace.resolve("hanoi_tower_solution.py");
        assertTrue(firstTurn.trajectory().completed());
        assertTrue(Files.readString(source).contains("def hanoi"));

        String followUp = "请在刚刚的代码里加一个函数，用于计算斐波那契数列";
        assertEquals(CliMode.CODE, router.route(followUp).mode());
        AgentRunResult secondTurn = agent.runWithTrajectory(followUp);

        String finalSource = Files.readString(source);
        assertTrue(secondTurn.trajectory().completed());
        assertTrue(finalSource.contains("def hanoi"));
        assertTrue(finalSource.contains("def fibonacci"));
        assertEquals(List.of("read_file", "insert_after"), toolNames(secondTurn));
        assertEquals(1, secondTurn.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD)
                .filter(step -> "WORKSPACE_CHANGE_REQUIRED".equals(step.errorMessage())).count());
        assertTrue(secondTurn.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.AUTO_REREAD && step.toolResult().success()));
        assertTrue(secondTurn.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "PASS".equals(step.arguments().get("status"))
                        && "python-py-compile".equals(step.arguments().get("verifier"))));
        assertTrue(client.messageSnapshots().stream().flatMap(List::stream)
                .anyMatch(message -> message.role().equals("system")
                        && message.content().startsWith("WORKSPACE_CHANGE_REQUIRED:")));
    }

    @Test
    void repeatedDirectFinalsCannotCompleteMutationRequiredTurn() throws Exception {
        Files.writeString(workspace.resolve("example.py"), "def existing():\n    return 1\n");
        ScriptedClient client = new ScriptedClient(List.of(
                answer("Done."), answer("The foo function is added."), answer("Completed.")));
        Agent agent = codingAgent(client, workspace, passRunner(), 3);

        AgentRunResult result = agent.runWithTrajectory("在 example.py 里添加 foo 函数");

        assertFalse(result.trajectory().completed());
        assertEquals(TerminationReason.MAX_STEPS, result.trajectory().terminationReason());
        assertFalse(Files.readString(workspace.resolve("example.py")).contains("def foo"));
        assertEquals(3, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD)
                .count());
        assertEquals(1, client.messageSnapshots().get(1).stream()
                .filter(message -> message.role().equals("system")
                        && message.content().startsWith("WORKSPACE_CHANGE_REQUIRED:")).count());
    }

    @Test
    void directCodeAnswerCannotReplaceCreatingTheRequestedFile() {
        ScriptedClient client = new ScriptedClient(List.of(
                answer("Create hello.py with: print('hello')"), answer("The file is ready.")));
        Agent agent = codingAgent(client, workspace, passRunner(), 2);

        AgentRunResult result = agent.runWithTrajectory("新建 hello.py");

        assertFalse(result.trajectory().completed());
        assertEquals(TerminationReason.MAX_STEPS, result.trajectory().terminationReason());
        assertFalse(Files.exists(workspace.resolve("hello.py")));
        assertEquals(2, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD).count());
    }

    @Test
    void successfulResultWithChangedFalseDoesNotSatisfyMutationRequirement() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "apply_patch"; }
            @Override public String description() { return "test no-op patch"; }
            @Override public ToolResult execute(String arguments) {
                return ToolResult.success("No change", Map.of("path", "example.py", "changed", false));
            }
        });
        Agent agent = codeAgent(new ScriptedClient(List.of(
                call("no-op", "apply_patch", Map.of("path", "example.py")),
                answer("Updated."), answer("Done."))), registry, 3);

        AgentRunResult result = agent.runWithTrajectory("Modify example.py");

        assertFalse(result.trajectory().completed());
        assertEquals(2, result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD).count());
    }

    @Test
    void verificationFailureStillBlocksFinalAfterSuccessfulMutation() {
        ScriptedClient client = new ScriptedClient(List.of(
                call("create-invalid", "create_file", Map.of("path", "broken.py", "content", "def x(:\n")),
                answer("Created and verified."), answer("Everything is correct.")));
        Agent agent = codingAgent(client, workspace, new FixedResultRunner(10, "SyntaxError"), 5);

        AgentRunResult result = agent.runWithTrajectory("创建 broken.py 文件");

        assertFalse(result.trajectory().completed());
        assertTrue(result.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "FAIL".equals(step.arguments().get("status"))));
        assertFalse(result.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.COMPLETION_GUARD));
        assertTrue(result.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK
                        && step.errorMessage().startsWith("POST_EDIT_VERIFICATION_FAILURE")));
    }

    @Test
    void codeProfileAllowsReadOnlyAndExplicitNoModificationTurns() throws Exception {
        Files.writeString(workspace.resolve("example.py"), "def foo():\n    return 1\n");
        Agent readOnlyAgent = codingAgent(new ScriptedClient(List.of(
                call("read", "read_file", Map.of("path", "example.py")),
                answer("The file defines foo."))), workspace, passRunner());
        AgentRunResult readOnly = readOnlyAgent.runWithTrajectory("解释一下 example.py 做了什么");
        assertTrue(readOnly.trajectory().completed());
        assertEquals(0, readOnly.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD).count());

        Agent analysisAgent = codingAgent(new ScriptedClient(List.of(
                call("read", "read_file", Map.of("path", "example.py")),
                answer("There may be no issue; I made no changes."))), workspace, passRunner());
        AgentRunResult analysis = analysisAgent.runWithTrajectory(
                "检查这个函数可能有什么问题，但不要修改文件");
        assertTrue(analysis.trajectory().completed());
        assertEquals(0, analysis.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.COMPLETION_GUARD).count());
    }

    @Test
    void readOnlyTaskModeNeverRequiresMutation() {
        Agent agent = new Agent(new ScriptedClient(List.of(answer("Here is an explanation."))),
                new ToolRegistry(), "read mode", 2, TaskMode.READ_ONLY);
        AgentRunResult result = agent.runWithTrajectory("Please explain the algorithm");
        assertTrue(result.trajectory().completed());
    }

    private Agent codingAgent(ScriptedClient client, Path root, ProcessRunner runner) {
        return codingAgent(client, root, runner, Agent.MAX_ITERATIONS);
    }

    private Agent codingAgent(ScriptedClient client, Path root, ProcessRunner runner, int maxIterations) {
        ToolRegistry registry = ToolRegistry.withCliCodingTools(root, runner, root.resolve(".m2/repository"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(
                root, registry, BuiltInCodeVerifiers.registry(runner));
        return new Agent(client, environment, "test coding agent", maxIterations,
                TaskMode.CODE_MODIFICATION, true, false, com.agent.agent.AgentEventListener.NO_OP, false);
    }

    private Agent codeAgent(ScriptedClient client, ToolRegistry registry, int maxIterations) {
        return new Agent(client, registry, "test coding agent", maxIterations, TaskMode.CODE_MODIFICATION);
    }

    private static List<String> toolNames(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .map(AgentStep::toolName)
                .toList();
    }

    private static LLMResponse call(String id, String name, Map<String, Object> arguments) {
        try {
            return new LLMResponse("", List.of(new ToolCall(id, name,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize test tool arguments", exception);
        }
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static ProcessRunner passRunner() {
        return new FixedResultRunner(0, "syntax check passed");
    }

    private static final class FixedResultRunner implements ProcessRunner {
        private final int exitCode;
        private final String output;
        private final List<List<String>> commands = new ArrayList<>();

        private FixedResultRunner(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        public ProcessExecutionResult run(List<String> command, Path workingDirectory,
                                          Duration timeout, int maxOutputBytes) {
            commands.add(List.copyOf(command));
            return new ProcessExecutionResult(exitCode, false, output, false, 1);
        }
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> messageSnapshots = new ArrayList<>();

        private ScriptedClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            messageSnapshots.add(List.copyOf(messages));
            return responses.removeFirst();
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<com.agent.llm.ToolDefinition> tools) {
            return chat(messages);
        }

        private List<List<Message>> messageSnapshots() {
            return List.copyOf(messageSnapshots);
        }
    }
}
