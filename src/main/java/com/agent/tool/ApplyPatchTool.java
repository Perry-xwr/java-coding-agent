package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ApplyPatchTool implements Tool {
    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;
    private final AtomicTextFileWriter fileWriter;

    public ApplyPatchTool(Path root) {
        this(new WorkspacePathResolver(root), new ObjectMapper(), AtomicTextFileWriter.utf8());
    }

    ApplyPatchTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this(pathResolver, objectMapper, AtomicTextFileWriter.utf8());
    }

    ApplyPatchTool(
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
        return "apply_patch";
    }

    @Override
    public String description() {
        return "Replaces one unique text occurrence in an existing UTF-8 workspace file.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", stringProperty("Existing file path relative to the workspace root."));
        properties.put("oldText", stringProperty("Text that must occur exactly once."));
        properties.put("newText", stringProperty("Replacement text; it may be empty."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path", "oldText", "newText"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        JsonNode input;
        try {
            input = parseArguments(arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        String path = input.get("path").asText();
        String oldText = input.get("oldText").asText();
        String newText = input.get("newText").asText();
        if (path.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "path must not be blank");
        }
        if (oldText.isEmpty()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "oldText must not be empty");
        }

        Path target;
        try {
            target = pathResolver.resolveExisting(path);
        } catch (WorkspaceViolationException exception) {
            return ToolResult.failure(ToolErrorCode.WORKSPACE_VIOLATION, exception.getMessage());
        } catch (NoSuchFileException exception) {
            return ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, exception.getMessage());
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, messageOrType(exception));
        }

        if (!Files.isRegularFile(target)) {
            return ToolResult.failure(
                    ToolErrorCode.NOT_A_REGULAR_FILE,
                    "Path is not a regular file: " + path
            );
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }

        int matchCount = countOccurrences(original, oldText);
        Map<String, Object> metadata = metadata(
                path,
                false,
                original.length(),
                original.length(),
                matchCount
        );
        if (matchCount == 0) {
            return ToolResult.failure(
                    ToolErrorCode.TEXT_NOT_FOUND,
                    "oldText was not found in: " + path,
                    metadata
            );
        }
        if (matchCount > 1) {
            return ToolResult.failure(
                    ToolErrorCode.MULTIPLE_MATCHES,
                    "oldText matched more than once in: " + path,
                    metadata
            );
        }

        int matchIndex = original.indexOf(oldText);
        String updated = original.substring(0, matchIndex)
                + newText
                + original.substring(matchIndex + oldText.length());
        boolean changed = !updated.equals(original);
        Map<String, Object> successMetadata = metadata(
                path,
                changed,
                original.length(),
                updated.length(),
                matchCount
        );
        if (!changed) {
            return ToolResult.failure(
                    ToolErrorCode.NO_EFFECT_CHANGE,
                    "Patch made no content change: " + path,
                    successMetadata
            );
        }

        try {
            fileWriter.write(target, updated);
            return ToolResult.success("Patched " + path, successMetadata);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolErrorCode.WRITE_FAILED,
                    messageOrType(exception),
                    successMetadata
            );
        }
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        try {
            JsonNode input = objectMapper.readTree(arguments);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            requireText(input, "path");
            requireText(input, "oldText");
            requireText(input, "newText");
            return input;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", exception);
        }
    }

    private static void requireText(JsonNode input, String name) {
        JsonNode value = input.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
    }

    private static int countOccurrences(String content, String text) {
        int count = 0;
        int fromIndex = 0;
        while (fromIndex <= content.length() - text.length()) {
            int index = content.indexOf(text, fromIndex);
            if (index < 0) {
                break;
            }
            count++;
            fromIndex = index + 1;
        }
        return count;
    }

    private static Map<String, Object> metadata(
            String path,
            boolean changed,
            int oldLength,
            int newLength,
            int matchCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", path);
        metadata.put("changed", changed);
        metadata.put("oldLength", oldLength);
        metadata.put("newLength", newLength);
        metadata.put("matchCount", matchCount);
        return metadata;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
