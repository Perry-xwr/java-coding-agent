package com.agent.benchmark.planning;

import com.agent.agent.PlanningMode;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningBenchmarkRunnerTest {
    @TempDir Path temp;

    @Test
    void writesRoundMetadataAndHonorsOppositeFixedExecutionOrders() throws Exception {
        Path fixtures = temp.resolve("fixtures");
        Files.createDirectories(fixtures.resolve("fixture"));
        Files.writeString(fixtures.resolve("fixture/note.txt"), "original\n");
        Path output = temp.resolve("benchmark-runs/planning-v1");
        PlanningBenchmarkTask task = new PlanningBenchmarkTask("runner-task", "test", "fixture",
                "read note", List.of(), Map.of("note.txt", List.of("original")),
                List.of("note.txt"), List.of("read_file"), List.of("note.txt"), List.of(),
                false, false, 6, 10);
        PlanningBenchmarkRunner runner = new PlanningBenchmarkRunner();
        List<PlanningBenchmarkRunner.PlanningRunRecord> round1 = runner.runRound(List.of(task), 1,
                PlanningBenchmarkRunner.ExecutionOrder.REACTIVE_FIRST, "runtime-sha", "benchmark-sha",
                "GLM", "glm-4-flash", fixtures, output, "experiment", temp.resolve("m2"),
                ignored -> pairedClient());
        List<PlanningBenchmarkRunner.PlanningRunRecord> round2 = runner.runRound(List.of(task), 2,
                PlanningBenchmarkRunner.ExecutionOrder.PLAN_FIRST, "runtime-sha", "benchmark-sha",
                "GLM", "glm-4-flash", fixtures, output, "experiment", temp.resolve("m2"),
                ignored -> pairedClient());

        assertEquals(List.of("REACTIVE", "PLAN_EXECUTE"), round1.stream()
                .map(PlanningBenchmarkRunner.PlanningRunRecord::mode).toList());
        assertEquals(List.of("PLAN_EXECUTE", "REACTIVE"), round2.stream()
                .map(PlanningBenchmarkRunner.PlanningRunRecord::mode).toList());
        assertTrue(round1.stream().allMatch(PlanningBenchmarkRunner.PlanningRunRecord::success));
        assertTrue(round2.stream().allMatch(PlanningBenchmarkRunner.PlanningRunRecord::success));
        for (var record : round1) {
            assertEquals(1, record.round());
            assertEquals("REACTIVE_FIRST", record.executionOrder());
            assertEquals("runtime-sha", record.runtimeCommit());
            assertEquals("benchmark-sha", record.benchmarkCommit());
            assertEquals(6, record.requestBudget());
            assertEquals(10, record.runtimeMaxIterations());
        }
        Path jsonl = output.resolve("experiment/round-1/task-results.jsonl");
        assertEquals(2, Files.readAllLines(jsonl).size());
        assertTrue(Files.exists(output.resolve("experiment/round-1/trajectories/REACTIVE/runner-task")));
        assertThrows(IOException.class, () -> runner.runRound(List.of(task), 1,
                PlanningBenchmarkRunner.ExecutionOrder.REACTIVE_FIRST, "runtime-sha", "benchmark-sha",
                "GLM", "glm-4-flash", fixtures, output, "experiment", temp.resolve("m2"),
                ignored -> pairedClient()));
    }

    @Test
    void providerFailuresAreRecordedSeparatelyAndDoNotStopTheOtherMode() throws Exception {
        Path fixtures = temp.resolve("failed-fixtures");
        Files.createDirectories(fixtures.resolve("fixture"));
        Files.writeString(fixtures.resolve("fixture/note.txt"), "original\n");
        PlanningBenchmarkTask task = new PlanningBenchmarkTask("provider-error", "test", "fixture",
                "read note", List.of(), Map.of(), List.of(), List.of("read_file"), List.of(), List.of(),
                false, false, 4, 8);
        LLMClient failing = new LLMClient() {
            @Override public LLMResponse chat(List<Message> messages) throws IOException {
                throw new IOException("provider unavailable");
            }
            @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools)
                    throws IOException { throw new IOException("provider unavailable"); }
        };
        var records = new PlanningBenchmarkRunner().runRound(List.of(task), 1,
                PlanningBenchmarkRunner.ExecutionOrder.REACTIVE_FIRST, "runtime-sha", "benchmark-sha",
                "GLM", "glm-4-flash", fixtures, temp.resolve("errors"), "experiment", temp.resolve("m2"),
                ignored -> failing);
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(record -> record.infrastructureError() != null));
        assertTrue(records.stream().noneMatch(PlanningBenchmarkRunner.PlanningRunRecord::success));
        assertFalse(records.stream().anyMatch(PlanningBenchmarkRunner.PlanningRunRecord::requestCapReached));
    }

    private static LLMClient pairedClient() {
        return new LLMClient() {
            private int reads;
            @Override public LLMResponse chat(List<Message> messages) throws IOException {
                return chat(messages, List.of());
            }
            @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools)
                    throws IOException {
                if (messages.stream().anyMatch(message -> message.content().contains("[PLANNING_PHASE]"))) {
                    return plan();
                }
                if (reads < 2) {
                    reads++;
                    return new LLMResponse("", List.of(new ToolCall("read", "read_file",
                            "{\"path\":\"note.txt\"}")));
                }
                return answer("The note contains original.");
            }
        };
    }

    private static LLMResponse plan() {
        return answer("{\"goal\":\"create result\",\"steps\":[{\"id\":\"S1\",\"description\":\"Create the file\"}]}");
    }

    private static LLMResponse answer(String text) { return new LLMResponse(text, List.of()); }
}
