package com.agent.benchmark.memory;

import com.agent.CliWorkingContext;
import com.agent.WorkingMemoryMode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBenchmarkProtocolTest {
    private static final Path ROOT = Path.of("benchmark/memory-v1");

    @Test
    void manifestDefinesEightDistinctDevTasksWithValidFixturesAndBudgets() throws Exception {
        List<MemoryBenchmarkTask> tasks = new MemoryBenchmarkTaskLoader().load(ROOT.resolve("manifest.json"));

        assertEquals(8, tasks.size());
        assertEquals(8, tasks.stream().map(MemoryBenchmarkTask::id).distinct().count());
        for (MemoryBenchmarkTask task : tasks) {
            assertTrue(Files.isDirectory(ROOT.resolve("fixtures").resolve(task.fixture())));
            assertEquals(task.turns().size(), task.expectedRoutes().size());
            assertTrue(task.maxProviderRequests() >= task.turns().size());
            assertTrue(task.maxToolSteps() >= 0);
        }
    }

    @Test
    void legacyConditionRetainsOnlyTheOriginalCandidateHandoff() {
        CliWorkingContext memory = new CliWorkingContext(WorkingMemoryMode.LEGACY_CONTEXT);
        memory.observeUserTask("找所有 cpp 文件");
        memory.observeToolResult("find_files", Map.of("pattern", "*.cpp"),
                ToolResult.success("{\"files\":[\"hello.cpp\"],\"truncated\":false}"));

        assertEquals("hello.cpp", memory.lastResolvedFile());
        assertTrue(memory.compactSnapshot().contains("Recent workspace context:"));
        assertFalse(memory.compactSnapshot().contains("Working memory:"));
        assertTrue(memory.discoveredFiles().isEmpty());
        assertTrue(memory.verifiedFileFacts().isEmpty());
        assertTrue(memory.recentToolFailures().isEmpty());
    }

    @Test
    void structuredConditionExposesBoundedTypedSnapshot() {
        CliWorkingContext memory = new CliWorkingContext(WorkingMemoryMode.STRUCTURED_MEMORY);
        memory.observeUserTask("读取 hello.cpp");
        memory.observeToolResult("read_file", Map.of("path", "hello.cpp"), ToolResult.success("source"));

        assertTrue(memory.compactSnapshot().contains("Working memory:"));
        assertTrue(memory.compactSnapshot().contains("Explicit target: hello.cpp"));
        assertTrue(memory.compactSnapshot().contains("FILE_READ(hello.cpp)"));
    }
}
