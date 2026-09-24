package com.agent.tool;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RunMavenTestTool implements Tool {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 100 * 1024;

    private static final Pattern TEST_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
    );
    private static final Pattern TEST_METHOD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Set<String> ALLOWED_FIELDS = Set.of("testClass", "testMethod");

    private final Path workspaceRoot;
    private final Path localRepository;
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final MavenDiagnosticParser diagnosticParser = new MavenDiagnosticParser();

    public RunMavenTestTool(Path workspaceRoot, ProcessRunner processRunner) {
        this(
                workspaceRoot,
                processRunner,
                workspaceRoot.toAbsolutePath().normalize().resolve(".m2/repository"),
                new ObjectMapper(),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_OUTPUT_BYTES
        );
    }

    public RunMavenTestTool(
            Path workspaceRoot,
            ProcessRunner processRunner,
            Path localRepository
    ) {
        this(
                workspaceRoot,
                processRunner,
                localRepository,
                new ObjectMapper(),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_OUTPUT_BYTES
        );
    }

    RunMavenTestTool(
            Path workspaceRoot,
            ProcessRunner processRunner,
            Path localRepository,
            ObjectMapper objectMapper
    ) {
        this(
                workspaceRoot,
                processRunner,
                localRepository,
                objectMapper,
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_OUTPUT_BYTES
        );
    }

    RunMavenTestTool(
            Path workspaceRoot,
            ProcessRunner processRunner,
            Path localRepository,
            ObjectMapper objectMapper,
            Duration timeout,
            int maxOutputBytes
    ) {
        this.workspaceRoot = Objects.requireNonNull(
                workspaceRoot,
                "workspaceRoot must not be null"
        ).toAbsolutePath().normalize();
        this.localRepository = Objects.requireNonNull(
                localRepository,
                "localRepository must not be null"
        ).toAbsolutePath().normalize();
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public String name() {
        return "run_maven_test";
    }

    @Override
    public String description() {
        return "Runs the fixed Maven test goal for all tests or one validated test class/method.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "testClass", stringProperty("Optional Java test class name."),
                        "testMethod", stringProperty("Optional test method; requires testClass.")
                ),
                "required", List.of(),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        Selection selection;
        try {
            selection = parseSelection(arguments);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add(mavenExecutable());
        command.add("-Dmaven.repo.local=" + localRepository);
        if (selection.testClass() != null) {
            String selector = selection.testMethod() == null
                    ? selection.testClass()
                    : selection.testClass() + "#" + selection.testMethod();
            command.add("-Dtest=" + selector);
        }
        command.add("test");

        ProcessExecutionResult processResult;
        try {
            processResult = processRunner.run(
                    List.copyOf(command),
                    workspaceRoot,
                    timeout,
                    maxOutputBytes
            );
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolErrorCode.PROCESS_START_FAILED,
                    messageOrType(exception)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_ERROR,
                    "Maven test execution was interrupted"
            );
        }

        Map<String, Object> metadata = processMetadata(processResult, selection);
        if (processResult.timedOut()) {
            return ToolResult.failureWithOutput(
                    ToolErrorCode.PROCESS_TIMEOUT,
                    "Maven tests exceeded timeout of " + timeout.toSeconds() + " seconds",
                    processResult.output(),
                    metadata
            );
        }
        if (processResult.exitCode() != 0) {
            metadata.putAll(diagnosticParser.parse(processResult.output()).metadata());
            return ToolResult.failureWithOutput(
                    ToolErrorCode.TEST_FAILED,
                    "Maven tests failed with exit code " + processResult.exitCode(),
                    processResult.output(),
                    metadata
            );
        }
        return ToolResult.success(processResult.output(), metadata);
    }

    private Selection parseSelection(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new Selection(null, null);
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
            String testClass = optionalText(input, "testClass");
            String testMethod = optionalText(input, "testMethod");
            if (testClass != null && !TEST_CLASS.matcher(testClass).matches()) {
                throw new IllegalArgumentException("Invalid testClass");
            }
            if (testMethod != null && !TEST_METHOD.matcher(testMethod).matches()) {
                throw new IllegalArgumentException("Invalid testMethod");
            }
            if (testMethod != null && testClass == null) {
                throw new IllegalArgumentException("testMethod requires testClass");
            }
            return new Selection(testClass, testMethod);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", exception);
        }
    }

    private static String optionalText(JsonNode input, String name) {
        JsonNode value = input.get(name);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string or null");
        }
        return value.asText();
    }

    private static Map<String, Object> processMetadata(
            ProcessExecutionResult result,
            Selection selection
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exitCode", result.exitCode());
        metadata.put("durationMs", result.durationMs());
        metadata.put("timedOut", result.timedOut());
        metadata.put("outputTruncated", result.outputTruncated());
        if (selection.testClass() != null) {
            metadata.put("testClass", selection.testClass());
        }
        if (selection.testMethod() != null) {
            metadata.put("testMethod", selection.testMethod());
        }
        return metadata;
    }

    private static String mavenExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "mvn.cmd"
                : "mvn";
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

    private record Selection(String testClass, String testMethod) {
    }
}
