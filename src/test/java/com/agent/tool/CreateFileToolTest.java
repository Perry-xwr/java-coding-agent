package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class CreateFileToolTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsUtf8TextFile() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CreateFileTool tool = new CreateFileTool(workspace);

        ToolResult result = tool.execute(arguments("hello.py", "你好\nprint('hello')\n"));

        assertTrue(result.success());
        assertEquals("你好\nprint('hello')\n", Files.readString(workspace.resolve("hello.py")));
        assertEquals("hello.py", result.metadata().get("path"));
    }

    @Test
    void refusesExistingTargetWithoutOverwriting() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("hello.py"), "original");

        ToolResult result = new CreateFileTool(workspace).execute(arguments("hello.py", "replacement"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.FILE_ALREADY_EXISTS, result.errorCode());
        assertEquals("original", Files.readString(target));
    }

    @Test
    void rejectsAbsoluteAndTraversalPaths() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CreateFileTool tool = new CreateFileTool(workspace);

        ToolResult absolute = tool.execute(arguments(workspace.resolve("x.txt").toString(), "x"));
        ToolResult traversal = tool.execute(arguments("../outside.txt", "x"));

        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, absolute.errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, traversal.errorCode());
    }

    @Test
    void rejectsParentDirectoryThatDoesNotExist() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        ToolResult result = new CreateFileTool(workspace).execute(arguments("missing/hello.py", "x"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.PARENT_DIRECTORY_NOT_FOUND, result.errorCode());
    }

    @Test
    void rejectsNulAndBinaryLikeContent() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        ToolResult result = new CreateFileTool(workspace).execute(arguments("hello.py", "safe\0unsafe"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.BINARY_FILE, result.errorCode());
        assertFalse(Files.exists(workspace.resolve("hello.py")));
    }

    @Test
    void rejectsSymlinkParentEscapeWhenSupported() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        try {
            Files.createSymbolicLink(workspace.resolve("linked"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        ToolResult result = new CreateFileTool(workspace).execute(arguments("linked/escape.txt", "x"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
        assertFalse(Files.exists(outside.resolve("escape.txt")));
    }

    @Test
    void writeFailureLeavesNoTargetOrTemporaryFile() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CreateFileTool tool = new CreateFileTool(
                new WorkspacePathResolver(workspace),
                objectMapper,
                (target, content) -> { throw new IOException("simulated write failure"); }
        );

        ToolResult result = tool.execute(arguments("hello.py", "x"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WRITE_FAILED, result.errorCode());
        assertFalse(Files.exists(workspace.resolve("hello.py")));
        try (var children = Files.list(workspace)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".agent-create-")));
        }
    }

    private String arguments(String path, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of("path", path, "content", content));
    }
}
