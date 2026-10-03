package com.agent.benchmark.planning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PlanningBenchmarkProtocolTest {
    @TempDir Path temp;

    private Path manifest() {
        return Path.of("benchmark/planning-v1/manifest.json").toAbsolutePath();
    }

    @Test
    void manifestHasEightUniqueDevTasksAndIndependentFixtures() throws Exception {
        List<PlanningBenchmarkTask> tasks = new PlanningBenchmarkTaskLoader().load(manifest());
        assertEquals(8, tasks.size());
        assertEquals(8, tasks.stream().map(PlanningBenchmarkTask::id).collect(Collectors.toSet()).size());
        assertEquals(8, tasks.stream().map(PlanningBenchmarkTask::fixture).collect(Collectors.toSet()).size());
        Path fixtures = manifest().getParent();
        for (PlanningBenchmarkTask task : tasks) {
            assertTrue(Files.isDirectory(fixtures.resolve(task.fixture())), task.id());
            assertTrue(task.maxProviderRequests() > 0);
        }
    }

    @Test
    void eachConditionHasEqualProviderCapAndIndependentWorkspace() throws Exception {
        PlanningBenchmarkTask task = simpleTask();
        var harness = new PlanningRuntimeHarness();
        var pair = harness.runPair(task, () -> new ScriptedClient(List.of(finalAnswer("done"))),
                fixtureRoot(), temp.resolve("runs"), "pair-1", temp.resolve("m2"));
        assertEquals(task.maxProviderRequests(), pair.sharedRequestCap());
        assertTrue(pair.reactive().metrics().providerRequests() <= pair.sharedRequestCap());
        assertTrue(pair.planExecute().metrics().providerRequests() <= pair.sharedRequestCap());
        assertNotEquals(pair.reactive().workspace(), pair.planExecute().workspace());
        assertEquals("REACTIVE", pair.reactive().mode().name());
        assertEquals("PLAN_EXECUTE", pair.planExecute().mode().name());
    }

    @Test
    void taskResetRemovesPriorConditionStateAndRestoresFixture() throws Exception {
        PlanningBenchmarkTask task = simpleTask();
        PlanningFixtureWorkspace manager = new PlanningFixtureWorkspace();
        Path fixtures = fixtureRoot();
        Path runs = temp.resolve("runs");
        Path workspace = manager.reset(task, fixtures, runs, "reset", com.agent.agent.PlanningMode.REACTIVE);
        Files.writeString(workspace.resolve("leftover.tmp"), "stale");
        Path again = manager.reset(task, fixtures, runs, "reset", com.agent.agent.PlanningMode.REACTIVE);
        assertFalse(Files.exists(again.resolve("leftover.tmp")));
        assertEquals("before\n", Files.readString(again.resolve("note.txt")));
    }

    @Test
    void finalProseCannotPassMissingWorkspaceOutcome() throws Exception {
        PlanningBenchmarkTask task = new PlanningBenchmarkTask("evaluator", "protocol", "fixture",
                "create expected file", List.of("result.txt"), Map.of("result.txt", List.of("expected")),
                List.of(), List.of(), List.of(), List.of(), false, true, 4, 8);
        Path workspace = temp.resolve("workspace");
        Path fixture = temp.resolve("fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);
        AgentTrajectory trajectory = trajectory(true, List.of());
        PlanningBenchmarkMetrics metrics = PlanningBenchmarkMetrics.from(trajectory, 1, task.allowedMutationTargets());
        var result = new PlanningBenchmarkEvaluator().evaluate(task, workspace, fixture, trajectory, metrics);
        assertFalse(result.taskOutcomeSuccess());
        assertTrue(result.conversationalCompletion());
    }

    @Test
    void actualWorkspaceOutcomeIsSeparateFromConversationalCompletion() throws Exception {
        PlanningBenchmarkTask task = new PlanningBenchmarkTask("workspace", "protocol", "fixture",
                "inspect existing result", List.of(), Map.of("result.txt", List.of("expected")),
                List.of(), List.of(), List.of(), List.of(), false, false, 4, 8);
        Path workspace = temp.resolve("workspace");
        Path fixture = temp.resolve("fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);
        Files.writeString(workspace.resolve("result.txt"), "expected");
        AgentTrajectory trajectory = trajectory(false, List.of());
        PlanningBenchmarkMetrics metrics = PlanningBenchmarkMetrics.from(trajectory, 1, List.of());
        var result = new PlanningBenchmarkEvaluator().evaluate(task, workspace, fixture, trajectory, metrics);
        assertTrue(result.taskOutcomeSuccess());
        assertFalse(result.conversationalCompletion());
    }

    @Test
    void targetDriftIsMeasuredFromSuccessfulMutationPath() throws Exception {
        var task = new PlanningBenchmarkTask("drift", "protocol", "fixture", "create only allowed",
                List.of("allowed.txt"), Map.of(), List.of(), List.of(), List.of(), List.of(), false, false, 4, 8);
        var mutation = new AgentStep(1, AgentActionType.TOOL_CALL, "create_file", "x",
                "{}", Map.of("path", "other.txt"),
                com.agent.tool.ToolResult.success("created", Map.of("changed", true)), null, null, 0, 0);
        var metrics = PlanningBenchmarkMetrics.from(trajectory(false, List.of(mutation)), 1,
                task.allowedMutationTargets());
        assertEquals(1, metrics.targetDrift());
    }

    @Test
    void requiredMavenPassMustFollowLatestMutation() throws Exception {
        var task = new PlanningBenchmarkTask("maven-order", "protocol", "fixture", "edit then test",
                List.of("Calculator.java"), Map.of("Calculator.java", List.of("fixed")),
                List.of(), List.of(), List.of(), List.of(), true, true, 6, 10);
        Path workspace = temp.resolve("maven-workspace");
        Path fixture = temp.resolve("maven-fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);
        Files.writeString(workspace.resolve("Calculator.java"), "fixed");
        AgentStep mutation = new AgentStep(1, AgentActionType.TOOL_CALL, "apply_patch", "edit",
                "{}", Map.of("path", "Calculator.java"),
                com.agent.tool.ToolResult.success("changed", Map.of("changed", true)), null, null, 0, 0);
        AgentStep maven = new AgentStep(2, AgentActionType.TOOL_CALL, "run_maven_test", "test",
                "{}", Map.of(), com.agent.tool.ToolResult.success("BUILD SUCCESS"), null, null, 0, 0);
        AgentTrajectory verified = trajectory(true, List.of(mutation, maven));
        var passed = new PlanningBenchmarkEvaluator().evaluate(task, workspace, fixture, verified,
                PlanningBenchmarkMetrics.from(verified, 2, task.allowedMutationTargets()));
        assertTrue(passed.taskOutcomeSuccess());

        AgentStep mavenFirst = new AgentStep(1, AgentActionType.TOOL_CALL, "run_maven_test", "test",
                "{}", Map.of(), com.agent.tool.ToolResult.success("BUILD SUCCESS"), null, null, 0, 0);
        AgentStep mutationLater = new AgentStep(2, AgentActionType.TOOL_CALL, "apply_patch", "edit",
                "{}", Map.of("path", "Calculator.java"),
                com.agent.tool.ToolResult.success("changed", Map.of("changed", true)), null, null, 0, 0);
        AgentTrajectory staleVerification = trajectory(true, List.of(mavenFirst, mutationLater));
        var failed = new PlanningBenchmarkEvaluator().evaluate(task, workspace, fixture, staleVerification,
                PlanningBenchmarkMetrics.from(staleVerification, 2, task.allowedMutationTargets()));
        assertFalse(failed.taskOutcomeSuccess());
        assertTrue(failed.failures().stream().anyMatch(text -> text.contains("after latest mutation")));
    }

    private Path fixtureRoot() throws Exception {
        Path root = temp.resolve("fixtures");
        Files.createDirectories(root.resolve("fixture"));
        Files.writeString(root.resolve("fixture/note.txt"), "before\n");
        return root;
    }

    private PlanningBenchmarkTask simpleTask() {
        return new PlanningBenchmarkTask("simple", "simple", "fixture", "edit note", List.of("note.txt"),
                Map.of("note.txt", List.of("before")), List.of(), List.of(), List.of(), List.of(), false, false, 5, 10);
    }

    private static AgentTrajectory trajectory(boolean completed, List<AgentStep> steps) {
        return new AgentTrajectory("run", "task", steps, completed ? "Done" : null,
                completed ? TerminationReason.FINAL_ANSWER : TerminationReason.MAX_STEPS,
                completed, null, 0, 0);
    }

    private static com.agent.llm.LLMResponse finalAnswer(String text) {
        return new com.agent.llm.LLMResponse(text, List.of());
    }

    private static final class ScriptedClient implements com.agent.llm.LLMClient {
        private final java.util.ArrayDeque<com.agent.llm.LLMResponse> replies;
        private ScriptedClient(List<com.agent.llm.LLMResponse> replies) {
            this.replies = new java.util.ArrayDeque<>(replies);
        }
        @Override public com.agent.llm.LLMResponse chat(List<com.agent.llm.Message> messages)
                throws java.io.IOException { return chat(messages, List.of()); }
        @Override public com.agent.llm.LLMResponse chat(List<com.agent.llm.Message> messages,
                List<com.agent.llm.ToolDefinition> tools) throws java.io.IOException {
            if (replies.isEmpty()) throw new java.io.IOException("script exhausted");
            return replies.removeFirst();
        }
    }
}
