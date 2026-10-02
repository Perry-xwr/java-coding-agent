package com.agent.benchmark.memory;

import com.agent.CliMode;
import com.agent.RoutingConfidence;
import com.agent.RoutingDecision;
import com.agent.RoutingReason;
import com.agent.WorkingMemoryMode;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRuntimeHarnessTest {
    private static final Path ROOT = Path.of("benchmark/memory-v1");
    @TempDir Path temporary;

    @Test
    void sameMultiTurnTaskDiffersOnlyInMemorySnapshot() throws Exception {
        MemoryBenchmarkTask task = task("memory_dev_01_unique_read");
        Path workspace = workspace(task, "snapshot");
        CapturingScriptedClient structuredClient = uniqueReadClient();
        List<MemoryRuntimeHarness.TurnResult> structured = new MemoryRuntimeHarness(
                structuredClient, workspace, WorkingMemoryMode.STRUCTURED_MEMORY).run(task);

        Path legacyWorkspace = workspace(task, "legacy");
        CapturingScriptedClient legacyClient = uniqueReadClient();
        List<MemoryRuntimeHarness.TurnResult> legacy = new MemoryRuntimeHarness(
                legacyClient, legacyWorkspace, WorkingMemoryMode.LEGACY_CONTEXT).run(task);

        assertEquals(structured.stream().map(turn -> turn.routing().mode()).toList(),
                legacy.stream().map(turn -> turn.routing().mode()).toList());
        assertTrue(structured.get(1).effectivePrompt().contains("Working memory:"));
        assertTrue(structured.get(1).effectivePrompt().contains("Current candidates: hello.cpp"));
        assertFalse(legacy.get(1).effectivePrompt().contains("Working memory:"));
        assertTrue(legacy.get(1).effectivePrompt().contains("Recent workspace context:"));
    }

    @Test
    void explicitTargetOverridesEarlierCandidateInStructuredPrompt() throws Exception {
        MemoryBenchmarkTask task = task("memory_dev_03_explicit_override");
        CapturingScriptedClient client = new CapturingScriptedClient(List.of(
                call("find_files", Map.of("pattern", "*.cpp")), answer("found"),
                call("read_file", Map.of("path", "README.md")), answer("read")
        ));

        List<MemoryRuntimeHarness.TurnResult> turns = new MemoryRuntimeHarness(
                client, workspace(task, "override"), WorkingMemoryMode.STRUCTURED_MEMORY).run(task);

        String prompt = turns.get(1).effectivePrompt();
        assertTrue(prompt.contains("Explicit target: README.md"));
        assertFalse(prompt.contains("Current candidates: hello.cpp"));
        assertEquals(CliMode.READ, turns.get(1).routing().mode());
    }

    @Test
    void metricsCountDriftRepeatedFailureAndRedundantReadDeterministically() {
        List<AgentStep> steps = List.of(
                step(1, "read_file", Map.of("path", "A.java"), ToolResult.success("a")),
                step(2, "read_file", Map.of("path", "A.java"), ToolResult.success("a")),
                step(3, "apply_patch", Map.of("path", "A.java", "oldText", "x"),
                        ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND, "missing")),
                step(4, "apply_patch", Map.of("path", "A.java", "oldText", "x"),
                        ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND, "missing")),
                step(5, "apply_patch", Map.of("path", "B.java"),
                        ToolResult.success("changed", Map.of("path", "B.java", "changed", true)))
        );
        MemoryBenchmarkMetrics metrics = MemoryBenchmarkMetrics.from(
                List.of(turn(steps)), List.of("A.java"), 7);

        assertEquals(7, metrics.providerRequests());
        assertEquals(5, metrics.toolSteps());
        assertEquals(1, metrics.mutationCount());
        assertEquals(1, metrics.targetDriftCount());
        assertEquals(1, metrics.repeatedFailureCount());
        assertEquals(1, metrics.redundantReadCount());
    }

    @Test
    void evaluatorIgnoresAFalseFinalAnswerAndUsesWorkspaceState() throws Exception {
        Path workspace = temporary.resolve("lying");
        Path fixture = temporary.resolve("lying-fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);
        Files.writeString(workspace.resolve("A.txt"), "old\n");
        Files.writeString(fixture.resolve("A.txt"), "old\n");
        MemoryBenchmarkTask task = new MemoryBenchmarkTask(
                "lying", "evaluation", "unused", List.of("change A.txt"), List.of(CliMode.CODE),
                List.of("A.txt"), Map.of("A.txt", "new\n"), List.of(), List.of(), List.of(),
                true, false, 2, 1);
        MemoryRuntimeHarness.TurnResult turn = new MemoryRuntimeHarness.TurnResult(
                1, "change A.txt", "change A.txt",
                new RoutingDecision(CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                trajectory(List.of(), "I changed A.txt successfully"));
        MemoryBenchmarkMetrics metrics = MemoryBenchmarkMetrics.from(List.of(turn), List.of("A.txt"), 1);

        MemoryBenchmarkEvaluator.Evaluation result = new MemoryBenchmarkEvaluator().evaluate(
                task, workspace, fixture, List.of(turn), metrics);

        assertFalse(result.passed());
        assertTrue(result.failures().contains("unexpected content A.txt"));
    }

    @Test
    void multipleCandidatesDoNotRequireOrPermitAnArbitraryMutation() throws Exception {
        MemoryBenchmarkTask task = task("memory_dev_04_ambiguity_safe");
        Path workspace = workspace(task, "ambiguous");
        MemoryRuntimeHarness.TurnResult first = new MemoryRuntimeHarness.TurnResult(
                1, task.turns().get(0), task.turns().get(0),
                decision(CliMode.READ), trajectory(List.of(
                step(1, "find_files", Map.of("pattern", "*.cpp"),
                        ToolResult.success("{\"files\":[\"a.cpp\",\"b.cpp\"]}"))), "found"));
        MemoryRuntimeHarness.TurnResult second = new MemoryRuntimeHarness.TurnResult(
                2, task.turns().get(1), task.turns().get(1),
                decision(CliMode.CODE), trajectory(List.of(), "Please clarify the file"));
        List<MemoryRuntimeHarness.TurnResult> turns = List.of(first, second);
        MemoryBenchmarkMetrics metrics = MemoryBenchmarkMetrics.from(turns, task.allowedMutationTargets(), 2);

        MemoryBenchmarkEvaluator.Evaluation result = new MemoryBenchmarkEvaluator().evaluate(
                task, workspace, ROOT.resolve("fixtures").resolve(task.fixture()), turns, metrics);

        assertTrue(result.passed(), result.failures().toString());
        assertEquals(0, metrics.mutationCount());
    }

    @Test
    void runnerCreatesFreshMemoryAndWorkspaceForEveryTask() throws Exception {
        MemoryBenchmarkTask discovery = task("memory_dev_01_unique_read");
        MemoryBenchmarkTask independent = new MemoryBenchmarkTask(
                "independent", "isolation", "note", List.of("读取这个文件"), List.of(CliMode.READ),
                List.of(), Map.of(), List.of("note.txt"), List.of(), List.of(), true,
                false, 2, 0);
        List<String> independentPrompts = new ArrayList<>();
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(ROOT.resolve("fixtures"));

        List<MemoryBenchmarkRunner.Result> results = runner.run(
                "isolation", List.of(discovery, independent), WorkingMemoryMode.STRUCTURED_MEMORY,
                temporary.resolve("runs"), (task, mode) -> {
                    if (task.id().equals(discovery.id())) {
                        return uniqueReadClient();
                    }
                    return messages -> {
                        independentPrompts.add(lastUser(messages));
                        return answer("cannot resolve without prior context");
                    };
                });

        assertEquals(2, results.size());
        assertEquals(List.of("读取这个文件"), independentPrompts);
        assertFalse(independentPrompts.get(0).contains("hello.cpp"));
    }

    private MemoryBenchmarkTask task(String id) throws Exception {
        return new MemoryBenchmarkTaskLoader().load(ROOT.resolve("manifest.json")).stream()
                .filter(task -> task.id().equals(id)).findFirst().orElseThrow();
    }

    private Path workspace(MemoryBenchmarkTask task, String runId) throws Exception {
        return new MemoryFixtureWorkspace(ROOT.resolve("fixtures"))
                .reset(task, temporary.resolve("workspaces"), runId, "test");
    }

    private static CapturingScriptedClient uniqueReadClient() {
        try {
            return new CapturingScriptedClient(List.of(
                    call("find_files", Map.of("pattern", "*.cpp")), answer("found"),
                    call("read_file", Map.of("path", "hello.cpp")), answer("summary")
            ));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LLMResponse call(String tool, Map<String, Object> arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(tool + "-id", tool,
                new ObjectMapper().writeValueAsString(arguments))));
    }

    private static LLMResponse answer(String value) {
        return new LLMResponse(value, List.of());
    }

    private static MemoryRuntimeHarness.TurnResult turn(List<AgentStep> steps) {
        return new MemoryRuntimeHarness.TurnResult(1, "task", "task", decision(CliMode.CODE),
                trajectory(steps, "done"));
    }

    private static RoutingDecision decision(CliMode mode) {
        return new RoutingDecision(mode, RoutingConfidence.HIGH,
                mode == CliMode.CODE ? RoutingReason.EXPLICIT_MUTATION_TARGET : RoutingReason.WORKSPACE_READ_REQUEST);
    }

    private static AgentStep step(int index, String tool, Map<String, Object> arguments, ToolResult result) {
        return new AgentStep(index, AgentActionType.TOOL_CALL, tool, "id", "{}", arguments,
                result, null, null, 0, 0);
    }

    private static AgentTrajectory trajectory(List<AgentStep> steps, String answer) {
        return new AgentTrajectory("run-" + System.nanoTime(), "task", steps, answer,
                TerminationReason.FINAL_ANSWER, true, null, 0, 1);
    }

    private static String lastUser(List<Message> messages) {
        return messages.stream().filter(message -> message.role().equals("user"))
                .reduce((first, second) -> second).orElseThrow().content();
    }

    private static final class CapturingScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<String> userPrompts = new ArrayList<>();

        private CapturingScriptedClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            userPrompts.add(lastUser(messages));
            return responses.removeFirst();
        }
    }
}
