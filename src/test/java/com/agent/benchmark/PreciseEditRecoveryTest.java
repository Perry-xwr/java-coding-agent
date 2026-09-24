package com.agent.benchmark;

import com.agent.agent.Agent;
import com.agent.agent.AgentRunResult;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreciseEditRecoveryTest {
    @TempDir
    Path workspace;

    @Test
    void fallsBackFromExactPatchFailureToFreshLineEdit() throws Exception {
        Path source = Files.writeString(workspace.resolve("Example.java"), """
                class Example {
                  boolean same(int a, int b) {
                    return a==b;
                  }
                }
                """);
        Deque<LLMResponse> responses = new ArrayDeque<>(List.of(
                call("read1", "read_file",
                        "{\"path\":\"Example.java\",\"includeLineNumbers\":true}"),
                call("patch1", "apply_patch",
                        "{\"path\":\"Example.java\",\"oldText\":\"return a == b;\","
                                + "\"newText\":\"return a != b;\"}"),
                call("read2", "read_file",
                        "{\"path\":\"Example.java\",\"includeLineNumbers\":true}"),
                call("lines1", "replace_lines",
                        "{\"path\":\"Example.java\",\"startLine\":3,\"endLine\":3,"
                                + "\"expectedText\":\"    return a==b;\","
                                + "\"newText\":\"    return a != b;\"}"),
                call("read3", "read_file",
                        "{\"path\":\"Example.java\",\"includeLineNumbers\":true}"),
                call("test1", "run_maven_test", "{}"),
                new LLMResponse("Done", List.of())
        ));
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                return responses.removeFirst();
            }
        };
        ProcessRunner passing = (command, directory, timeout, maxOutput) ->
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
        ToolRegistry registry = ToolRegistry.withPreciseEditCodingTools(
                workspace, passing, workspace.resolve(".m2/repository")
        );
        AgentRunResult run = new Agent(
                client, registry, "precise", 7,
                TaskMode.CODE_MODIFICATION, true, false
        ).runWithTrajectory("Change the comparison");
        BenchmarkTask task = new BenchmarkTask(
                "precise", TaskCategory.BUG_FIX, TaskDifficulty.EASY, "task", "fixture",
                List.of("Example.java"), EvaluationType.COMBINED, 7, List.of(), null,
                BenchmarkSplit.DEV, List.of("apply_patch", "replace_lines", "run_maven_test")
        );
        BenchmarkRunRecord record = new BenchmarkRunRecord(
                task, BaselineType.REACT_PRECISE_EDIT, run,
                new EvaluationResult("precise", true, true, true, true, null, Map.of()),
                FailureCategory.NONE, false
        );
        BenchmarkMetrics metrics = new BenchmarkMetricsCalculator().calculate(List.of(record));

        assertEquals("Done", run.finalAnswer());
        assertTrue(Files.readString(source).contains("return a != b;"));
        assertEquals(1, metrics.exactPatchAttempts());
        assertEquals(1, metrics.exactPatchFailures());
        assertEquals(1, metrics.lineEditAttempts());
        assertEquals(1, metrics.lineEditSuccesses());
        assertEquals(1, metrics.patchFallbackCount());
    }

    private static LLMResponse call(String id, String name, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, name, arguments)));
    }
}
