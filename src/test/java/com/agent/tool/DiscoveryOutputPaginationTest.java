package com.agent.tool;

import com.agent.llm.Message;
import com.agent.llm.RequestContextMetrics;
import com.agent.llm.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryOutputPaginationTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void boundsLargeDirectoryOutputAndPagesAllSortedPathsWithoutDuplicates() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("many-files"));
        for (int index = 0; index < 350; index++) {
            Path file = workspace.resolve("src/pkg/file-%04d.java".formatted(index));
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class C{}\n");
        }
        List<String> raw = new ListFilesTool().listFiles(workspace);
        String rawObservation = mapper.writeValueAsString(raw);
        ToolRegistry registry = ToolRegistry.withFileTools(workspace);
        JsonNode first = invoke(registry, "list_files", "{}", "files");

        assertTrue(first.path("truncated").asBoolean());
        assertTrue(first.path("nextOffset").asInt() > 0);
        assertTrue(first.toString().length() <= PagedToolOutput.MAX_OUTPUT_CHARS);
        assertTrue(first.toString().length() < rawObservation.length());
        assertTrue(requestMetrics(first.toString()).payloadBytes() < requestMetrics(rawObservation).payloadBytes());
        assertEquals(350, first.path("totalFiles").asInt());
        reportMetrics("large-directory", rawObservation, first.toString());

        List<String> collected = collectFiles(registry);
        assertEquals(raw, collected);
        assertEquals(collected.size(), new HashSet<>(collected).size());
    }

    @Test
    void boundsManySearchMatchesAndCanReassembleVeryLongSingleLine() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("matches"));
        Path file = workspace.resolve("src/main/Example.java");
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 160; index++) lines.add("MATCH result-" + index);
        String longLine = "MATCH " + "\"".repeat(10_000);
        lines.add(53, longLine);
        Files.writeString(file, String.join("\n", lines) + "\n");
        Files.writeString(workspace.resolve("A.java"), "MATCH first-file\n");

        List<SearchCodeTool.Match> raw = new SearchCodeTool().searchCode("MATCH", workspace);
        String rawObservation = mapper.writeValueAsString(raw);
        ToolRegistry registry = ToolRegistry.withFileTools(workspace);
        JsonNode first = invoke(registry, "search_code", "{\"keyword\":\"MATCH\"}", "matches");
        assertTrue(first.path("truncated").asBoolean());
        assertTrue(first.toString().length() <= PagedToolOutput.MAX_OUTPUT_CHARS);
        assertTrue(first.toString().length() < rawObservation.length());
        assertTrue(requestMetrics(first.toString()).payloadBytes() < requestMetrics(rawObservation).payloadBytes());
        reportMetrics("many-search-matches", rawObservation, first.toString());

        List<JsonNode> fragments = collectMatches(registry);
        assertEquals(raw.size(), fragments.stream().map(node -> node.path("file").asText() + ":"
                + node.path("line").asInt()).distinct().count());
        List<String> ordering = fragments.stream().map(node -> node.path("file").asText() + ":"
                + String.format("%08d:%08d", node.path("line").asInt(), node.path("fragmentIndex").asInt())).toList();
        List<String> sortedOrdering = ordering.stream().sorted().toList();
        assertEquals(sortedOrdering, ordering);
        assertTrue(fragments.stream().allMatch(node -> node.path("lineTruncated").isBoolean()));
        List<JsonNode> longLineFragments = fragments.stream()
                .filter(node -> node.path("lineTruncated").asBoolean())
                .toList();
        assertTrue(longLineFragments.size() > 1);
        String rebuilt = longLineFragments.stream()
                .sorted(java.util.Comparator.comparingInt(node -> node.path("fragmentIndex").asInt()))
                .map(node -> node.path("content").asText())
                .reduce("", String::concat);
        assertEquals(longLine, rebuilt);
        assertEquals(longLineFragments.get(0).path("fragmentCount").asInt(), longLineFragments.size());
    }

    @Test
    void supportsSmallLegacyCallsEmptyResultsAndRejectsInvalidPaginationAndUnsafePaths() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("small"));
        Files.writeString(workspace.resolve("A.java"), "class A {\n    int value = 7;\n}\n");
        Files.writeString(workspace.resolve("main.py"), "def main():\n    return 1\n");
        ToolRegistry registry = ToolRegistry.withFileTools(workspace);

        JsonNode listed = invoke(registry, "list_files", "{}", "files");
        assertFalse(listed.path("truncated").asBoolean());
        assertEquals(List.of("A.java", "main.py"), filePaths(listed));
        JsonNode matches = invoke(registry, "search_code", "{\"keyword\":\"class\"}", "matches");
        assertEquals(1, matches.path("matches").size());
        String smallRaw = mapper.writeValueAsString(new ListFilesTool().listFiles(workspace));
        reportMetrics("small-java-python-project", smallRaw, listed.toString());
        JsonNode empty = invoke(registry, "search_code", "{\"keyword\":\"absent\"}", "matches");
        assertTrue(empty.path("matches").isEmpty());
        assertFalse(empty.path("truncated").asBoolean());

        for (String args : List.of("{\"limit\":0}", "{\"limit\":101}",
                "{\"offset\":-1}", "{\"offset\":\"zero\"}")) {
            ToolResult invalid = registry.execute("list_files", args);
            assertFalse(invalid.success(), args);
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, invalid.errorCode());
        }
        ToolResult invalidSearchPage = registry.execute("search_code", "{\"keyword\":\"class\",\"limit\":0}");
        assertFalse(invalidSearchPage.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, invalidSearchPage.errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("list_files", "{\"path\":\"..\"}").errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("search_code", "{\"keyword\":\"class\",\"path\":\"../\"}").errorCode());

        String expected = Files.readString(workspace.resolve("A.java"));
        assertEquals(expected, registry.execute("read_file", "{\"path\":\"A.java\"}").output());
        Files.writeString(workspace.resolve("A.java"), expected + "// externally changed\n");
        ApplyContextPatchTool patch = new ApplyContextPatchTool(workspace);
        ToolResult stale = patch.execute(mapper.writeValueAsString(Map.of(
                "path", "A.java", "expectedContent", expected,
                "edits", List.of(Map.of("search", "int value = 7;", "replace", "int value = 8;")))));
        assertFalse(stale.success());
        assertEquals(ToolErrorCode.STALE_EDIT_CONTEXT, stale.errorCode());
    }

    private List<String> collectFiles(ToolRegistry registry) throws Exception {
        List<String> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = invoke(registry, "list_files", "{\"offset\":" + offset + ",\"limit\":100}", "files");
            for (JsonNode item : page.path("files")) result.add(item.path("path").asText());
            if (!page.path("truncated").asBoolean()) break;
            int next = page.path("nextOffset").asInt();
            assertTrue(next > offset);
            offset = next;
        }
        return result;
    }

    private List<JsonNode> collectMatches(ToolRegistry registry) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = invoke(registry, "search_code", "{\"keyword\":\"MATCH\",\"offset\":"
                    + offset + ",\"limit\":100}", "matches");
            page.path("matches").forEach(result::add);
            if (!page.path("truncated").asBoolean()) break;
            int next = page.path("nextOffset").asInt();
            assertTrue(next > offset);
            offset = next;
        }
        return result;
    }

    private JsonNode invoke(ToolRegistry registry, String name, String args, String field) throws Exception {
        ToolResult result = registry.execute(name, args);
        assertTrue(result.success(), result.errorMessage());
        JsonNode page = mapper.readTree(result.output());
        assertTrue(page.path(field).isArray());
        assertTrue(result.output().length() <= PagedToolOutput.MAX_OUTPUT_CHARS);
        return page;
    }

    private static List<String> filePaths(JsonNode page) {
        List<String> paths = new ArrayList<>();
        page.path("files").forEach(item -> paths.add(item.path("path").asText()));
        return paths;
    }

    private RequestContextMetrics requestMetrics(String observation) throws Exception {
        List<Message> messages = List.of(Message.system("offline fixture"), Message.tool("call-1", observation));
        ToolDefinition definition = new ToolDefinition("list_files", "fixture tool",
                Map.of("type", "object", "properties", Map.of()));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of(
                "name", definition.name(), "description", definition.description(),
                "parameters", definition.parameters())));
        byte[] payload = mapper.writeValueAsBytes(Map.of("model", "offline", "messages", messages, "tools", tools));
        return RequestContextMetrics.of(messages, List.of(definition), payload.length);
    }

    private void reportMetrics(String fixture, String originalObservation, String firstPageObservation)
            throws Exception {
        if (!Boolean.getBoolean("agent.test.printDiscoveryMetrics")) return;
        System.out.printf("DISCOVERY_METRIC fixture=%s rawChars=%d pageChars=%d simulatedPayloadBytes=%d/%d%n",
                fixture, originalObservation.length(), firstPageObservation.length(),
                requestMetrics(originalObservation).payloadBytes(), requestMetrics(firstPageObservation).payloadBytes());
    }
}
