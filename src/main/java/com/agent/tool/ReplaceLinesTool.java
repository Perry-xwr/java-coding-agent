package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReplaceLinesTool implements Tool {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "path", "startLine", "endLine", "expectedText", "newText"
    );

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;
    private final AtomicTextFileWriter fileWriter;

    public ReplaceLinesTool(Path root) {
        this(new WorkspacePathResolver(root), new ObjectMapper(), AtomicTextFileWriter.utf8());
    }

    ReplaceLinesTool(
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
        return "replace_lines";
    }

    @Override
    public String description() {
        return "Atomically replaces a 1-based inclusive line range in an existing UTF-8 "
                + "workspace file after verifying its current expectedText.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", stringProperty("Existing file path relative to the workspace root."));
        properties.put("startLine", integerProperty("First line to replace, starting at 1."));
        properties.put("endLine", integerProperty("Last line to replace, inclusive."));
        properties.put("expectedText", stringProperty(
                "Exact current text of startLine through endLine, copied from the latest read."
        ));
        properties.put("newText", stringProperty("Replacement text; it may contain multiple lines."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path", "startLine", "endLine", "expectedText", "newText"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        Input input;
        try {
            input = parse(arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        Path target;
        try {
            target = pathResolver.resolveExisting(input.path());
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
                    "Path is not a regular file: " + input.path()
            );
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            return ToolResult.failure(ToolErrorCode.BINARY_FILE, "File is not valid UTF-8 text");
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }
        if (original.indexOf('\0') >= 0 || input.newText().indexOf('\0') >= 0) {
            return ToolResult.failure(ToolErrorCode.BINARY_FILE, "NUL bytes are not editable text");
        }

        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String normalized = normalizeNewlines(original);
        boolean trailingNewline = normalized.endsWith("\n");
        List<String> lines = logicalLines(normalized, trailingNewline);
        if (input.startLine() < 1
                || input.endLine() < input.startLine()
                || input.endLine() > lines.size()) {
            return ToolResult.failure(
                    ToolErrorCode.INVALID_LINE_RANGE,
                    "Line range " + input.startLine() + "-" + input.endLine()
                            + " is outside file line count " + lines.size(),
                    metadata(input, false, original.length(), original.length(), 0)
            );
        }

        String currentRange = String.join(
                "\n",
                lines.subList(input.startLine() - 1, input.endLine())
        );
        if (!currentRange.equals(normalizeNewlines(input.expectedText()))) {
            return ToolResult.failure(
                    ToolErrorCode.STALE_EDIT_CONTEXT,
                    "expectedText does not match the current line range; reread the file",
                    metadata(input, false, original.length(), original.length(), 0)
            );
        }

        List<String> replacement = replacementLines(input.newText());
        List<String> updatedLines = new ArrayList<>(lines.size() + replacement.size());
        updatedLines.addAll(lines.subList(0, input.startLine() - 1));
        updatedLines.addAll(replacement);
        updatedLines.addAll(lines.subList(input.endLine(), lines.size()));
        String updatedNormalized = String.join("\n", updatedLines)
                + (trailingNewline ? "\n" : "");
        String updated = "\r\n".equals(newline)
                ? updatedNormalized.replace("\n", "\r\n")
                : updatedNormalized;
        int lineDelta = replacement.size() - (input.endLine() - input.startLine() + 1);
        Map<String, Object> resultMetadata = metadata(
                input, !updated.equals(original), original.length(), updated.length(), lineDelta
        );
        if (updated.equals(original)) {
            return ToolResult.failure(
                    ToolErrorCode.NO_EFFECT_CHANGE,
                    "Line replacement made no content change: " + input.path(),
                    resultMetadata
            );
        }

        try {
            fileWriter.write(target, updated);
            return ToolResult.success(
                    "Replaced lines " + input.startLine() + "-" + input.endLine()
                            + " in " + input.path(),
                    resultMetadata
            );
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolErrorCode.WRITE_FAILED,
                    messageOrType(exception),
                    resultMetadata
            );
        }
    }

    private Input parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        try {
            JsonNode input = objectMapper.readTree(arguments);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            input.fieldNames().forEachRemaining(name -> {
                if (!ALLOWED_FIELDS.contains(name)) {
                    throw new IllegalArgumentException("Unsupported argument: " + name);
                }
            });
            String path = requiredText(input, "path");
            String expectedText = requiredText(input, "expectedText");
            String newText = requiredText(input, "newText");
            int startLine = requiredInt(input, "startLine");
            int endLine = requiredInt(input, "endLine");
            return new Input(path, startLine, endLine, expectedText, newText);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", exception);
        }
    }

    private static String requiredText(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        if ("path".equals(field) && value.asText().isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static List<String> logicalLines(String normalized, boolean trailingNewline) {
        String[] split = normalized.split("\n", -1);
        int count = trailingNewline ? split.length - 1 : split.length;
        return new ArrayList<>(List.of(split).subList(0, count));
    }

    private static List<String> replacementLines(String text) {
        String normalized = normalizeNewlines(text);
        String[] split = normalized.split("\n", -1);
        int count = normalized.endsWith("\n") ? split.length - 1 : split.length;
        return new ArrayList<>(List.of(split).subList(0, count));
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Map<String, Object> metadata(
            Input input,
            boolean changed,
            int oldLength,
            int newLength,
            int lineDelta
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("path", input.path());
        values.put("startLine", input.startLine());
        values.put("endLine", input.endLine());
        values.put("changed", changed);
        values.put("oldLength", oldLength);
        values.put("newLength", newLength);
        values.put("lineDelta", lineDelta);
        return values;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record Input(
            String path,
            int startLine,
            int endLine,
            String expectedText,
            String newText
    ) {
    }
}
