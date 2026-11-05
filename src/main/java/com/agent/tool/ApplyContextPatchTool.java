package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies a bounded set of exact contextual replacements against a complete UTF-8 snapshot. */
public final class ApplyContextPatchTool implements Tool {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_ARGUMENT_BYTES = 5 * 1024 * 1024;
    static final int MAX_EDITS = 32;

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;
    private final AtomicTextFileWriter fileWriter;

    public ApplyContextPatchTool(Path workspaceRoot) {
        this(new WorkspacePathResolver(workspaceRoot), new ObjectMapper(), AtomicTextFileWriter.strictlyAtomicUtf8());
    }

    ApplyContextPatchTool(WorkspacePathResolver resolver, ObjectMapper mapper, AtomicTextFileWriter writer) {
        pathResolver = Objects.requireNonNull(resolver, "resolver must not be null");
        objectMapper = Objects.requireNonNull(mapper, "mapper must not be null");
        fileWriter = Objects.requireNonNull(writer, "writer must not be null");
    }

    @Override public String name() { return "apply_context_patch"; }

    @Override
    public String description() {
        return "Applies one or more exact, unique multiline SEARCH/REPLACE edits to an existing UTF-8 file. "
                + "Provide the complete exact expectedContent snapshot from read_file; the tool rejects stale "
                + "snapshots, ambiguous matches, missing contexts, and overlapping edits. All edits are validated "
                + "before one atomic file write. This applies text only and does not verify syntax or behavior.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "object");
        edit.put("properties", Map.of(
                "search", Map.of("type", "string", "description", "Exact non-empty multiline context."),
                "replace", Map.of("type", "string", "description", "Exact replacement text; may be empty.")));
        edit.put("required", List.of("search", "replace"));
        edit.put("additionalProperties", false);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace-relative existing file path."),
                        "expectedContent", Map.of("type", "string", "description",
                                "Complete exact file contents most recently returned by read_file."),
                        "edits", Map.of("type", "array", "items", edit,
                                "description", "All exact SEARCH/REPLACE edits to apply together.")),
                "required", List.of("path", "expectedContent", "edits"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(String arguments) {
        PatchRequest request;
        try {
            request = parse(arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        Path target;
        try {
            target = pathResolver.resolveExisting(request.path());
            if (!Files.isRegularFile(target)) {
                return ToolResult.failure(ToolErrorCode.NOT_A_REGULAR_FILE,
                        "Path is not a regular file: " + request.path());
            }
        } catch (WorkspaceViolationException exception) {
            return ToolResult.failure(ToolErrorCode.WORKSPACE_VIOLATION, exception.getMessage());
        } catch (NoSuchFileException exception) {
            return ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, exception.getMessage());
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }

        byte[] originalBytes;
        String original;
        try {
            originalBytes = Files.readAllBytes(target);
            if (originalBytes.length > MAX_FILE_BYTES) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "Target exceeds the supported " + MAX_FILE_BYTES + " byte snapshot limit.");
            }
            original = decodeUtf8(originalBytes);
        } catch (CharacterCodingException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                    "Target is not valid UTF-8; refusing to rewrite it with a different encoding.");
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }

        if (!original.equals(request.expectedContent())) {
            return ToolResult.failure(ToolErrorCode.STALE_EDIT_CONTEXT,
                    "File contents differ from expectedContent; reread the current file before editing.",
                    Map.of("path", request.path(), "expectedVersion", sha256(request.expectedContent()),
                            "actualVersion", sha256(original)));
        }

        String lineEnding;
        try {
            lineEnding = uniformLineEnding(original);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        List<LocatedEdit> located = new ArrayList<>();
        String normalizedOriginal = normalizeLineEndings(original);
        for (PatchEdit edit : request.edits()) {
            String search = normalizeLineEndings(edit.search());
            String replacement = withLineEnding(edit.replace(), lineEnding);
            int first = normalizedOriginal.indexOf(search);
            if (first < 0) {
                return ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND,
                        "A SEARCH context was not found; no edits were written.",
                        Map.of("path", request.path(), "editIndex", located.size()));
            }
            if (normalizedOriginal.indexOf(search, first + 1) >= 0) {
                return ToolResult.failure(ToolErrorCode.AMBIGUOUS_MATCH,
                        "A SEARCH context occurs more than once; no edits were written.",
                        Map.of("path", request.path(), "editIndex", located.size()));
            }
            located.add(new LocatedEdit(first, first + search.length(), search, replacement));
        }

