package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Inserts text after one unique literal anchor in an existing UTF-8 workspace file. */
public final class InsertAfterTool implements Tool {
    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;
    private final AtomicTextFileWriter fileWriter;

    public InsertAfterTool(Path root) {
        this(new WorkspacePathResolver(root), new ObjectMapper(), AtomicTextFileWriter.utf8());
    }

    InsertAfterTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this(pathResolver, objectMapper, AtomicTextFileWriter.utf8());
    }

    InsertAfterTool(
            WorkspacePathResolver pathResolver,
            ObjectMapper objectMapper,
            AtomicTextFileWriter fileWriter
    ) {
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
    }

    @Override
    public String name() {
        return "insert_after";
    }

    @Override
    public String description() {
        return "Inserts content after one unique literal anchor in an existing UTF-8 workspace file.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", stringProperty("Existing file path relative to the workspace root."));
        properties.put("anchor", stringProperty("Non-blank literal text that must occur exactly once."));
        properties.put("content", stringProperty("Non-empty text to insert immediately after the anchor."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path", "anchor", "content"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        return LiteralInsertionSupport.insert(pathResolver, objectMapper, fileWriter, arguments, false);
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }
}
