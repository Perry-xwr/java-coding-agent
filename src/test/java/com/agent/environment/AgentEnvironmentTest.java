package com.agent.environment;

import com.agent.CliMode;
import com.agent.agent.Agent;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.TaskMode;
import com.agent.environment.verification.VerificationStatus;
import com.agent.benchmark.v12.V12Evaluator;
import com.agent.benchmark.v12.V12RuntimeHarness;
import com.agent.benchmark.v12.V12Split;
import com.agent.benchmark.v12.V12Task;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.DefaultProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentEnvironmentTest {
    @TempDir
    Path temporary;

    @Test
    void defaultEnvironmentDistinguishesUnsupportedSourceFromNonCodeFile() {
        AgentEnvironment environment = new AgentEnvironment() {
            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "not available");
            }

            @Override
            public List<com.agent.llm.ToolDefinition> toolDefinitions() {
                return List.of();
            }
        };

        assertEquals(VerificationStatus.UNAVAILABLE, environment.verifyPostEdit("App.py", 1).status());
        assertEquals(VerificationStatus.NOT_APPLICABLE, environment.verifyPostEdit("README.md", 2).status());
    }

    @Test
    void exposesRegisteredDefinitionsAndBoundWorkspace() {
        ToolRegistry registry = ToolRegistry.withFileTools(temporary);
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(temporary, registry);

        assertEquals(temporary.toAbsolutePath().normalize(), environment.workspaceRoot());
        assertEquals(registry.definitions(), environment.toolDefinitions());
        assertTrue(environment.toolDefinitions().stream().anyMatch(d -> d.name().equals("read_file")));
    }

    @Test
    void rejectsRegistryBoundToAnotherWorkspace() throws IOException {
        Path other = Files.createDirectory(temporary.resolve("other"));
        ToolRegistry registry = ToolRegistry.withFileTools(other);

        assertThrows(IllegalArgumentException.class,
                () -> new LocalWorkspaceEnvironment(temporary, registry));
    }

    @Test
    void preservesTypedUnknownToolError() {
        LocalWorkspaceEnvironment environment = environment(temporary, ToolRegistry.withFileTools(temporary));

        ToolResult result = environment.execute(new ToolCall("unknown-1", "missing_tool", "{}"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.errorCode());
    }

    @Test
    void executesReadAndMutationThroughEnvironment() throws IOException {
        Files.writeString(temporary.resolve("note.txt"), "old value\n");
        LocalWorkspaceEnvironment environment = environment(
                temporary,
                ToolRegistry.withCliCodingTools(temporary, new DefaultProcessRunner(),
                        temporary.resolve(".m2/repository")));

        ToolResult read = execute(environment, "read_file", Map.of("path", "note.txt"));
        ToolResult patch = execute(environment, "apply_patch", Map.of(
                "path", "note.txt", "oldText", "old value", "newText", "new value"));

        assertTrue(read.success());
        assertTrue(patch.success());
        assertEquals("new value\n", Files.readString(temporary.resolve("note.txt")));
    }

    @Test
    void differentWorkspaceEnvironmentsDoNotShareFiles() throws IOException {
        Path workspaceA = Files.createDirectory(temporary.resolve("workspace-a"));
        Path workspaceB = Files.createDirectory(temporary.resolve("workspace-b"));
        Files.writeString(workspaceA.resolve("same.txt"), "A\n");
        Files.writeString(workspaceB.resolve("same.txt"), "B\n");
        LocalWorkspaceEnvironment envA = environment(workspaceA,
                ToolRegistry.withCliCodingTools(workspaceA, new DefaultProcessRunner(), workspaceA.resolve(".m2")));
        LocalWorkspaceEnvironment envB = environment(workspaceB,
                ToolRegistry.withCliCodingTools(workspaceB, new DefaultProcessRunner(), workspaceB.resolve(".m2")));

        assertTrue(execute(envA, "apply_patch", Map.of(
                "path", "same.txt", "oldText", "A", "newText", "A-edited")).success());

        assertEquals("A-edited\n", Files.readString(workspaceA.resolve("same.txt")));
        assertEquals("B\n", Files.readString(workspaceB.resolve("same.txt")));
        assertFalse(envA.workspaceRoot().equals(envB.workspaceRoot()));
    }

    @Test
    void traversalAndSymlinkEscapeRemainBlocked() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        LocalWorkspaceEnvironment environment = environment(workspace, ToolRegistry.withFileTools(workspace));

        ToolResult traversal = execute(environment, "read_file", Map.of("path", "../outside/secret.txt"));
        assertFalse(traversal.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, traversal.errorCode());

        try {
            Files.createSymbolicLink(workspace.resolve("escape.txt"), outside.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        ToolResult symlink = execute(environment, "read_file", Map.of("path", "escape.txt"));
        assertFalse(symlink.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, symlink.errorCode());
    }

    @Test
    void fakeLlmAgentCompletesReadEditAndVerificationThroughEnvironment() throws IOException {
        Files.writeString(temporary.resolve("note.txt"), "old\n");
        LocalWorkspaceEnvironment environment = environment(
                temporary,
                ToolRegistry.withCliCodingTools(temporary, new DefaultProcessRunner(),
                        temporary.resolve(".m2/repository")));
        LLMClient fake = scripted(
                call("read_file", Map.of("path", "note.txt")),
                call("apply_patch", Map.of("path", "note.txt", "oldText", "old", "newText", "new")),
                new LLMResponse("Updated note.txt.", List.of())
        );
        Agent agent = new Agent(fake, environment, "test", 6, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);

        AgentRunResult result = agent.runWithTrajectory("修改 note.txt，把 old 改成 new");

        assertTrue(result.trajectory().completed());
        assertEquals("new\n", Files.readString(temporary.resolve("note.txt")));
        assertTrue(result.trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.AUTO_REREAD
                        && step.toolResult().success()));
    }

    @Test
    void v12TaskHarnessesKeepSeparateWorkspaceEnvironments() throws Exception {
        Path workspaceA = Files.createDirectory(temporary.resolve("task-a"));
        Path workspaceB = Files.createDirectory(temporary.resolve("task-b"));
        V12RuntimeHarness taskA = new V12RuntimeHarness(scripted(
                call("create_file", Map.of("path", "result.txt", "content", "from A")),
                new LLMResponse("created", List.of())), workspaceA);
        V12RuntimeHarness taskB = new V12RuntimeHarness(scripted(
                call("create_file", Map.of("path", "result.txt", "content", "from B")),
                new LLMResponse("created", List.of())), workspaceB);

        taskA.run(task("task-a", "在项目中创建 result.txt", Map.of("seed.txt", "a")));
        taskB.run(task("task-b", "在项目中创建 result.txt", Map.of("seed.txt", "b")));

        assertEquals("from A", Files.readString(workspaceA.resolve("result.txt")));
        assertEquals("from B", Files.readString(workspaceB.resolve("result.txt")));
    }

    private static LocalWorkspaceEnvironment environment(Path root, ToolRegistry registry) {
        return new LocalWorkspaceEnvironment(root, registry);
    }

    private static ToolResult execute(LocalWorkspaceEnvironment environment, String name,
                                      Map<String, Object> arguments) throws IOException {
        return environment.execute(new ToolCall(name + "-id", name,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments)));
    }

    private static LLMClient scripted(LLMResponse... responses) {
        Deque<LLMResponse> queue = new ArrayDeque<>(List.of(responses));
        return messages -> queue.removeFirst();
    }

    private static LLMResponse call(String name, Map<String, Object> arguments) throws IOException {
        return new LLMResponse("", List.of(new ToolCall(name + "-id", name,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments))));
    }

    private static V12Task task(String id, String instruction, Map<String, String> fixture) {
        return new V12Task(id, V12Split.DEV, "environment_isolation", fixture,
                List.of(instruction), List.of(CliMode.CODE), List.of("create file"),
                V12Evaluator.HIDDEN_FILE_CONTENT, 5, List.of("CREATE_FILE"), List.of("file created"));
    }
}