        located.sort(java.util.Comparator.comparingInt(LocatedEdit::start));
        for (int index = 1; index < located.size(); index++) {
            if (located.get(index).start() < located.get(index - 1).end()) {
                return ToolResult.failure(ToolErrorCode.AMBIGUOUS_MATCH,
                        "SEARCH contexts overlap; no edits were written.", Map.of("path", request.path()));
            }
        }

        StringBuilder updatedNormalized = new StringBuilder(normalizedOriginal);
        for (int index = located.size() - 1; index >= 0; index--) {
            LocatedEdit edit = located.get(index);
            updatedNormalized.replace(edit.start(), edit.end(), normalizeLineEndings(edit.replacement()));
        }
        String updated = withLineEnding(updatedNormalized.toString(), lineEnding);
        if (updated.equals(original)) {
            return ToolResult.failure(ToolErrorCode.NO_EFFECT_CHANGE,
                    "The contextual patch makes no content change.", Map.of("path", request.path()));
        }

        List<Map<String, String>> diff = located.stream().map(edit -> Map.of(
                "before", edit.search(), "after", normalizeLineEndings(edit.replacement()))).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", request.path());
        output.put("summary", "Applied " + located.size() + " contextual edit(s).");
        output.put("editCount", located.size());
        output.put("versionBefore", sha256(original));
        output.put("versionAfter", sha256(updated));
        output.put("diffFormat", "exact SEARCH/REPLACE hunks (\\n-normalized)");
        output.put("diff", diff);
        Map<String, Object> metadata = Map.of("path", request.path(), "changed", true,
                "editCount", located.size(), "versionBefore", sha256(original),
                "versionAfter", sha256(updated));
        final String serializedOutput;
        try {
            serializedOutput = objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR,
                    "Unable to serialize contextual patch result.");
        }

        // Recheck immediately before the single replacement to catch changes during validation.
        try {
            if (!MessageDigest.isEqual(originalBytes, Files.readAllBytes(target))) {
                return ToolResult.failure(ToolErrorCode.STALE_EDIT_CONTEXT,
                        "File changed while the patch was being prepared; no edits were written.",
                        Map.of("path", request.path()));
            }
            fileWriter.write(target, updated);
        } catch (AccessDeniedException exception) {
            return ToolResult.failure(ToolErrorCode.ACCESS_DENIED, exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.WRITE_FAILED, messageOrType(exception));
        }

        return ToolResult.success(serializedOutput, metadata);
    }

    private PatchRequest parse(String arguments) {
        if (arguments == null || arguments.isBlank()) throw invalid("Arguments must be a JSON object.");
        if (arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            throw invalid("Arguments exceed the supported size limit.");
        }
        try {
            JsonNode root = objectMapper.readTree(arguments);
            if (root == null || !root.isObject()) throw invalid("Arguments must be a JSON object.");
            requireKeys(root, List.of("path", "expectedContent", "edits"));
            String path = text(root, "path");
            if (path.isBlank()) throw invalid("path must not be blank.");
            String expected = text(root, "expectedContent");
            JsonNode editsNode = root.get("edits");
            if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()
                    || editsNode.size() > MAX_EDITS) {
                throw invalid("edits must contain between 1 and " + MAX_EDITS + " entries.");
            }
            List<PatchEdit> edits = new ArrayList<>();
            for (JsonNode node : editsNode) {
                if (!node.isObject()) throw invalid("Each edit must be an object.");
                requireKeys(node, List.of("search", "replace"));
                String search = text(node, "search");
                String replace = text(node, "replace");
                if (search.isEmpty()) throw invalid("search must not be empty.");
                edits.add(new PatchEdit(search, replace));
            }
            return new PatchRequest(path, expected, List.copyOf(edits));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON arguments.", exception);
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw invalid(name + " must be a string.");
        return value.asText();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static void requireKeys(JsonNode node, List<String> allowed) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) throw invalid("Unexpected property in patch arguments.");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String uniformLineEnding(String content) {
        int crlf = 0, lf = 0, cr = 0;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\r') {
                if (index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    crlf++;
                    index++;
                } else cr++;
            } else if (current == '\n') lf++;
        }
        int kinds = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
        if (kinds > 1) throw invalid("Mixed line endings are not supported; no edits were written.");
        return crlf > 0 ? "\r\n" : cr > 0 ? "\r" : "\n";
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String withLineEnding(String text, String lineEnding) {
        return normalizeLineEndings(text).replace("\n", lineEnding);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String messageOrType(IOException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record PatchRequest(String path, String expectedContent, List<PatchEdit> edits) { }
    private record PatchEdit(String search, String replace) { }
    private record LocatedEdit(int start, int end, String search, String replacement) { }
}
