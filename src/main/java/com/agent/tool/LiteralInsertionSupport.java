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
import java.util.Map;

/** Shared safe implementation for literal insertion immediately before or after one unique anchor. */
final class LiteralInsertionSupport {
    private LiteralInsertionSupport() {
    }

    static ToolResult insert(
            WorkspacePathResolver pathResolver,
            ObjectMapper objectMapper,
            AtomicTextFileWriter fileWriter,
            String arguments,
            boolean before
    ) {
        JsonNode input;
        try {
            input = parseArguments(objectMapper, arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        String path = input.get("path").asText();
        String anchor = input.get("anchor").asText();
        String content = input.get("content").asText();
        if (path.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "path must not be blank");
        }
        if (anchor.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "anchor must not be blank");
        }
        if (content.isEmpty()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "content must not be empty");
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
            return ToolResult.failure(ToolErrorCode.NOT_A_REGULAR_FILE, "Path is not a regular file: " + path);
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }

        int matchCount = countOccurrences(original, anchor);
        Map<String, Object> metadata = metadata(path, false, original.length(), original.length(), matchCount);
        if (matchCount == 0) {
            return ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND, "anchor was not found in: " + path, metadata);
        }
        if (matchCount > 1) {
            return ToolResult.failure(ToolErrorCode.AMBIGUOUS_MATCH,
                    "anchor matched more than once in: " + path, metadata);
        }

        int anchorIndex = original.indexOf(anchor);
        String updated = before
                ? original.substring(0, anchorIndex) + content + original.substring(anchorIndex)
                : original.substring(0, anchorIndex + anchor.length()) + content
                + original.substring(anchorIndex + anchor.length());
        boolean changed = !updated.equals(original);
        Map<String, Object> successMetadata = metadata(path, changed, original.length(), updated.length(), matchCount);
        if (!changed) {
            return ToolResult.failure(ToolErrorCode.NO_EFFECT_CHANGE,
                    "Insertion made no content change: " + path, successMetadata);
        }

        try {
            fileWriter.write(target, updated);
            return ToolResult.success("Inserted content " + (before ? "before" : "after")
                    + " anchor in " + path, successMetadata);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage(), successMetadata);
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.WRITE_FAILED, messageOrType(exception), successMetadata);
        }
    }

    private static JsonNode parseArguments(ObjectMapper objectMapper, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        try {
            JsonNode input = objectMapper.readTree(arguments);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            requireText(input, "path");
            requireText(input, "anchor");
            requireText(input, "content");
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
            String path, boolean changed, int oldLength, int newLength, int matchCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", path);
        metadata.put("changed", changed);
        metadata.put("oldLength", oldLength);
        metadata.put("newLength", newLength);
        metadata.put("matchCount", matchCount);
        return metadata;
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
