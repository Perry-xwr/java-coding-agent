package com.agent.benchmark;

import com.agent.agent.Agent;
import com.agent.agent.AgentRunResult;
import com.agent.agent.TerminationReason;
import com.agent.agent.TaskMode;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkFailureModesTest {
    @TempDir
    Path temporary;

    @Test
    void countsRuntimeCompletionFeedbackMetrics() {
        AgentRunResult run = new Agent(
                fake(List.of(answer("suggestion"), answer("final after warning"))),
                new ToolRegistry(),
                "test",
                3,
                TaskMode.CODE_MODIFICATION
        ).runWithTrajectory("Fix it");
        BenchmarkTask task = task("guard-metrics", List.of("apply_patch"));
        BenchmarkRunRecord record = new BenchmarkRunRecord(
                task,
                BaselineType.REACT_ACTION_ORIENTED,
                run,
                failedEvaluation(false),
                FailureCategory.PREMATURE_FINAL,
                false
        );

        BenchmarkMetrics metrics = new BenchmarkMetricsCalculator().calculate(List.of(record));

        assertEquals(1, metrics.prematureFinalAttempts());
        assertEquals(1, metrics.completionGuardActivations());
        assertEquals(0, metrics.validationGuardActivations());
        assertEquals(0, metrics.repeatedActionWarnings());
    }

    @Test
    void classifiesPatchFailure() throws Exception {
        Files.writeString(temporary.resolve("App.java"), "before");
        AgentRunResult run = new Agent(
                fake(List.of(
                        call("p", "apply_patch", "{\"path\":\"App.java\",\"oldText\":\"missing\",\"newText\":\"after\"}"),
                        answer("unable")
                )),
                ToolRegistry.withCodingTools(temporary, passingRunner()),
                "test",
                3
        ).runWithTrajectory("edit");
        EvaluationResult failed = failedEvaluation(false);

        assertEquals(FailureCategory.PATCH_FAILED, new FailureClassifier().classify(
                task("failure-patch", List.of("apply_patch")), run, failed, false
        ));
    }

    @Test
    void recordsTestFailureRecoveryInMetrics() {
        QueueRunner processes = new QueueRunner(List.of(
                new ProcessExecutionResult(1, false, "tests failed", false, 2),
                new ProcessExecutionResult(0, false, "tests passed", false, 2)
        ));
        AgentRunResult run = new Agent(
                fake(List.of(
                        call("t1", "run_maven_test", "{}"),
                        call("t2", "run_maven_test", "{}"),
                        answer("recovered")
                )),
                ToolRegistry.withCodingTools(temporary, processes),
                "test",
                4
        ).runWithTrajectory("recover");
        BenchmarkTask task = task("failure-recovery", List.of("run_maven_test"));
        BenchmarkRunRecord record = new BenchmarkRunRecord(
                task,
                BaselineType.REACT,
                run,
                new EvaluationResult(task.id(), true, true, true, true, null, Map.of()),
                FailureCategory.NONE,
                false
        );

        BenchmarkMetrics metrics = new BenchmarkMetricsCalculator().calculate(List.of(record));

        assertEquals(1.0, metrics.testFailureRecoveryRate());
        assertEquals(1, metrics.testFailureCount());
        assertEquals(1, metrics.testDiagnosticFailures());
        assertEquals(1, metrics.testFailureRecoverySuccesses());
        assertEquals(1, metrics.recoveryAttempts());
        assertEquals(0, metrics.noEffectPatchCount());
        assertEquals(0, metrics.rereadAfterTestFailureCount());
        assertEquals(0, metrics.budgetWarnings());
        assertEquals(2, metrics.toolUsage().get("run_maven_test").calls());
    }

    @Test
    void classifiesMaxSteps() throws Exception {
        Files.writeString(temporary.resolve("App.java"), "content");
        AgentRunResult run = new Agent(
                fake(List.of(
                        call("r1", "read_file", "{\"path\":\"App.java\"}"),
                        call("r2", "read_file", "{\"path\":\"App.java\"}")
                )),
                ToolRegistry.withCodingTools(temporary, passingRunner()),
                "test",
                2
        ).runWithTrajectory("loop");

        assertEquals(TerminationReason.MAX_STEPS, run.trajectory().terminationReason());
        assertEquals(FailureCategory.MAX_STEPS, new FailureClassifier().classify(
                task("failure-max", List.of()), run, failedEvaluation(false), false
        ));
    }

    @Test
    void runnerSeparatesEvaluatorErrorFromAgentFailure() throws Exception {
        BenchmarkSuite suite = new BenchmarkTaskLoader().load(root(
                "benchmark", "tasks", "v0.1", "tasks.json"
        ));
        BenchmarkTask task = suite.tasks().get(0);
        FixtureWorkspaceManager manager = new FixtureWorkspaceManager(
                root("benchmark", "fixtures", "v0.1"),
                temporary.resolve("runs")
        );
        BenchmarkAgentExecutor executor = (ignored, workspace, baseline) -> new Agent(
                fake(List.of(answer("done"))),
                new ToolRegistry(),
                "test",
                1
        ).runWithTrajectory("done");
        TaskEvaluator brokenEvaluator = (ignoredTask, workspace, run) -> {
            throw new IOException("evaluator unavailable");
        };
        BenchmarkRunner runner = new BenchmarkRunner(
                manager,
                executor,
                brokenEvaluator,
                new BenchmarkResultWriter(temporary.resolve("results"))
        );

        BenchmarkExecutionResult result = runner.run(new ExperimentMetadata(
                "evaluator-error", "fake", "test", BaselineType.REACT,
                "v0.1", "deterministic", 1, "2026-09-24T00:00:00Z", null
        ), List.of(task));

        assertTrue(result.records().get(0).evaluatorError());
        assertEquals(FailureCategory.EVALUATOR_ERROR, result.records().get(0).failureCategory());
        assertEquals(0, result.metrics().evaluableTasks());
        assertEquals(1, result.metrics().evaluatorErrors());
    }

    private static BenchmarkTask task(String id, List<String> requiredTools) {
        return new BenchmarkTask(
                id, TaskCategory.BUG_FIX, TaskDifficulty.EASY, "task", "fixture",
                List.of("App.java"), EvaluationType.COMBINED, 3, List.of(),
                null, BenchmarkSplit.DEV, requiredTools
        );
    }

    private static EvaluationResult failedEvaluation(boolean changed) {
        return new EvaluationResult("task", false, false, true, changed, "failed", Map.of());
    }

    private static LLMResponse call(String id, String tool, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, tool, arguments)));
    }

    private static LLMResponse answer(String content) {
        return new LLMResponse(content, List.of());
    }

    private static LLMClient fake(List<LLMResponse> values) {
        Deque<LLMResponse> responses = new ArrayDeque<>(values);
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                return responses.removeFirst();
            }
        };
    }

    private static ProcessRunner passingRunner() {
        return (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(0, false, "success", false, 1);
    }

    private static Path root(String first, String... more) {
        return Path.of(first, more).toAbsolutePath().normalize();
    }

    private static final class QueueRunner implements ProcessRunner {
        private final Deque<ProcessExecutionResult> values;
        private QueueRunner(List<ProcessExecutionResult> values) { this.values = new ArrayDeque<>(values); }
        @Override public ProcessExecutionResult run(
                List<String> command, Path workingDirectory, Duration timeout, int maxOutputBytes
        ) { return values.removeFirst(); }
    }
}
