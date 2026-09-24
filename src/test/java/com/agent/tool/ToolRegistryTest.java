package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersAndFindsAllFileTools() {
        ToolRegistry registry = ToolRegistry.withFileTools(tempDir);

        assertEquals("list_files", registry.getTool("list_files").name());
        assertEquals("read_file", registry.getTool("read_file").name());
        assertEquals("search_code", registry.getTool("search_code").name());
        assertEquals(3, registry.definitions().size());
        assertTrue(registry.definitions().stream().anyMatch(
                definition -> definition.name().equals("read_file")
                        && definition.parameters().toString().contains("path")
        ));
    }

    @Test
    void registersControlledCodingTools() {
        ToolRegistry registry = ToolRegistry.withCodingTools(
                tempDir,
                (command, directory, timeout, maxOutput) ->
                        new com.agent.tool.execution.ProcessExecutionResult(
                                0, false, "BUILD SUCCESS", false, 1
                        )
        );

        assertEquals("apply_patch", registry.getTool("apply_patch").name());
        assertEquals("run_maven_test", registry.getTool("run_maven_test").name());
        assertEquals(5, registry.definitions().size());
    }

    @Test
    void preciseEditRegistryAddsLineEditorWithoutChangingExistingCodingRegistry() {
        com.agent.tool.execution.ProcessRunner runner =
                (command, directory, timeout, maxOutput) ->
                        new com.agent.tool.execution.ProcessExecutionResult(
                                0, false, "BUILD SUCCESS", false, 1
                        );
        ToolRegistry existing = ToolRegistry.withActionOrientedCodingTools(
                tempDir, runner, tempDir.resolve(".m2/repository")
        );
        ToolRegistry precise = ToolRegistry.withPreciseEditCodingTools(
                tempDir, runner, tempDir.resolve(".m2/repository")
        );

        assertThrows(IllegalArgumentException.class, () -> existing.getTool("replace_lines"));
        assertEquals("replace_lines", precise.getTool("replace_lines").name());
        assertTrue(precise.definitions().stream()
                .filter(definition -> definition.name().equals("apply_patch"))
                .findFirst().orElseThrow().description().contains("replace_lines"));
    }

    @Test
    void actionOrientedDefinitionsAddCompletionAndRecoveryGuidanceOnlyThere() {
        com.agent.tool.execution.ProcessRunner runner =
                (command, directory, timeout, maxOutput) ->
                        new com.agent.tool.execution.ProcessExecutionResult(
                                0, false, "BUILD SUCCESS", false, 1
                        );
        ToolRegistry original = ToolRegistry.withCodingTools(tempDir, runner);
        ToolRegistry actionOriented = ToolRegistry.withActionOrientedCodingTools(
                tempDir,
                runner,
                tempDir.resolve(".m2/repository")
        );

        String originalPatch = original.definitions().stream()
                .filter(definition -> definition.name().equals("apply_patch"))
                .findFirst().orElseThrow().description();
        String guardedPatch = actionOriented.definitions().stream()
                .filter(definition -> definition.name().equals("apply_patch"))
                .findFirst().orElseThrow().description();
        String guardedTest = actionOriented.definitions().stream()
                .filter(definition -> definition.name().equals("run_maven_test"))
                .findFirst().orElseThrow().description();

        assertEquals(
                "Replaces one unique text occurrence in an existing UTF-8 workspace file.",
                originalPatch
        );
        assertTrue(guardedPatch.contains("actually modifies"));
        assertTrue(guardedPatch.contains("TEXT_NOT_FOUND"));
        assertTrue(guardedTest.contains("TEST_FAILED"));
        assertTrue(guardedTest.contains("verified until tests pass"));
    }

    @Test
    void executesAllFileTools() throws IOException {
        Files.writeString(tempDir.resolve("Example.java"), "class Example { // needle\n}\n");
        ToolRegistry registry = ToolRegistry.withFileTools(tempDir);

        JsonNode listedFiles = objectMapper.readTree(registry.execute("list_files", "{}").output());
        assertEquals("Example.java", listedFiles.get(0).asText());

        assertEquals(
                "class Example { // needle\n}\n",
                registry.execute("read_file", "{\"path\":\"Example.java\"}").output()
        );
        assertEquals(
                "1 | class Example { // needle\n2 | }",
                registry.execute(
                        "read_file",
                        "{\"path\":\"Example.java\",\"includeLineNumbers\":true}"
                ).output()
        );

        JsonNode matches = objectMapper.readTree(registry.execute(
                "search_code",
                "{\"keyword\":\"needle\"}"
        ).output());
        assertEquals("Example.java", matches.get(0).path("file").asText());
        assertEquals(1, matches.get(0).path("line").asInt());
        assertEquals("class Example { // needle", matches.get(0).path("content").asText());
    }

    @Test
    void registersAndExecutesCustomTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echoes its arguments.";
            }

            @Override
            public ToolResult execute(String arguments) {
                return ToolResult.success(arguments);
            }
        });

        assertEquals("hello", registry.execute("echo", "hello").output());
    }

    @Test
    void rejectsUnknownTool() {
        ToolRegistry registry = new ToolRegistry();

        ToolResult result = registry.execute("missing", "{}");

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.errorCode());
        assertTrue(result.errorMessage().contains("missing"));
    }

    @Test
    void readFileAllowsWorkspaceFileAndRejectsTraversal() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "safe content");
        Files.writeString(tempDir.resolve("outside.txt"), "outside content");
        ToolRegistry registry = ToolRegistry.withFileTools(workspace);

        assertEquals(
                "safe content",
                registry.execute("read_file", "{\"path\":\"README.md\"}").output()
        );

        ToolResult result = registry.execute(
                "read_file",
                "{\"path\":\"../outside.txt\"}"
        );
        assertTrue(!result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
        assertTrue(result.errorMessage().contains("outside the workspace"));
    }
}
