package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FindFilesToolTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path workspace;
    private FindFilesTool tool;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectory(tempDir.resolve("workspace"));
        write("root.py");
        write("scripts/train.py");
        write("src/tools/helper.py");
        write("Agent.java");
        write("src/main/App.java");
        write("src/main/java/com/agent/Agent.java");
        write("src/test/java/com/agent/AgentTest.java");
        write("src/test/AppTest.java");
        write("README.md");
        tool = new FindFilesTool(workspace);
    }

    @Test
    void findsSimpleFilenamePatternsRecursivelyAndSortsPaths() throws Exception {
        assertEquals(List.of("root.py", "scripts\\train.py", "src\\tools\\helper.py"), files("*.py"));
        assertEquals(List.of(
                "Agent.java",
                "src\\main\\java\\com\\agent\\Agent.java",
                "src\\test\\java\\com\\agent\\AgentTest.java"
        ), files("*Agent*.java"));
        assertEquals(List.of("README.md"), files("README*"));
    }

    @Test
    void respectsDirectoryQualifiedPatterns() throws Exception {
        assertEquals(List.of(
                "src\\main\\App.java",
                "src\\main\\java\\com\\agent\\Agent.java",
                "src\\test\\AppTest.java",
                "src\\test\\java\\com\\agent\\AgentTest.java"
        ), files("src/**/*.java"));
    }

    @Test
    void excludesDefaultNoiseDirectories() throws Exception {
        write(".git/ignored.py");
        write("target/generated.py");
        write(".m2/cache.py");
        write(".gradle/cache.py");
        write("node_modules/package.py");

        assertEquals(List.of("root.py", "scripts\\train.py", "src\\tools\\helper.py"), files("*.py"));
    }

    @Test
    void rejectsInvalidAndUnsafePatterns() throws Exception {
        assertError("", ToolErrorCode.INVALID_ARGUMENTS);
        assertError(workspace.resolve("absolute.py").toString(), ToolErrorCode.WORKSPACE_VIOLATION);
        assertError("../*.py", ToolErrorCode.WORKSPACE_VIOLATION);
        assertError("[", ToolErrorCode.INVALID_PATTERN);
    }

    @Test
    void limitsResultsAndMarksTheResponseAsTruncated() throws Exception {
        for (int index = 0; index < 101; index++) {
            write("many/file-%03d.py".formatted(index));
        }

        ToolResult result = tool.execute(arguments("*.py"));
        JsonNode output = objectMapper.readTree(result.output());

        assertTrue(result.success());
        assertEquals(100, output.path("files").size());
        assertTrue(output.path("truncated").asBoolean());
        assertEquals(104, output.path("totalMatches").asInt());
        assertTrue((Boolean) result.metadata().get("truncated"));
    }

    @Test
    void doesNotReturnSymlinkEscapingTheWorkspaceWhenSupported() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.py"), "secret");
        try {
            Files.createSymbolicLink(workspace.resolve("outside-link.py"), secret);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        List<String> found = files("*.py");

        assertFalse(found.contains("outside-link.py"));
    }

    private List<String> files(String pattern) throws Exception {
        ToolResult result = tool.execute(arguments(pattern));
        assertTrue(result.success(), result.errorMessage());
        List<String> values = new ArrayList<>();
        objectMapper.readTree(result.output()).path("files").forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }

    private void assertError(String pattern, ToolErrorCode errorCode) throws Exception {
        ToolResult result = tool.execute(arguments(pattern));
        assertFalse(result.success());
        assertEquals(errorCode, result.errorCode());
    }

    private String arguments(String pattern) throws Exception {
        return objectMapper.writeValueAsString(Map.of("pattern", pattern));
    }

    private void write(String relative) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, relative);
    }
}
