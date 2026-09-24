package com.agent.tool;

import com.agent.llm.ToolDefinition;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final boolean actionOrientedDescriptions;
    private final boolean preciseEditDescriptions;

    public ToolRegistry() {
        this(false, false);
    }

    private ToolRegistry(boolean actionOrientedDescriptions) {
        this(actionOrientedDescriptions, false);
    }

    private ToolRegistry(boolean actionOrientedDescriptions, boolean preciseEditDescriptions) {
        this.actionOrientedDescriptions = actionOrientedDescriptions;
        this.preciseEditDescriptions = preciseEditDescriptions;
    }

    public static ToolRegistry withFileTools(Path root) {
        Objects.requireNonNull(root, "root must not be null");

        ToolRegistry registry = new ToolRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(root);
        registerFileTools(registry, objectMapper, pathResolver);
        return registry;
    }

    public static ToolRegistry withCodingTools(Path root) {
        return withCodingTools(root, new DefaultProcessRunner());
    }

    public static ToolRegistry withCodingTools(Path root, ProcessRunner processRunner) {
        Path localRepository = root.toAbsolutePath().normalize().resolve(".m2/repository");
        return withCodingTools(root, processRunner, localRepository);
    }

    public static ToolRegistry withCodingTools(
            Path root,
            ProcessRunner processRunner,
            Path localRepository
    ) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(processRunner, "processRunner must not be null");
        Objects.requireNonNull(localRepository, "localRepository must not be null");

        ToolRegistry registry = new ToolRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(root);
        registerFileTools(registry, objectMapper, pathResolver);
        registry.register(new ApplyPatchTool(pathResolver, objectMapper));
        registry.register(new RunMavenTestTool(
                pathResolver.root(),
                processRunner,
                localRepository,
                objectMapper
        ));
        return registry;
    }

    public static ToolRegistry withActionOrientedCodingTools(
            Path root,
            ProcessRunner processRunner,
            Path localRepository
    ) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(processRunner, "processRunner must not be null");
        Objects.requireNonNull(localRepository, "localRepository must not be null");

        ToolRegistry registry = new ToolRegistry(true);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(root);
        registerFileTools(registry, objectMapper, pathResolver);
        registry.register(new ApplyPatchTool(pathResolver, objectMapper));
        registry.register(new RunMavenTestTool(
                pathResolver.root(),
                processRunner,
                localRepository,
                objectMapper
        ));
        return registry;
    }

    /**
     * CLI-only coding tools. Kept separate so frozen Benchmark baselines retain their original set.
     */
    public static ToolRegistry withCliCodingTools(
            Path root,
            ProcessRunner processRunner,
            Path localRepository
    ) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(processRunner, "processRunner must not be null");
        Objects.requireNonNull(localRepository, "localRepository must not be null");

        ToolRegistry registry = new ToolRegistry(true);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(root);
        registerFileTools(registry, objectMapper, pathResolver);
        registry.register(new ApplyPatchTool(pathResolver, objectMapper));
        registry.register(new CreateFileTool(pathResolver, objectMapper));
        registry.register(new RunMavenTestTool(
                pathResolver.root(), processRunner, localRepository, objectMapper
        ));
        return registry;
    }

    public static ToolRegistry withPreciseEditCodingTools(
            Path root,
            ProcessRunner processRunner,
            Path localRepository
    ) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(processRunner, "processRunner must not be null");
        Objects.requireNonNull(localRepository, "localRepository must not be null");

        ToolRegistry registry = new ToolRegistry(true, true);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(root);
        registerFileTools(registry, objectMapper, pathResolver);
        registry.register(new ApplyPatchTool(pathResolver, objectMapper));
        registry.register(new ReplaceLinesTool(
                pathResolver,
                objectMapper,
                AtomicTextFileWriter.utf8()
        ));
        registry.register(new RunMavenTestTool(
                pathResolver.root(), processRunner, localRepository, objectMapper
        ));
        return registry;
    }

    private static void registerFileTools(
            ToolRegistry registry,
            ObjectMapper objectMapper,
            WorkspacePathResolver pathResolver
    ) {
        registry.register(new ListFilesAdapter(pathResolver, objectMapper));
        registry.register(new ReadFileAdapter(pathResolver, objectMapper));
        registry.register(new SearchCodeAdapter(pathResolver, objectMapper));
    }

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        String name = requireNonBlank(tool.name(), "tool name");
        if (tools.putIfAbsent(name, tool) != null) {
            throw new IllegalArgumentException("Tool is already registered: " + name);
        }
    }

    public Tool getTool(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    public ToolResult execute(String name, String arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + name);
        }
        try {
            return Objects.requireNonNull(
                    tool.execute(arguments),
                    "tool result must not be null"
            );
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(
                    ToolErrorCode.INVALID_ARGUMENTS,
                    messageOrType(exception)
            );
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_ERROR,
                    messageOrType(exception)
            );
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(
                        tool.name(),
                        description(tool),
                        tool.parameters()
                ))
                .toList();
    }

    private String description(Tool tool) {
        if (!actionOrientedDescriptions) {
            return tool.description();
        }
        return switch (tool.name()) {
            case "apply_patch" -> tool.description()
                    + " This tool actually modifies an existing workspace file. Use it when the task "
                    + "requires a code change; do not merely describe the edit in the final response. "
                    + "After TEXT_NOT_FOUND or MULTIPLE_MATCHES, reread the file and construct a more "
                    + (preciseEditDescriptions
                    ? "precise patch. If exact text is unreliable or the call fails with "
                    + "TEXT_NOT_FOUND or MULTIPLE_MATCHES, reread with line numbers and use "
                    + "replace_lines instead of guessing oldText again."
                    : "precise patch instead of repeating the same call.");
            case "create_file" -> tool.description()
                    + " Use it only for a genuinely new file. For an existing file, use apply_patch; "
                    + "create_file never overwrites a path that already exists.";
            case "replace_lines" -> tool.description()
                    + " Use it for a clear local range copied from the latest line-numbered read. "
                    + "After STALE_EDIT_CONTEXT, reread the file and construct a fresh range edit.";
            case "read_file" -> tool.description()
                    + (preciseEditDescriptions
                    ? " Set includeLineNumbers=true before replace_lines so the range and "
                    + "expectedText come from current source."
                    : "");
            case "run_maven_test" -> tool.description()
                    + " Use it after modifying Java code to verify the change. TEST_FAILED is a "
                    + "recoverable observation: inspect its output, continue editing, and rerun the "
                    + "relevant class or method. A proposed fix is not verified until tests pass.";
            case "search_code" -> tool.description()
                    + " Zero matches do not prove that code is absent; try list_files, read_file, an "
                    + "alternate query, or another workspace-relative path.";
            case "list_files" -> tool.description()
                    + " Omit path or use '.' for the workspace root; do not pass a blank path.";
            default -> tool.description();
        };
    }

    private abstract static class FileToolAdapter implements Tool {
        private final WorkspacePathResolver pathResolver;

        private FileToolAdapter(WorkspacePathResolver pathResolver) {
            this.pathResolver = pathResolver;
        }

        protected Path resolve(String value) throws IOException {
            return pathResolver.resolveExisting(value);
        }

        protected ToolResult failure(IOException exception) {
            ToolErrorCode errorCode;
            if (exception instanceof WorkspaceViolationException) {
                errorCode = ToolErrorCode.WORKSPACE_VIOLATION;
            } else if (exception instanceof NoSuchFileException) {
                errorCode = ToolErrorCode.FILE_NOT_FOUND;
            } else if (exception instanceof AccessDeniedException) {
                errorCode = ToolErrorCode.ACCESS_DENIED;
            } else {
                errorCode = ToolErrorCode.TOOL_EXECUTION_ERROR;
            }
            return ToolResult.failure(errorCode, messageOrType(exception));
        }
    }

    private static final class ListFilesAdapter extends FileToolAdapter {
        private final ListFilesTool delegate = new ListFilesTool();
        private final ObjectMapper objectMapper;

        private ListFilesAdapter(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
            super(pathResolver);
            this.objectMapper = objectMapper;
        }

        @Override
        public String name() {
            return "list_files";
        }

        @Override
        public String description() {
            return "Recursively lists regular files under a path relative to the workspace root.";
        }

        @Override
        public Map<String, Object> parameters() {
            return objectSchema(
                    Map.of("path", stringProperty("Optional path relative to the workspace root.")),
                    List.of()
            );
        }

        @Override
        public ToolResult execute(String arguments) {
            try {
                JsonNode input = parseArguments(objectMapper, arguments);
                String path = input.path("path").asText(".");
                return ToolResult.success(
                        objectMapper.writeValueAsString(delegate.listFiles(resolve(path)))
                );
            } catch (IOException exception) {
                return failure(exception);
            }
        }
    }

    private static final class ReadFileAdapter extends FileToolAdapter {
        private final ReadFileTool delegate = new ReadFileTool();
        private final ObjectMapper objectMapper;

        private ReadFileAdapter(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
            super(pathResolver);
            this.objectMapper = objectMapper;
        }

        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "Reads a UTF-8 text file at a path relative to the workspace root.";
        }

        @Override
        public Map<String, Object> parameters() {
            return objectSchema(
                    Map.of(
                            "path", stringProperty("File path relative to the workspace root."),
                            "includeLineNumbers", Map.of(
                                    "type", "boolean",
                                    "description", "When true, prefix observation lines with 1-based numbers."
                            )
                    ),
                    List.of("path")
            );
        }

        @Override
        public ToolResult execute(String arguments) {
            try {
                JsonNode input = parseArguments(objectMapper, arguments);
                String path = requireNonBlank(input.path("path").asText(null), "path");
                JsonNode includeLineNumbers = input.get("includeLineNumbers");
                if (includeLineNumbers != null && !includeLineNumbers.isBoolean()) {
                    throw new IllegalArgumentException("includeLineNumbers must be a boolean");
                }
                String content = delegate.readFile(resolve(path));
                return ToolResult.success(includeLineNumbers != null && includeLineNumbers.asBoolean()
                        ? withLineNumbers(content)
                        : content);
            } catch (IOException exception) {
                return failure(exception);
            }
        }

        private static String withLineNumbers(String content) {
            String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            int count = normalized.endsWith("\n") ? lines.length - 1 : lines.length;
            StringBuilder numbered = new StringBuilder();
            for (int index = 0; index < count; index++) {
                if (index > 0) {
                    numbered.append('\n');
                }
                numbered.append(index + 1).append(" | ").append(lines[index]);
            }
            return numbered.toString();
        }
    }

    private static final class SearchCodeAdapter extends FileToolAdapter {
        private final SearchCodeTool delegate = new SearchCodeTool();
        private final ObjectMapper objectMapper;

        private SearchCodeAdapter(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
            super(pathResolver);
            this.objectMapper = objectMapper;
        }

        @Override
        public String name() {
            return "search_code";
        }

        @Override
        public String description() {
            return "Searches text files recursively for lines containing a keyword.";
        }

        @Override
        public Map<String, Object> parameters() {
            return objectSchema(
                    Map.of(
                            "keyword", stringProperty("Exact keyword to search for."),
                            "path", stringProperty("Optional path relative to the workspace root.")
                    ),
                    List.of("keyword")
            );
        }

        @Override
        public ToolResult execute(String arguments) {
            try {
                JsonNode input = parseArguments(objectMapper, arguments);
                String keyword = requireNonBlank(input.path("keyword").asText(null), "keyword");
                String path = input.path("path").asText(".");
                return ToolResult.success(
                        objectMapper.writeValueAsString(delegate.searchCode(keyword, resolve(path)))
                );
            } catch (IOException exception) {
                return failure(exception);
            }
        }
    }

    private static JsonNode parseArguments(ObjectMapper objectMapper, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", exception);
        }
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of(
                "type", "string",
                "description", description
        );
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
