package com.agent.benchmark;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkPipelineTest {
    @TempDir
    Path temporary;

    @Test
    void loadsFixedVersionedTwentyTaskSuite() throws Exception {
        BenchmarkSuite suite = loadSuite();
        Map<String, EvaluationSpec> specs = new EvaluationSpecLoader().load(root(
                "benchmark", "tasks", "v0.1", "evaluation-checks.json"
        ));

        assertEquals("v0.1", suite.benchmarkVersion());
        assertEquals(20, suite.tasks().size());
        assertEquals(6, suite.tasks().stream().filter(t -> t.split() == BenchmarkSplit.DEV).count());
        assertEquals(14, suite.tasks().stream().filter(t -> t.split() == BenchmarkSplit.TEST).count());
        assertEquals(5, count(suite, TaskCategory.BUG_FIX));
        assertEquals(4, count(suite, TaskCategory.LOGIC_FIX));
        assertEquals(4, count(suite, TaskCategory.TEST_FIX));
        assertEquals(3, count(suite, TaskCategory.SMALL_REFACTOR));
        assertEquals(4, count(suite, TaskCategory.MULTI_STEP_DEBUG));
        assertEquals(20, specs.size());
        assertEquals(7, specs.values().stream().filter(EvaluationSpec::strictContentCheck).count());
        assertTrue(suite.tasks().stream().allMatch(task -> specs.containsKey(task.id())));
    }

    @Test
    void fakeLlmRunsThreeTasksThroughResetAgentEvaluationMetricsAndPersistence() throws Exception {
        BenchmarkSuite suite = loadSuite();
        List<BenchmarkTask> tasks = List.of(
                task(suite, "bugfix_001"),
                task(suite, "bugfix_002"),
                task(suite, "bugfix_004")
        );
        Path fixtures = root("benchmark", "fixtures", "v0.1");
        FixtureWorkspaceManager manager = new FixtureWorkspaceManager(
                fixtures,
                temporary.resolve("runs")
        );
        Deque<LLMClient> clients = new ArrayDeque<>(List.of(
                patchClient("src/main/java/bench/Calculator.java", "a - b", "a + b"),
                patchClient("src/main/java/bench/AgePolicy.java", "age > 18", "age >= 18"),
                patchClient("src/main/java/bench/ArrayTotal.java", "i <= values.length", "i < values.length")
        ));
        Supplier<LLMClient> supplier = clients::removeFirst;
        ProcessRunner passingEvaluator = (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 5);
        DeterministicTaskEvaluator evaluator = new DeterministicTaskEvaluator(
                manager,
                root("benchmark", "hidden", "v0.1"),
                root(".m2", "repository"),
                passingEvaluator,
                new EvaluationSpecLoader().load(root(
                        "benchmark", "tasks", "v0.1", "evaluation-checks.json"
                ))
        );
        BenchmarkResultWriter writer = new BenchmarkResultWriter(temporary.resolve("results"));
        BenchmarkRunner runner = new BenchmarkRunner(
                manager,
                new DefaultBaselineExecutor(supplier, root(".m2", "repository")),
                evaluator,
                writer
        );
        ExperimentMetadata metadata = new ExperimentMetadata(
                "fake-smoke", "fake", "test", BaselineType.REACT,
                "v0.1", "deterministic", 10, "2026-09-24T00:00:00Z", null
        );

        BenchmarkExecutionResult result = runner.run(metadata, tasks);

        assertEquals(3, result.records().size());
        assertEquals(3, result.metrics().successfulTasks());
        assertEquals(1.0, result.metrics().taskSuccessRate());
        assertEquals(1.0, result.metrics().completionRate());
        assertTrue(result.records().stream().allMatch(r -> r.failureCategory() == FailureCategory.NONE));
        for (BenchmarkTask task : tasks) {
            Path taskResult = temporary.resolve("results/fake-smoke").resolve(task.id());
            assertTrue(Files.isRegularFile(taskResult.resolve("trajectory.json")));
            assertTrue(Files.isRegularFile(taskResult.resolve("evaluation.json")));
            JsonNode evaluation = new ObjectMapper().readTree(
                    taskResult.resolve("evaluation.json").toFile()
            );
            assertFalse(evaluation.has("run"));
            assertTrue(evaluation.path("evaluation").path("success").asBoolean());
        }
        assertTrue(Files.isRegularFile(temporary.resolve("results/fake-smoke/summary.json")));
        assertTrue(Files.isRegularFile(temporary.resolve("results/fake-smoke/summary.md")));
        assertTrue(Files.isRegularFile(temporary.resolve("results/fake-smoke/failure_analysis.md")));

        Path reset = manager.reset(tasks.get(0), "second-reset");
        assertTrue(Files.readString(reset.resolve("src/main/java/bench/Calculator.java"))
                .contains("a - b"));
        assertFalse(Files.exists(reset.resolve("src/test/java/bench/CalculatorHiddenTest.java")));
    }

    private static LLMClient patchClient(String path, String oldText, String newText) {
        Deque<LLMResponse> responses = new ArrayDeque<>(List.of(
                new LLMResponse("", List.of(new ToolCall(
                        "patch-1",
                        "apply_patch",
                        newArgument(path, oldText, newText)
                ))),
                new LLMResponse("Done.", List.of())
        ));
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                return responses.removeFirst();
            }
        };
    }

    private static String newArgument(String path, String oldText, String newText) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                    "path", path, "oldText", oldText, "newText", newText
            ));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BenchmarkSuite loadSuite() throws Exception {
        return new BenchmarkTaskLoader().load(root("benchmark", "tasks", "v0.1", "tasks.json"));
    }

    private static BenchmarkTask task(BenchmarkSuite suite, String id) {
        return suite.tasks().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static long count(BenchmarkSuite suite, TaskCategory category) {
        return suite.tasks().stream().filter(task -> task.category() == category).count();
    }

    private static Path root(String first, String... more) {
        return Path.of(first, more).toAbsolutePath().normalize();
    }
}
