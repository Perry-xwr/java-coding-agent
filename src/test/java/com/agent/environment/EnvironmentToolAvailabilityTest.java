package com.agent.environment;

import com.agent.agent.Agent;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.TaskMode;
import com.agent.environment.verification.VerificationStatus;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EnvironmentToolAvailabilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void standaloneWorkspaceHidesOnlyMavenAndKeepsCodingTools() {
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(new AtomicInteger()));

        List<String> available = environment.toolDefinitions().stream().map(ToolDefinition::name).toList();

        assertFalse(available.contains("run_maven_test"));
        assertTrue(available.containsAll(List.of("list_files", "find_files", "read_file", "search_code",
                "apply_patch", "insert_before", "insert_after", "create_file")));
        assertFalse(environment.toolAvailability("run_maven_test").available());
    }

    @Test
    void defaultUnknownEnvironmentPreservesExistingToolAvailability() {
        AgentEnvironment unknown = new AgentEnvironment() {
            @Override public ToolResult execute(ToolCall call) { return ToolResult.success("ok"); }
            @Override public List<ToolDefinition> toolDefinitions() { return List.of(); }
        };

        ToolAvailabilityDecision decision = unknown.toolAvailability("run_maven_test");

        assertTrue(decision.available());
        assertEquals("UNKNOWN", decision.capabilitySnapshot());
    }

    @Test
    void mavenWorkspaceAdvertisesMavenTool() throws IOException {
        Files.writeString(temporary.resolve("pom.xml"), "<project/>\n");
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(new AtomicInteger()));

        assertTrue(environment.toolDefinitions().stream().anyMatch(d -> d.name().equals("run_maven_test")));
        assertEquals("MAVEN", environment.toolAvailability("run_maven_test").capabilitySnapshot());
    }

    @Test
    void availabilityIsRecomputedAfterRootPomIsCreatedDuringSameAgentRun() throws Exception {
        AtomicInteger mavenExecutions = new AtomicInteger();
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(mavenExecutions));
        ScriptedClient client = new ScriptedClient(
                call("create_file", Map.of("path", "pom.xml", "content", "<project/>\n")),
                call("run_maven_test", Map.of()),
                new LLMResponse("Maven verification completed.", List.of()));
        Agent agent = agent(client, environment);

        AgentRunResult result = agent.runWithTrajectory("Create a minimal pom.xml and verify the project.");

        assertTrue(result.trajectory().completed());
        assertEquals(3, client.advertisedTools.size());
        assertFalse(hasTool(client.advertisedTools.get(0), "run_maven_test"));
        assertTrue(hasTool(client.advertisedTools.get(1), "run_maven_test"));
        assertEquals(1, mavenExecutions.get());
        assertTrue(Files.isRegularFile(temporary.resolve("pom.xml")));
    }

    @Test
    void forgedUnavailableMavenCallIsRejectedWithoutRunningProcess() throws Exception {
        AtomicInteger mavenExecutions = new AtomicInteger();
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(mavenExecutions));
        ScriptedClient client = new ScriptedClient(
                call("run_maven_test", Map.of()),
                new LLMResponse("This standalone workspace has no Maven project.", List.of()));

        AgentRunResult result = new Agent(client, environment, "deterministic test", 4, TaskMode.READ_ONLY,
                false, false, AgentEventListener.NO_OP, false)
                .runWithTrajectory("Check the workspace.");

        var rejected = result.trajectory().steps().stream()
                .filter(step -> step.toolResult() != null
                        && step.toolResult().errorCode() == ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT)
                .findFirst().orElseThrow();
        assertEquals("UNAVAILABLE_IN_ENVIRONMENT", rejected.toolResult().metadata().get("availability"));
        assertEquals("run_maven_test", rejected.toolResult().metadata().get("toolName"));
        assertTrue(rejected.toolResult().errorMessage().contains("no safe root pom.xml"));
        assertEquals(0, mavenExecutions.get());
        assertFalse(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && step.arguments().get("status") == VerificationStatus.FAIL));
    }

    @Test
    void directLocalEnvironmentInvocationAlsoRejectsUnavailableMaven() {
        AtomicInteger mavenExecutions = new AtomicInteger();
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(mavenExecutions));

        ToolResult result = environment.execute(new ToolCall("direct", "run_maven_test", "{}"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT, result.errorCode());
        assertEquals(0, mavenExecutions.get());
    }

    @Test
    void escapingPomSymlinkDoesNotPromoteMavenCapability() throws IOException {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outsidePom = temporary.resolve("outside-pom.xml");
        Files.writeString(outsidePom, "<project/>\n");
        try {
            Files.createSymbolicLink(workspace.resolve("pom.xml"), outsidePom);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        LocalWorkspaceEnvironment environment = environment(workspace, recordingRunner(new AtomicInteger()));

        assertFalse(environment.toolAvailability("run_maven_test").available());
        assertFalse(environment.toolDefinitions().stream().anyMatch(d -> d.name().equals("run_maven_test")));
    }

    @Test
    void standaloneJavaEditStillUsesJavacAndNeverAdvertisesMaven() throws Exception {
        Files.writeString(temporary.resolve("Main.java"), "class Main { int value() { return 1; } }\n");
        ToolRegistry registry = ToolRegistry.withCliCodingTools(temporary, new DefaultProcessRunner(),
                temporary.resolve(".m2/repository"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(temporary, registry);
        ScriptedClient client = new ScriptedClient(
                call("read_file", Map.of("path", "Main.java")),
                call("apply_patch", Map.of("path", "Main.java", "oldText", "return 1;", "newText", "return 2;")),
                new LLMResponse("Updated and syntax checked.", List.of()));

        AgentRunResult result = agent(client, environment).runWithTrajectory("Change Main.value to return 2.");

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().name());
        assertTrue(Files.readString(temporary.resolve("Main.java")).contains("return 2;"));
        assertTrue(client.advertisedTools.stream().allMatch(tools -> !hasTool(tools, "run_maven_test")));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "PASS".equals(step.arguments().get("status"))
                        && "java-javac".equals(step.arguments().get("verifier"))));
        assertFalse(result.trajectory().steps().stream().anyMatch(step ->
                "run_maven_test".equals(step.toolName())));
    }

    @Test
    void rootPomToolInvocationStillExecutesNormally() throws Exception {
        Files.writeString(temporary.resolve("pom.xml"), "<project/>\n");
        AtomicInteger mavenExecutions = new AtomicInteger();
        LocalWorkspaceEnvironment environment = environment(temporary, recordingRunner(mavenExecutions));
        ScriptedClient client = new ScriptedClient(
                call("run_maven_test", Map.of()),
                new LLMResponse("Tests passed.", List.of()));

        AgentRunResult result = new Agent(client, environment, "deterministic test", 4, TaskMode.READ_ONLY,
                false, false, AgentEventListener.NO_OP, false)
                .runWithTrajectory("Run the Maven tests.");

        assertTrue(result.trajectory().completed());
        assertTrue(hasTool(client.advertisedTools.get(0), "run_maven_test"));
        assertEquals(1, mavenExecutions.get());
        assertTrue(result.trajectory().steps().stream().anyMatch(step -> step.toolResult() != null
                && step.toolResult().success() && "BUILD SUCCESS".equals(step.toolResult().output())));
    }

    private static Agent agent(LLMClient client, LocalWorkspaceEnvironment environment) {
        return new Agent(client, environment, "deterministic test", 8, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);
    }

    private LocalWorkspaceEnvironment environment(Path root, ProcessRunner runner) {
        ToolRegistry registry = ToolRegistry.withCliCodingTools(root, runner, root.resolve(".m2/repository"));
        return new LocalWorkspaceEnvironment(root, registry);
    }

    private static ProcessRunner recordingRunner(AtomicInteger count) {
        return (command, cwd, timeout, limit) -> {
            count.incrementAndGet();
            return new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
        };
    }

    private static boolean hasTool(List<ToolDefinition> definitions, String name) {
        return definitions.stream().anyMatch(definition -> definition.name().equals(name));
    }

    private static LLMResponse call(String name, Map<String, Object> arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall("call-" + name,
                name, JSON.writeValueAsString(arguments))));
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses = new ArrayDeque<>();
        private final List<List<ToolDefinition>> advertisedTools = new ArrayList<>();

        private ScriptedClient(LLMResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LLMResponse chat(List<Message> messages) throws IOException {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            advertisedTools.add(List.copyOf(tools));
            if (responses.isEmpty()) {
                throw new AssertionError("Scripted LLM has no response remaining");
            }
            return responses.removeFirst();
        }
    }
}
