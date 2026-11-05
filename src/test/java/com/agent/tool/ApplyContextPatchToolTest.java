package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApplyContextPatchToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test
    void pythonTopLevelFunctionInsertionKeepsASeparatorFromPreviousFunction() throws Exception {
        String original = "def fibonacci(n):\n    print('Solution does not exist')\n";
        String replacement = "    print('Solution does not exist')\n\n\ndef hanoi(n):\n    return n\n";
        Files.writeString(workspace.resolve("algorithms.py"), original);

        ToolResult result = apply("algorithms.py", original,
                edit("    print('Solution does not exist')", "    print('Solution does not exist')\n\n\ndef hanoi(n):\n    return n"));

        assertTrue(result.success(), result.errorMessage());
        assertEquals("def fibonacci(n):\n" + replacement, Files.readString(workspace.resolve("algorithms.py")));
        assertTrue(Files.readString(workspace.resolve("algorithms.py")).contains("exist')\n\n\ndef hanoi"));
    }

    @Test
    void javaMethodCanBeInsertedAtClassMemberBoundaryWithMultilineContext() throws Exception {
        String original = "public class Algorithms {\n    int fibonacci(int n) {\n        return n;\n    }\n}\n";
        String search = "    int fibonacci(int n) {\n        return n;\n    }\n}";
        String replace = "    int fibonacci(int n) {\n        return n;\n    }\n\n    void hanoi() {}\n}";
        Files.writeString(workspace.resolve("Algorithms.java"), original);

        ToolResult result = apply("Algorithms.java", original, edit(search, replace));

        assertTrue(result.success(), result.errorMessage());
        String updated = Files.readString(workspace.resolve("Algorithms.java"));
        assertEquals("public class Algorithms {\n" + replace + "\n", updated);
        assertTrue(updated.indexOf("void hanoi") > updated.indexOf("    }\n"));
    }

    @Test
    void cFunctionInsertionAppliesCompleteBlockWithoutConcatenatingDeclarations() throws Exception {
        String original = "int fibonacci(int n) {\n    return n;\n}\n";
        String replacement = "int fibonacci(int n) {\n    return n;\n}\n\nint hanoi(int n) {\n    return n;\n}\n";
        Files.writeString(workspace.resolve("algorithms.c"), original);

        ToolResult result = apply("algorithms.c", original,
                edit("int fibonacci(int n) {\n    return n;\n}\n",
                        "int fibonacci(int n) {\n    return n;\n}\n\nint hanoi(int n) {\n    return n;\n}\n"));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(replacement, Files.readString(workspace.resolve("algorithms.c")));
    }

    @Test
    void appliesMultipleNonOverlappingEditsTogether() throws Exception {
        String original = "alpha();\nkeep();\nomega();\n";
        String updated = "first();\nkeep();\nlast();\n";
        Files.writeString(workspace.resolve("multi-edit.txt"), original);

        ToolResult result = apply("multi-edit.txt", original,
                edit("alpha();", "first();"), edit("omega();", "last();"));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(updated, Files.readString(workspace.resolve("multi-edit.txt")));
        assertEquals(2, JSON.readTree(result.output()).path("editCount").asInt());
    }

    @Test
    void rejectsAmbiguousSearchWithoutChangingFile() throws Exception {
        String original = "value();\nvalue();\n";
        Files.writeString(workspace.resolve("duplicate.c"), original);

        ToolResult result = apply("duplicate.c", original, edit("value();", "changed();"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.AMBIGUOUS_MATCH, result.errorCode());
        assertEquals(original, Files.readString(workspace.resolve("duplicate.c")));
    }

    @Test
    void rejectsStaleFullFileSnapshotWithoutChangingFile() throws Exception {
        String actual = "class A {}\n";
        Files.writeString(workspace.resolve("A.java"), actual);

        ToolResult result = apply("A.java", "class A { }\n", edit("class A {}", "class B {}"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.STALE_EDIT_CONTEXT, result.errorCode());
        assertEquals(actual, Files.readString(workspace.resolve("A.java")));
    }

    @Test
    void failedEditInMultiEditRequestLeavesEarlierPotentialChangeUnwritten() throws Exception {
        String original = "alpha();\nbeta();\n";
        Files.writeString(workspace.resolve("multi.c"), original);

        ToolResult result = apply("multi.c", original,
                edit("alpha();", "changed();"), edit("missing();", "alsoChanged();"));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TEXT_NOT_FOUND, result.errorCode());
        assertEquals(original, Files.readString(workspace.resolve("multi.c")));
    }

    @Test
    void preservesLfAndCrlfAndOnlyUsesTheExistingLineEndingForInsertedLines() throws Exception {
        for (String eol : List.of("\n", "\r\n")) {
            String original = "first();" + eol + "second();" + eol;
            Path file = workspace.resolve(eol.equals("\n") ? "lf.txt" : "crlf.txt");
            Files.writeString(file, original, StandardCharsets.UTF_8);
            ToolResult result = apply(file.getFileName().toString(), original,
                    edit("second();", "second();" + eol + "third();"));
            String updated = Files.readString(file);

            assertTrue(result.success(), result.errorMessage());
            assertEquals("first();" + eol + "second();" + eol + "third();" + eol, updated);
            assertFalse(updated.replace("\r\n", "").contains("\n") && eol.equals("\r\n"));
        }
    }

    @Test
    void returnsStructuredDiffAndHashesThatMatchTheActualFile() throws Exception {
        String original = "first();\nsecond();\n";
        String updated = "first();\nchanged();\n";
        Files.writeString(workspace.resolve("diff.txt"), original);

        ToolResult result = apply("diff.txt", original, edit("second();", "changed();"));
        JsonNode output = JSON.readTree(result.output());

        assertTrue(result.success());
        assertEquals(updated, Files.readString(workspace.resolve("diff.txt")));
        assertEquals("diff.txt", output.path("path").asText());
        assertEquals(1, output.path("editCount").asInt());
        assertEquals(hash(original), output.path("versionBefore").asText());
        assertEquals(hash(updated), output.path("versionAfter").asText());
        assertEquals("second();", output.path("diff").get(0).path("before").asText());
        assertEquals("changed();", output.path("diff").get(0).path("after").asText());
    }

    @Test
    void cliCodeRegistryExposesContextPatchAlongsideLegacyTools() {
        ToolRegistry code = ToolRegistry.withCliCodingTools(workspace,
                (command, directory, timeout, maxOutput) ->
                        new com.agent.tool.execution.ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1),
                workspace.resolve(".m2/repository"));

        assertEquals("apply_context_patch", code.getTool("apply_context_patch").name());
        assertTrue(code.definitions().stream().anyMatch(definition ->
                definition.name().equals("apply_context_patch")
                        && definition.parameters().containsKey("properties")));
        ToolRegistry readOnly = ToolRegistry.withFileTools(workspace);
        assertThrows(IllegalArgumentException.class, () -> readOnly.getTool("apply_context_patch"));
        for (String legacy : List.of("apply_patch", "insert_before", "insert_after", "create_file")) {
            assertTrue(code.getTool(legacy) != null);
        }
    }

    private ToolResult apply(String path, String expected, Map<String, String>... edits) throws Exception {
        return new ApplyContextPatchTool(workspace).execute(JSON.writeValueAsString(Map.of(
                "path", path, "expectedContent", expected, "edits", List.of(edits))));
    }

    private static Map<String, String> edit(String search, String replace) {
        return Map.of("search", search, "replace", replace);
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
