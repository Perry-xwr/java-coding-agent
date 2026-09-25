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

class InsertAfterToolTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path workspace;
    private Path target;
    private InsertAfterTool tool;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectory(tempDir.resolve("workspace"));
        target = Files.writeString(workspace.resolve("hello.cpp"), """
                #include <iostream>
                using namespace std;

                int main() {
                    return 0;
                }
                """);
        tool = new InsertAfterTool(workspace);
    }

    @Test
    void insertsAfterOneUniqueLiteralAnchorAndPreservesOtherText() throws Exception {
        String inserted = "\n\nint sum(int a, int b) {\n    return a + b;\n}";

        ToolResult result = tool.execute(arguments("hello.cpp", "using namespace std;", inserted));

        String expected = """
                #include <iostream>
                using namespace std;

                int sum(int a, int b) {
                    return a + b;
                }

                int main() {
                    return 0;
                }
                """;
        assertTrue(result.success());
        assertEquals(expected, Files.readString(target));
        assertEquals("hello.cpp", result.metadata().get("path"));
        assertEquals(true, result.metadata().get("changed"));
        assertEquals(1, result.metadata().get("matchCount"));
    }

    @Test
    void rejectsUnsafePathsAndInvalidArguments() throws Exception {
        assertError(arguments(target.toAbsolutePath().toString(), "anchor", "content"), ToolErrorCode.WORKSPACE_VIOLATION);
        assertError(arguments("../outside.cpp", "anchor", "content"), ToolErrorCode.WORKSPACE_VIOLATION);
        assertError(arguments("", "anchor", "content"), ToolErrorCode.INVALID_ARGUMENTS);
        assertError(arguments("hello.cpp", "", "content"), ToolErrorCode.INVALID_ARGUMENTS);
        assertError(arguments("hello.cpp", "   ", "content"), ToolErrorCode.INVALID_ARGUMENTS);
        assertError(arguments("hello.cpp", "anchor", ""), ToolErrorCode.INVALID_ARGUMENTS);
    }

    @Test
    void rejectsMissingAndDirectoryTargets() throws Exception {
        assertError(arguments("missing.cpp", "anchor", "content"), ToolErrorCode.FILE_NOT_FOUND);
        Files.createDirectory(workspace.resolve("folder"));
        assertError(arguments("folder", "anchor", "content"), ToolErrorCode.NOT_A_REGULAR_FILE);
    }

    @Test
    void rejectsSymlinkEscapeWithoutWritingWhenSupported() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("outside.cpp"), "anchor");
        try {
            Files.createSymbolicLink(workspace.resolve("link.cpp"), outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        assertError(arguments("link.cpp", "anchor", "content"), ToolErrorCode.WORKSPACE_VIOLATION);
        assertEquals("anchor", Files.readString(outsideFile));
    }

    @Test
    void reportsMissingOrAmbiguousAnchorWithoutChangingTheFile() throws Exception {
        String original = Files.readString(target);
        assertError(arguments("hello.cpp", "not present", "content"), ToolErrorCode.TEXT_NOT_FOUND);
        assertEquals(original, Files.readString(target));

        Files.writeString(target, "anchor\nanchor\n");
        ToolResult ambiguous = tool.execute(arguments("hello.cpp", "anchor", "content"));
        assertFalse(ambiguous.success());
        assertEquals(ToolErrorCode.AMBIGUOUS_MATCH, ambiguous.errorCode());
        assertEquals(2, ambiguous.metadata().get("matchCount"));
        assertEquals("anchor\nanchor\n", Files.readString(target));
    }

    @Test
    void writeFailureLeavesTheOriginalFileUntouched() throws Exception {
        String original = Files.readString(target);
        InsertAfterTool failingTool = new InsertAfterTool(
                new WorkspacePathResolver(workspace),
                objectMapper,
                (path, content) -> { throw new IOException("simulated write failure"); }
        );

        ToolResult result = failingTool.execute(arguments("hello.cpp", "using namespace std;", "\ncontent"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.WRITE_FAILED, result.errorCode());
        assertEquals(original, Files.readString(target));
    }

    private void assertError(String arguments, ToolErrorCode expected) {
        ToolResult result = tool.execute(arguments);
        assertFalse(result.success());
        assertEquals(expected, result.errorCode());
    }

    private String arguments(String path, String anchor, String content) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of("path", path, "anchor", anchor, "content", content));
    }
}
