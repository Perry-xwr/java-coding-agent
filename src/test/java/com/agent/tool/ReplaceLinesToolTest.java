package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReplaceLinesToolTest {
    @TempDir
    Path temporary;

    private Path workspace;
    private Path target;
    private ReplaceLinesTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace"));
        target = Files.writeString(
                workspace.resolve("Example.java"),
                "class Example {\n  boolean same(int a, int b) {\n    return a==b;\n  }\n}\n",
                StandardCharsets.UTF_8
        );
        tool = new ReplaceLinesTool(workspace);
    }

    @Test
    void replacesCurrentLineRangeWithoutReconstructingSurroundingFormatting() throws Exception {
        ToolResult result = tool.execute(arguments(
                "Example.java", 3, 3, "    return a==b;", "    return a != b;"
        ));

        assertTrue(result.success());
        assertTrue(Files.readString(target).contains("return a != b;"));
        assertEquals(0, result.metadata().get("lineDelta"));
        assertEquals(true, result.metadata().get("changed"));
        assertEquals(3, result.metadata().get("startLine"));
    }

    @Test
    void rejectsStaleExpectedTextWithoutChangingFile() throws Exception {
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments(
                "Example.java", 3, 3, "    return a == b;", "    return false;"
        ));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.STALE_EDIT_CONTEXT, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    @Test
    void rejectsInvalidAndOutOfBoundsRanges() throws Exception {
        ToolResult reversed = tool.execute(arguments("Example.java", 4, 2, "", "x"));
        ToolResult outside = tool.execute(arguments("Example.java", 20, 20, "", "x"));

        assertEquals(ToolErrorCode.INVALID_LINE_RANGE, reversed.errorCode());
        assertEquals(ToolErrorCode.INVALID_LINE_RANGE, outside.errorCode());
    }

    @Test
    void writeFailureLeavesOriginalFileUnchanged() throws Exception {
        String original = Files.readString(target);
        ReplaceLinesTool failing = new ReplaceLinesTool(
                new WorkspacePathResolver(workspace),
                objectMapper,
                (path, content) -> { throw new IOException("simulated replace failure"); }
        );

        ToolResult result = failing.execute(arguments(
                "Example.java", 3, 3, "    return a==b;", "    return false;"
        ));

        assertEquals(ToolErrorCode.WRITE_FAILED, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    @Test
    void preservesCrLfLineEndings() throws Exception {
        Files.writeString(target, "one\r\ntwo\r\nthree\r\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(arguments("Example.java", 2, 2, "two", "changed"));
        String updated = Files.readString(target, StandardCharsets.UTF_8);

        assertTrue(result.success());
        assertEquals("one\r\nchanged\r\nthree\r\n", updated);
        assertFalse(updated.replace("\r\n", "").contains("\n"));
    }

    @Test
    void rejectsNoOp() throws Exception {
        String original = Files.readString(target);

        ToolResult result = tool.execute(arguments(
                "Example.java", 3, 3, "    return a==b;", "    return a==b;"
        ));

        assertEquals(ToolErrorCode.NO_EFFECT_CHANGE, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    @Test
    void rejectsTraversal() throws Exception {
        ToolResult result = tool.execute(arguments(
                "../../outside.java", 1, 1, "old", "new"
        ));

        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.java"), "outside");
        Path link = workspace.resolve("link.java");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        ToolResult result = tool.execute(arguments("link.java", 1, 1, "outside", "changed"));

        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void rejectsNulContentAsBinary() throws Exception {
        Files.write(target, new byte[] {'a', 0, 'b'});

        ToolResult result = tool.execute(arguments("Example.java", 1, 1, "a", "b"));

        assertEquals(ToolErrorCode.BINARY_FILE, result.errorCode());
    }

    private String arguments(
            String path,
            int startLine,
            int endLine,
            String expectedText,
            String newText
    ) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "path", path,
                "startLine", startLine,
                "endLine", endLine,
                "expectedText", expectedText,
                "newText", newText
        ));
    }
}
