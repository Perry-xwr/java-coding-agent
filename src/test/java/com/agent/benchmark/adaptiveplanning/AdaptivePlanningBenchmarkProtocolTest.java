package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.PlanningMode;
import com.agent.agent.TerminationReason;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePlanningBenchmarkProtocolTest {
    private static final Path MANIFEST = Path.of("benchmark/adaptive-planning-v1/manifest.json")
            .toAbsolutePath().normalize();

    @TempDir Path temp;

    @Test
    void devManifestHasNineUniqueTasksAndThreeClassesOfThreeWithFixtures() throws Exception {
        List<AdaptivePlanningTask> tasks = new AdaptivePlanningTaskLoader().load(MANIFEST);
        assertEquals(9, tasks.size());
        assertEquals(9, tasks.stream().map(AdaptivePlanningTask::id).collect(Collectors.toSet()).size());
        assertEquals(Map.of(AdaptivePlanningTaskClass.SIMPLE, 3,
                        AdaptivePlanningTaskClass.COMPLEX, 3,
                        AdaptivePlanningTaskClass.AMBIGUOUS, 3),
                tasks.stream().collect(Collectors.groupingBy(AdaptivePlanningTask::taskClass,
                        Collectors.summingInt(task -> 1))));
        for (AdaptivePlanningTask task : tasks) {
            assertTrue(Files.isDirectory(MANIFEST.getParent().resolve(task.fixture())), task.id());
            assertEquals(12, task.maxProviderRequests(), task.id());
            assertEquals(20, task.maxToolSteps(), task.id());
        }
    }

    @Test
    void workspaceResetIsolatesConditionsAndRestoresFixtureContents() throws Exception {
        AdaptivePlanningTask task = simpleTask();
        Path fixtures = MANIFEST.getParent();
        Path runs = temp.resolve("runs");
        AdaptivePlanningWorkspace workspaces = new AdaptivePlanningWorkspace();
        Path reactive = workspaces.reset(task, fixtures, runs, "isolation", PlanningMode.REACTIVE);
        Files.writeString(reactive.resolve("notes.txt"), "changed");
        Path planned = workspaces.reset(task, fixtures, runs, "isolation", PlanningMode.PLAN_EXECUTE);
        assertNotEquals(reactive, planned);
        assertEquals("Status: draft\n", normalizedLineEndings(Files.readString(planned.resolve("notes.txt"))));
        Path reset = workspaces.reset(task, fixtures, runs, "isolation", PlanningMode.REACTIVE);
        assertEquals("Status: draft\n", normalizedLineEndings(Files.readString(reset.resolve("notes.txt"))));
    }

    @Test
    void taskSuccessDoesNotDependOnAdaptiveRoutingMatchOrFinalProse() throws Exception {
        AdaptivePlanningTask task = new AdaptivePlanningTask("route-mismatch", AdaptivePlanningTaskClass.SIMPLE,
                "fixture", "edit", PlanningMode.REACTIVE, List.of(), Map.of("result.txt", List.of("done")),
                List.of(), List.of(), List.of(), false, false, 4, 10);
        Path workspace = temp.resolve("workspace");
        Path fixture = temp.resolve("fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);
        Files.writeString(workspace.resolve("result.txt"), "done");
        var metrics = new AdaptivePlanningMetrics(PlanningMode.PLAN_EXECUTE, "HIGH", List.of("ORDERED_ACTIONS"),
                false, 2, 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, false);
        AgentTrajectory trajectory = new AgentTrajectory("route-mismatch", "edit", List.of(),
                "claim unrelated success", TerminationReason.FINAL_ANSWER, true, null, 0, 0);

        var result = new AdaptivePlanningEvaluator().evaluate(task, workspace, fixture, trajectory, metrics);

        assertTrue(result.taskSuccess());
        assertFalse(metrics.routingMatch());
        assertFalse(result.conversationalCompletion());
    }

    @Test
    void evaluatorRejectsTargetDriftEvenWhenExpectedContentExists() throws Exception {
        AdaptivePlanningTask task = new AdaptivePlanningTask("drift", AdaptivePlanningTaskClass.SIMPLE,
                "fixture", "edit target", PlanningMode.REACTIVE, List.of("allowed.txt"), Map.of(),
                List.of(), List.of(), List.of(), false, false, 4, 10);
        AgentStep mutation = new AgentStep(1, AgentActionType.TOOL_CALL, "apply_patch", "edit",
                "{}", Map.of("path", "wrong.txt"), ToolResult.success("changed", Map.of("changed", true)),
                null, null, 0, 0);
        AgentTrajectory trajectory = new AgentTrajectory("drift", "edit", List.of(mutation), "done",
                TerminationReason.FINAL_ANSWER, true, null, 0, 0);
        var metrics = AdaptivePlanningMetrics.from(task, PlanningMode.REACTIVE, trajectory, 1);
        Path workspace = temp.resolve("drift-workspace");
        Path fixture = temp.resolve("drift-fixture");
        Files.createDirectories(workspace);
        Files.createDirectories(fixture);

        var result = new AdaptivePlanningEvaluator().evaluate(task, workspace, fixture, trajectory, metrics);

        assertFalse(result.taskSuccess());
        assertEquals(1, metrics.targetDrift());
        assertTrue(result.failures().contains("MUTATION_TARGET_DRIFT"));
    }

    @Test
    void adaptiveProtocolLivesBesideRatherThanInsideExistingProtocols() {
        assertTrue(Files.isRegularFile(MANIFEST));
        assertTrue(Files.isRegularFile(Path.of("benchmark/planning-v1/manifest.json")));
        assertTrue(Files.isRegularFile(Path.of("benchmark/memory-v1/manifest.json")));
        assertFalse(MANIFEST.startsWith(Path.of("benchmark/planning-v1").toAbsolutePath().normalize()));
        assertFalse(MANIFEST.startsWith(Path.of("benchmark/memory-v1").toAbsolutePath().normalize()));
    }

    @Test
    void runnerRequiresExplicitProviderFactoryAndWritesOneRecordPerCondition() throws Exception {
        AtomicInteger providerConstructions = new AtomicInteger();
        Path output = temp.resolve("runner-output");
        var records = new AdaptivePlanningBenchmarkRunner().runDev(
                new AdaptivePlanningTaskLoader().load(MANIFEST), "runtime-sha", "benchmark-sha",
                "scripted", "fake-model", MANIFEST.getParent(), output, "run-1", temp.resolve("m2"),
                task -> {
                    providerConstructions.incrementAndGet();
                    return messages -> { throw new java.io.IOException("scripted offline provider"); };
                });

        assertEquals(27, records.size());
        assertEquals(27, providerConstructions.get());
        assertTrue(Files.isRegularFile(output.resolve("run-1/run-summary.json")));
        assertEquals(27, Files.readAllLines(output.resolve("run-1/task-results.jsonl")).size());
    }

    @Test
    void frozenRoundsUseRequiredModeOrderAndCommitScopedOutput() throws Exception {
        var tasks = new AdaptivePlanningTaskLoader().load(MANIFEST);
        var runner = new AdaptivePlanningBenchmarkRunner();
        Path output = temp.resolve("rounds");
        var provider = (AdaptivePlanningBenchmarkRunner.ProviderFactory) task -> messages -> {
            throw new java.io.IOException("offline scripted provider");
        };
        var round1 = runner.runRound(tasks, 1, AdaptivePlanningBenchmarkRunner.ExecutionOrder.R_P_A,
                "runtime-sha", "benchmark-sha", "scripted", "fake", MANIFEST.getParent(),
                output, temp.resolve("m2"), provider);
        var round2 = runner.runRound(tasks, 2, AdaptivePlanningBenchmarkRunner.ExecutionOrder.A_P_R,
                "runtime-sha", "benchmark-sha", "scripted", "fake", MANIFEST.getParent(),
                output, temp.resolve("m2"), provider);

        assertEquals(List.of("REACTIVE", "PLAN_EXECUTE", "ADAPTIVE"), round1.subList(0, 3).stream()
                .map(AdaptivePlanningBenchmarkRunner.RunRecord::configuredMode).toList());
        assertEquals(List.of("ADAPTIVE", "PLAN_EXECUTE", "REACTIVE"), round2.subList(0, 3).stream()
                .map(AdaptivePlanningBenchmarkRunner.RunRecord::configuredMode).toList());
        assertTrue(Files.isRegularFile(output.resolve("benchmark-sha/round-1/run-summary.json")));
        assertTrue(Files.isRegularFile(output.resolve("benchmark-sha/round-2/task-results.jsonl")));
    }

    @Test
    void trajectoryWriterRedactsCredentialFieldsAndAuthorizationValues() throws Exception {
        AgentStep step = new AgentStep(1, AgentActionType.ERROR, null, null, null,
                Map.of("Authorization", "Bearer top-secret", "safe", "ok"), null,
                "Authorization: another-secret", "api_key=third-secret", 0, 0);
        AgentTrajectory trajectory = new AgentTrajectory("sanitized", "task", List.of(step),
                "done", TerminationReason.LLM_ERROR, false, null, 0, 0);
        Path written = new AdaptivePlanningTrajectoryWriter(temp.resolve("trajectories")).write(trajectory);
        String json = Files.readString(written);
        assertFalse(json.contains("top-secret"));
        assertFalse(json.contains("another-secret"));
        assertFalse(json.contains("third-secret"));
        assertTrue(json.contains("[REDACTED]"));
    }

    private static AdaptivePlanningTask simpleTask() throws Exception {
        return new AdaptivePlanningTaskLoader().load(MANIFEST).get(0);
    }

    private static String normalizedLineEndings(String content) {
        return content.replace("\r\n", "\n");
    }
}
