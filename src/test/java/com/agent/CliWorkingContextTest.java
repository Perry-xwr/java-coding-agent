package com.agent;

import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliWorkingContextTest {
    @Test
    void uniqueDiscoveryBecomesOneResolvedCandidate() {
        CliWorkingContext memory = new CliWorkingContext();

        memory.observeToolResult("find_files", Map.of("pattern", "*.cpp"),
                ToolResult.success("{\"files\":[\"hello.cpp\"],\"truncated\":false}"));

        assertEquals(List.of("hello.cpp"), memory.discoveredFiles());
        assertEquals(List.of("hello.cpp"), memory.lastResolvedFiles());
        assertEquals("hello.cpp", memory.lastResolvedFile());
    }

    @Test
    void multipleDiscoveryKeepsCandidatesAmbiguous() {
        CliWorkingContext memory = new CliWorkingContext();

        memory.observeToolResult("find_files", Map.of("pattern", "*.cpp"),
                ToolResult.success("{\"files\":[\"a.cpp\",\"b.cpp\"],\"truncated\":false}"));

        assertEquals(List.of("a.cpp", "b.cpp"), memory.lastResolvedFiles());
        assertNull(memory.lastResolvedFile());
        assertTrue(memory.compactSnapshot().contains("Do not choose a candidate arbitrarily"));
    }

    @Test
    void successfulReadRecordsOnlyVerifiedFileFacts() {
        CliWorkingContext memory = new CliWorkingContext();

        memory.observeToolResult("read_file", Map.of("path", "README.md"), ToolResult.success("contents"));

        assertTrue(memory.verifiedFileFacts().contains(
                new CliWorkingContext.VerifiedFileFact(CliWorkingContext.VerifiedFileFactType.FILE_EXISTS, "README.md")));
        assertTrue(memory.verifiedFileFacts().contains(
                new CliWorkingContext.VerifiedFileFact(CliWorkingContext.VerifiedFileFactType.FILE_READ, "README.md")));
        assertEquals(List.of("README.md"), memory.discoveredFiles());
    }

    @Test
    void successfulMutationRecordsFactAndLastMutation() {
        CliWorkingContext memory = new CliWorkingContext();

        memory.observeToolResult("apply_patch", Map.of("path", "A.java"),
                ToolResult.success("patched", Map.of("path", "A.java", "changed", true)));

        assertTrue(memory.verifiedFileFacts().contains(
                new CliWorkingContext.VerifiedFileFact(CliWorkingContext.VerifiedFileFactType.FILE_MUTATED, "A.java")));
        assertEquals(new CliWorkingContext.LastMutation("A.java", "apply_patch"), memory.lastMutation());
    }

    @Test
    void typedFailuresAreBoundedAndKeepTheirErrorCode() {
        CliWorkingContext memory = new CliWorkingContext();
        for (int index = 0; index < CliWorkingContext.MAX_RECENT_FAILURES + 2; index++) {
            memory.observeToolResult("insert_before", Map.of("path", "File" + index + ".java"),
                    ToolResult.failure(ToolErrorCode.AMBIGUOUS_MATCH, "ambiguous"));
        }

        assertEquals(CliWorkingContext.MAX_RECENT_FAILURES, memory.recentToolFailures().size());
        assertEquals("File2.java", memory.recentToolFailures().get(0).path());
        assertEquals(ToolErrorCode.AMBIGUOUS_MATCH, memory.recentToolFailures().get(0).errorCode());
    }

    @Test
    void explicitUserTargetOverridesEarlierDiscoveredFile() {
        CliWorkingContext memory = new CliWorkingContext();
        memory.observeToolResult("find_files", Map.of("pattern", "*.cpp"),
                ToolResult.success("{\"files\":[\"hello.cpp\"],\"truncated\":false}"));

        memory.observeUserTask("修改 README.md");

        assertEquals(List.of("README.md"), memory.explicitTargetFiles());
        assertTrue(memory.compactSnapshot().contains("Explicit target: README.md"));
        assertFalse(memory.compactSnapshot().contains("Known files: hello.cpp"));
    }

    @Test
    void modelTextAloneCannotCreateVerifiedMutationFact() {
        CliWorkingContext memory = new CliWorkingContext();

        memory.observeUserTask("I modified A.java");

        assertFalse(memory.verifiedFileFacts().contains(
                new CliWorkingContext.VerifiedFileFact(CliWorkingContext.VerifiedFileFactType.FILE_MUTATED, "A.java")));
        assertNull(memory.lastMutation());
    }

    @Test
    void clearResetsTheEntireSessionScopedMemory() {
        CliWorkingContext memory = new CliWorkingContext();
        memory.observeUserTask("修改 A.java");
        memory.observeToolResult("create_file", Map.of("path", "A.java"),
                ToolResult.success("created", Map.of("path", "A.java")));
        memory.observeToolResult("apply_patch", Map.of("path", "A.java"),
                ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND, "missing"));

        memory.clear();

        assertNull(memory.activeTask());
        assertTrue(memory.explicitTargetFiles().isEmpty());
        assertTrue(memory.discoveredFiles().isEmpty());
        assertTrue(memory.verifiedFileFacts().isEmpty());
        assertTrue(memory.recentToolFailures().isEmpty());
        assertNull(memory.lastMutation());
    }
}
