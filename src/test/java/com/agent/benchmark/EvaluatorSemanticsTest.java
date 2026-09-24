package com.agent.benchmark;

import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorSemanticsTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsBehaviorallyCorrectAlternativeWhenContentIsNotStrict() throws Exception {
        EvaluationResult result = evaluateMultiTask(false, passingRunner());

        assertTrue(result.success());
        assertTrue(result.behavioralPassed());
        assertFalse(result.contentCheckPassed());
        assertFalse(result.strictContentRequired());
        assertTrue(result.finalSuccess());
    }

    @Test
    void rejectsMissingExplicitSourceRequirementWhenContentIsStrict() throws Exception {
        EvaluationResult result = evaluateMultiTask(true, passingRunner());

        assertFalse(result.success());
        assertTrue(result.behavioralPassed());
        assertFalse(result.contentCheckPassed());
        assertTrue(result.strictContentRequired());
    }

    @Test
    void rejectsPassingContentWhenBehavioralTestsFail() throws Exception {
        BenchmarkTask task = task("multi_001");
        FixtureWorkspaceManager manager = manager();
        Path workspace = manager.reset(task, "behavior-fails");
        Files.writeString(workspace.resolve(task.expectedFiles().get(0)), """
                package bench;
                public final class RangeSum {
                  public int sum(int first, int second) {
                    int start = Math.min(first, second);
                    int end = Math.max(first, second);
                    int total = 0;
                    for (int i = start; i <= end; i++) total += i;
                    return total;
                  }
                }
                """);
        EvaluationResult result = evaluator(manager, false, failingRunner())
                .evaluate(task, workspace, completedRun());

        assertFalse(result.success());
        assertFalse(result.behavioralPassed());
        assertTrue(result.contentCheckPassed());
    }

    @Test
    void reportsEvaluatorInfrastructureFailureSeparately() throws Exception {
        BenchmarkTask task = task("multi_001");
        FixtureWorkspaceManager manager = manager();
        Path workspace = manager.reset(task, "infra-error");
        Files.writeString(workspace.resolve(task.expectedFiles().get(0)), "changed");
        ProcessRunner broken = (command, directory, timeout, maxOutput) -> {
            throw new IOException("Maven unavailable");
        };

        assertThrows(IOException.class, () -> evaluator(manager, false, broken)
                .evaluate(task, workspace, completedRun()));
    }

    private EvaluationResult evaluateMultiTask(boolean strict, ProcessRunner runner) throws Exception {
        BenchmarkTask task = task("multi_001");
        FixtureWorkspaceManager manager = manager();
        Path workspace = manager.reset(task, strict ? "strict" : "behavioral");
        Files.writeString(workspace.resolve(task.expectedFiles().get(0)), """
                package bench;
                public final class RangeSum {
                  public int sum(int first, int second) {
                    int total = 0;
                    for (int i = Math.min(first, second); i <= Math.max(first, second); i++) total += i;
                    return total;
                  }
                }
                """);
        return evaluator(manager, strict, runner).evaluate(task, workspace, completedRun());
    }

    private DeterministicTaskEvaluator evaluator(
            FixtureWorkspaceManager manager,
            boolean strict,
            ProcessRunner runner
    ) {
        return new DeterministicTaskEvaluator(
                manager,
                root("benchmark", "hidden", "v0.1"),
                root(".m2", "repository"),
                runner,
                Map.of("multi_001", new EvaluationSpec(
                        "multi_001", List.of("Math.min", "Math.max", "<= end"), List.of(), strict
                ))
        );
    }

    private FixtureWorkspaceManager manager() {
        return new FixtureWorkspaceManager(
                root("benchmark", "fixtures", "v0.1"), temporary.resolve("runs")
        );
    }

    private static BenchmarkTask task(String id) throws Exception {
        return new BenchmarkTaskLoader()
                .load(root("benchmark", "tasks", "v0.1", "tasks.json"))
                .tasks().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static AgentRunResult completedRun() {
        return new AgentRunResult("done", new AgentTrajectory(
                "test", "task", List.of(), "done", TerminationReason.FINAL_ANSWER,
                true, null, 0, 0
        ));
    }

    private static ProcessRunner passingRunner() {
        return (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
    }

    private static ProcessRunner failingRunner() {
        return (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(1, false, "[ERROR] Tests failed", false, 1);
    }

    private static Path root(String first, String... more) {
        return Path.of(first, more).toAbsolutePath().normalize();
    }
}
