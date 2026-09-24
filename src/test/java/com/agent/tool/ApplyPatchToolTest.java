package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApplyPatchToolTest {
    @TempDir
    Path tempDir;

    private Path workspace;
    private Path target;
    private ApplyPatchTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectory(tempDir.resolve("workspace"));
        target = Files.writeString(workspace.resolve("Example.java"), "return a - b;\n");
        tool = new ApplyPatchTool(workspace);
    }

    @Test
    void replacesOneUniqueOccurrence() throws IOException {
        ToolResult result = tool.execute(arguments("Example.java", "a - b", "a + b"));

        assertTrue(result.success());
        assertEquals("return a + b;\n", Files.readString(target));
        assertEquals(true, result.metadata().get("changed"));
        assertEquals(1, result.metadata().get("matchCount"));
        assertEquals("Example.java", result.metadata().get("path"));
    }

    @Test
    void reportsMissingOldTextWithoutChangingFile() throws IOException {
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments("Example.java", "not present", "replacement"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TEXT_NOT_FOUND, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    @Test
    void rejectsMultipleMatchesWithoutChangingFile() throws IOException {
        Files.writeString(target, "same same");
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments("Example.java", "same", "changed"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.MULTIPLE_MATCHES, result.errorCode());
        assertEquals(2, result.metadata().get("matchCount"));
        assertEquals(original, Files.readString(target));
    }

    @Test
    void rejectsTraversal() throws JsonProcessingException {
        ToolResult result = tool.execute(arguments("../outside.txt", "old", "new"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
    }

    @Test
    void rejectsAbsolutePath() throws JsonProcessingException {
        ToolResult result = tool.execute(arguments(target.toAbsolutePath().toString(), "old", "new"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
    }

    @Test
    void reportsMissingTarget() throws JsonProcessingException {
        ToolResult result = tool.execute(arguments("missing.java", "old", "new"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.FILE_NOT_FOUND, result.errorCode());
    }

    @Test
    void rejectsDirectoryTarget() throws IOException {
        Files.createDirectory(workspace.resolve("folder"));

        ToolResult result = tool.execute(arguments("folder", "old", "new"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.NOT_A_REGULAR_FILE, result.errorCode());
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("outside.txt"), "old");
        Path link = workspace.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        ToolResult result = tool.execute(arguments("link.txt", "old", "new"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
        assertEquals("old", Files.readString(outsideFile));
    }

    @Test
    void rejectsEmptyPathAndPreservesTarget() throws IOException {
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments("", "a - b", "a + b"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    @Test
    void reportsNoChangeWithoutRewritingContent() throws IOException {
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments("Example.java", "a - b", "a - b"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.NO_EFFECT_CHANGE, result.errorCode());
        assertEquals(false, result.metadata().get("changed"));
        assertEquals(original, Files.readString(target));
    }

    private String arguments(String path, String oldText, String newText)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "path", path,
                "oldText", oldText,
                "newText", newText
        ));
    }
}
