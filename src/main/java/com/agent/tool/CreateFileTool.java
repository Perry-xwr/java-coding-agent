package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CreateFileTool implements Tool {
    static final int MAX_CONTENT_BYTES = 1_048_576;

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;
    private final AtomicTextFileWriter fileWriter;

    public CreateFileTool(Path root) {
        this(new WorkspacePathResolver(root), new ObjectMapper(), AtomicTextFileWriter.createUtf8());
    }

    CreateFileTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this(pathResolver, objectMapper, AtomicTextFileWriter.createUtf8());
    }

    CreateFileTool(
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
        return "create_file";
    }

    @Override
    public String description() {
        return "Creates one new UTF-8 text file inside the workspace without overwriting an existing file.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "New file path relative to the workspace root; its parent must exist."
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "UTF-8 text content for the new file."
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path", "content"),
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
        String content = input.get("content").asText();
        try {
            validateContent(content);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.BINARY_FILE, exception.getMessage());
        }

        Path target;
        try {
            target = pathResolver.resolveNew(path);
        } catch (FileAlreadyExistsException exception) {
            return ToolResult.failure(ToolErrorCode.FILE_ALREADY_EXISTS,
                    "File already exists: " + path);
        } catch (WorkspaceViolationException exception) {
            return ToolResult.failure(ToolErrorCode.WORKSPACE_VIOLATION, exception.getMessage());
        } catch (NoSuchFileException exception) {
            return ToolResult.failure(ToolErrorCode.PARENT_DIRECTORY_NOT_FOUND, exception.getMessage());
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, messageOrType(exception));
        }

        Map<String, Object> metadata = Map.of(
                "path", path,
                "changed", true,
                "contentBytes", content.getBytes(StandardCharsets.UTF_8).length
        );
        try {
            fileWriter.write(target, content);
            return ToolResult.success("Created " + path, metadata);
        } catch (FileAlreadyExistsException exception) {
            return ToolResult.failure(ToolErrorCode.FILE_ALREADY_EXISTS,
                    "File already exists: " + path, metadata);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage(), metadata);
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.WRITE_FAILED, messageOrType(exception), metadata);
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

    private static void validateContent(String content) {
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("content exceeds " + MAX_CONTENT_BYTES + " UTF-8 bytes");
        }
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\0' || (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t')) {
                throw new IllegalArgumentException("content contains binary-like control characters");
            }
        }
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
