package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Finds workspace files by a glob pattern without reading their contents. */
public final class FindFilesTool implements Tool {
    static final int MAX_RESULTS = 100;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "target", ".m2", ".gradle", "node_modules"
    );

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public FindFilesTool(Path root) {
        this(new WorkspacePathResolver(root), new ObjectMapper());
    }

    FindFilesTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String name() {
        return "find_files";
    }

    @Override
    public String description() {
        return "Finds workspace-relative regular file paths matching one glob pattern without reading file contents.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("pattern", Map.of(
                        "type", "string",
                        "description", "Glob pattern relative to the workspace. Simple filename patterns search recursively; directory-qualified patterns restrict the search."
                )),
                "required", List.of("pattern"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        String pattern;
        try {
            pattern = pattern(arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        if (isAbsolute(pattern) || containsParentTraversal(pattern)) {
            return ToolResult.failure(
                    ToolErrorCode.WORKSPACE_VIOLATION,
                    "Glob pattern must be workspace-relative and must not contain '..': " + pattern
            );
        }

        PathMatcher matcher;
        PathMatcher rootFileMatcher;
        boolean filenamePattern = !containsDirectorySeparator(pattern);
        try {
            FileSystem fileSystem = FileSystems.getDefault();
            matcher = fileSystem.getPathMatcher("glob:" + pattern);
            rootFileMatcher = pattern.startsWith("**/")
                    ? fileSystem.getPathMatcher("glob:" + pattern.substring(3))
                    : null;
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_PATTERN, "Invalid glob pattern: " + pattern);
        }

        try {
            List<String> matches = findMatches(matcher, rootFileMatcher, filenamePattern);
            boolean truncated = matches.size() > MAX_RESULTS;
            int totalMatches = matches.size();
            List<String> returned = matches.stream().limit(MAX_RESULTS).toList();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("files", returned);
            output.put("truncated", truncated);
            output.put("totalMatches", totalMatches);
            return ToolResult.success(
                    objectMapper.writeValueAsString(output),
                    Map.of("truncated", truncated, "totalMatches", totalMatches)
            );
        } catch (IOException exception) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, messageOrType(exception));
        }
    }

    private List<String> findMatches(
            PathMatcher matcher,
            PathMatcher rootFileMatcher,
            boolean filenamePattern
    ) throws IOException {
        Path root = pathResolver.root();
        List<String> matches = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root) && isIgnored(root.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && !isIgnored(root.relativize(file))) {
                    addIfWorkspaceMatch(root, matcher, rootFileMatcher, filenamePattern, file, matches);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        matches.sort(Comparator.naturalOrder());
        return matches;
    }

    private void addIfWorkspaceMatch(
            Path root,
            PathMatcher matcher,
            PathMatcher rootFileMatcher,
            boolean filenamePattern,
            Path path,
            List<String> matches
    ) {
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(root)) {
                return;
            }
            Path relative = root.relativize(realPath);
            Path candidate = filenamePattern ? relative.getFileName() : relative;
            if (matcher.matches(candidate)
                    || (rootFileMatcher != null && relative.getNameCount() == 1 && rootFileMatcher.matches(relative))) {
                matches.add(relative.toString());
            }
        } catch (IOException ignored) {
            // A concurrently removed or inaccessible file must not fail a read-only discovery request.
        }
    }

    private static boolean isIgnored(Path relative) {
        for (Path segment : relative) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String pattern(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        try {
            JsonNode input = objectMapper.readTree(arguments);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            JsonNode value = input.get("pattern");
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException("pattern must be a non-blank string");
            }
            return value.asText();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", exception);
        }
    }

    private static boolean isAbsolute(String pattern) {
        if (pattern.startsWith("/") || pattern.startsWith("\\\\") || pattern.matches("^[A-Za-z]:[\\\\/].*")) {
            return true;
        }
        try {
            return Path.of(pattern).isAbsolute();
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static boolean containsParentTraversal(String pattern) {
        for (String segment : pattern.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDirectorySeparator(String pattern) {
        return pattern.indexOf('/') >= 0 || pattern.indexOf('\\') >= 0;
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
