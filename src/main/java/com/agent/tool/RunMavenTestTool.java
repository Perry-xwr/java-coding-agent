package com.agent.tool;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.agent.tool.execution.ProcessEnvironmentProfile;
import com.agent.llm.LiveTestControl;
import com.agent.execution.secure.ApprovalDecision;
import com.agent.execution.secure.ExecutionApprovalPrompt;
import com.agent.execution.secure.ExecutionRequest;
import com.agent.execution.secure.HostMavenExecutionApproval;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.stream.Stream;
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
    private final Function<ExecutionApprovalPrompt, ApprovalDecision> approvalRequester;

    public RunMavenTestTool(Path workspaceRoot, ProcessRunner processRunner) {
        this(
                workspaceRoot,
                processRunner,
                workspaceRoot.toAbsolutePath().normalize().resolve(".m2/repository"),
                new ObjectMapper(),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_OUTPUT_BYTES,
                HostMavenExecutionApproval::request
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
                DEFAULT_MAX_OUTPUT_BYTES,
                HostMavenExecutionApproval::request
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
                DEFAULT_MAX_OUTPUT_BYTES,
                HostMavenExecutionApproval::request
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
        this(workspaceRoot, processRunner, localRepository, objectMapper, timeout, maxOutputBytes,
                HostMavenExecutionApproval::request);
    }

    RunMavenTestTool(
            Path workspaceRoot,
            ProcessRunner processRunner,
            Path localRepository,
            ObjectMapper objectMapper,
            Duration timeout,
            int maxOutputBytes,
            Function<ExecutionApprovalPrompt, ApprovalDecision> approvalRequester
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
        this.approvalRequester = Objects.requireNonNull(approvalRequester, "approvalRequester must not be null");
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

        String selectedTest = selection.testClass() == null ? "all tests"
                : selection.testClass() + (selection.testMethod() == null ? "" : "#" + selection.testMethod());
        String workspaceSnapshot;
        try {
            workspaceSnapshot = workspaceSnapshotDigest();
        } catch (IOException unsafeWorkspace) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR,
                    "Maven approval unavailable: workspace could not be safely fingerprinted; no process was started",
                    Map.of("testStatus", "APPROVAL_UNAVAILABLE", "approvalStatus", "UNAVAILABLE",
                            "executed", false, "testSummaryAvailable", false));
        }
        String digest = requestDigest(command, selectedTest, timeout, maxOutputBytes, workspaceSnapshot);
        String challenge = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        ExecutionApprovalPrompt approvalPrompt = new ExecutionApprovalPrompt(digest, challenge,
                ExecutionRequest.Action.MAVEN_TEST,
                "Windows host workspace=" + workspaceRoot + "; command=" + command,
                selectedTest, "Host process; no container",
                "Direct Windows host process; allowlisted environment only; no OS sandbox or network isolation; "
                        + "timeout=" + timeout.toSeconds() + "s; max output=" + maxOutputBytes + " bytes",
                "Maven build plugins and tests execute project code with the current Windows user permissions "
                        + "and may access user files or the network.");
        ApprovalDecision approval;
        try {
            approval = approvalRequester.apply(approvalPrompt);
        } catch (RuntimeException approvalFailure) {
            approval = new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE, digest, "");
        }
        if (approval == null || approval.status() == ApprovalDecision.Status.UNAVAILABLE) {
            return approvalFailure(ToolErrorCode.APPROVAL_UNAVAILABLE, "APPROVAL_UNAVAILABLE",
                    "Maven execution approval is unavailable; no process was started", digest);
        }
        if (approval.status() == ApprovalDecision.Status.DENIED
                || !digest.equals(approval.requestDigest()) || approval.approvalId().isBlank()) {
            return approvalFailure(ToolErrorCode.APPROVAL_DENIED, "APPROVAL_DENIED",
                    "Maven execution was denied; no process was started", digest);
        }
        try {
            if (!workspaceSnapshot.equals(workspaceSnapshotDigest())) {
                return approvalFailure(ToolErrorCode.APPROVAL_DENIED, "APPROVAL_DENIED",
                        "Workspace changed after approval; request approval again. No process was started", digest);
            }
        } catch (IOException unsafeWorkspace) {
            return approvalFailure(ToolErrorCode.APPROVAL_UNAVAILABLE, "APPROVAL_UNAVAILABLE",
                    "Workspace could not be revalidated after approval; no process was started", digest);
        }

        ProcessExecutionResult processResult;
        Duration effectiveTimeout = LiveTestControl.boundedProcessTimeout(timeout);
        if (effectiveTimeout != null && effectiveTimeout.isZero()) {
            return ToolResult.failure(ToolErrorCode.PROCESS_TIMEOUT,
                    "Live-test deadline is too close to safely start Maven",
                    Map.of("testStatus", "TIMED_OUT", "approvalStatus", "APPROVED",
                            "approvalId", approval.approvalId(), "executed", false,
                            "requestDigest", digest));
        }
        if (effectiveTimeout == null) effectiveTimeout = timeout;
        try {
            processResult = processRunner.run(
                    List.copyOf(command),
                    workspaceRoot,
                    effectiveTimeout,
                    maxOutputBytes,
                    Map.of(),
                    ProcessEnvironmentProfile.MAVEN
            );
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolErrorCode.PROCESS_START_FAILED,
                    messageOrType(exception),
                    Map.of("testStatus", "PROCESS_START_FAILED", "approvalStatus", "APPROVED",
                            "approvalId", approval.approvalId(), "executed", false,
                            "requestDigest", digest)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(
                    ToolErrorCode.PROCESS_CANCELLED,
                    "Maven test execution was interrupted",
                    Map.of("testStatus", "CANCELLED", "approvalStatus", "APPROVED",
                            "approvalId", approval.approvalId(), "executed", true,
                            "requestDigest", digest)
            );
        }

        MavenTestSummary summary = MavenTestSummary.parse(processResult.output());
        String testStatus = testStatus(processResult, summary);
        Map<String, Object> metadata = processMetadata(processResult, selection, approval, digest, summary,
                testStatus);
        if (processResult.timedOut()) {
            return ToolResult.failureWithOutput(
                    ToolErrorCode.PROCESS_TIMEOUT,
                    "Maven tests exceeded timeout of " + effectiveTimeout.toSeconds() + " seconds",
                    processResult.output(),
                    metadata
            );
        }
        if ("TEST_PASSED".equals(testStatus)) {
            return ToolResult.success(processResult.output(), metadata);
        }
        if ("BUILD_FAILED".equals(testStatus) || "TEST_FAILED".equals(testStatus)
                || "TEST_RUNTIME_ERROR".equals(testStatus)
                || "DEPENDENCY_UNAVAILABLE".equals(testStatus)
                || "BUILD_CONFIGURATION_FAILURE".equals(testStatus)
                || processResult.exitCode() != 0) {
            metadata.putAll(diagnosticParser.parse(processResult.output()).metadata());
            if ("TEST_RUNTIME_ERROR".equals(testStatus)) {
                metadata.put("diagnosticType", DiagnosticType.TEST_RUNTIME.name());
                metadata.put("diagnosticSummary", "One or more tests ended with runtime errors");
            }
            List<String> failedTests = diagnosticParser.failureIdentifiers(processResult.output());
            if (!failedTests.isEmpty()) metadata.put("failedTests", failedTests);
        }
        return switch (testStatus) {
            case "TEST_FAILED" -> ToolResult.failureWithOutput(ToolErrorCode.TEST_FAILED,
                    "Maven tests failed", processResult.output(), metadata);
            case "TEST_RUNTIME_ERROR" -> ToolResult.failureWithOutput(ToolErrorCode.TEST_RUNTIME_ERROR,
                    "Maven tests reported runtime errors", processResult.output(), metadata);
            case "BUILD_FAILED" -> ToolResult.failureWithOutput(ToolErrorCode.BUILD_FAILED,
                    "Maven build failed before reliable test completion", processResult.output(), metadata);
            case "DEPENDENCY_UNAVAILABLE" -> ToolResult.failureWithOutput(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Maven dependencies or repository were unavailable", processResult.output(), metadata);
            case "BUILD_CONFIGURATION_FAILURE" -> ToolResult.failureWithOutput(
                    ToolErrorCode.BUILD_CONFIGURATION_FAILURE,
                    "Maven build configuration or plugin setup failed", processResult.output(), metadata);
            case "TESTS_SKIPPED" -> ToolResult.failureWithOutput(ToolErrorCode.TESTS_SKIPPED,
                    "Maven completed but all reported tests were skipped", processResult.output(), metadata);
            default -> ToolResult.failureWithOutput(ToolErrorCode.TEST_SUMMARY_UNAVAILABLE,
                    "Maven did not provide a reliable test summary; test success is unknown",
                    processResult.output(), metadata);
        };
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
            Selection selection,
            ApprovalDecision approval,
            String digest,
            MavenTestSummary summary,
            String testStatus
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exitCode", result.exitCode());
        metadata.put("durationMs", result.durationMs());
        metadata.put("timedOut", result.timedOut());
        metadata.put("outputTruncated", result.outputTruncated());
        metadata.put("approvalStatus", approval.status().name());
        metadata.put("approvalId", approval.approvalId());
        metadata.put("requestDigest", digest);
        metadata.put("executed", true);
        metadata.put("testStatus", testStatus);
        metadata.put("testSummaryAvailable", summary.available());
        if (summary.available()) {
            metadata.put("testsRun", summary.total());
            metadata.put("testsPassed", summary.passed());
            metadata.put("testsFailed", summary.failures());
            metadata.put("testsErrors", summary.errors());
            metadata.put("testsSkipped", summary.skipped());
        }
        if (selection.testClass() != null) {
            metadata.put("testClass", selection.testClass());
        }
        if (selection.testMethod() != null) {
            metadata.put("testMethod", selection.testMethod());
        }
        return metadata;
    }

    private static ToolResult approvalFailure(ToolErrorCode code, String status, String message, String digest) {
        return ToolResult.failure(code, message, Map.of("testStatus", status,
                "approvalStatus", status.equals("APPROVAL_DENIED") ? "DENIED" : "UNAVAILABLE",
                "executed", false, "testSummaryAvailable", false, "requestDigest", digest));
    }

    private static String testStatus(ProcessExecutionResult result, MavenTestSummary summary) {
        if (result.timedOut()) return "TIMED_OUT";
        MavenDiagnostic diagnostic = new MavenDiagnosticParser().parse(result.output());
        if (summary.available()) {
            if (summary.errors() > 0) return "TEST_RUNTIME_ERROR";
            if (summary.failures() > 0) return "TEST_FAILED";
            if (summary.total() == 0) return "TEST_SUMMARY_UNAVAILABLE";
            if (summary.skipped() == summary.total()) return "TESTS_SKIPPED";
            if (result.exitCode() == 0 && summary.passed() > 0 && !result.outputTruncated()) return "TEST_PASSED";
            if (result.exitCode() != 0) {
                if (diagnostic.type() == DiagnosticType.DEPENDENCY) return "DEPENDENCY_UNAVAILABLE";
                if (diagnostic.type() == DiagnosticType.BUILD_CONFIGURATION) return "BUILD_CONFIGURATION_FAILURE";
                return "BUILD_FAILED";
            }
        } else if (result.exitCode() != 0) {
            if (diagnostic.type() == DiagnosticType.DEPENDENCY) return "DEPENDENCY_UNAVAILABLE";
            if (diagnostic.type() == DiagnosticType.BUILD_CONFIGURATION) return "BUILD_CONFIGURATION_FAILURE";
            DiagnosticType type = diagnostic.type();
            if (type == DiagnosticType.COMPILATION || type == DiagnosticType.SYNTAX) return "BUILD_FAILED";
            if (type == DiagnosticType.ASSERTION) return "TEST_FAILED";
            if (type == DiagnosticType.DEPENDENCY) return "DEPENDENCY_UNAVAILABLE";
            if (type == DiagnosticType.BUILD_CONFIGURATION) return "BUILD_CONFIGURATION_FAILURE";
            if (type == DiagnosticType.TEST_RUNTIME) return "TEST_RUNTIME_ERROR";
        }
        return "TEST_SUMMARY_UNAVAILABLE";
    }

    private String requestDigest(List<String> command, String selectedTest, Duration configuredTimeout,
                                 int outputLimit, String workspaceSnapshot) {
        String canonical = "run_maven_test\n" + workspaceRoot + "\n" + command + "\n" + selectedTest
                + "\n" + configuredTimeout.toMillis() + "\n" + outputLimit
                + "\nMAVEN_HOST_ALLOWLIST_PROFILE_V1\n" + workspaceSnapshot;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String workspaceSnapshotDigest() throws IOException {
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(workspaceRoot)) {
            throw new IOException("Workspace root is not a safe directory");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        List<Path> files;
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            files = paths.filter(path -> !path.equals(workspaceRoot))
                    .filter(path -> !isExcludedVcsOrRepositoryPath(path))
                    .limit(20_001)
                    .sorted(Comparator.comparing(path -> workspaceRoot.relativize(path).toString()))
                    .toList();
        }
        if (files.size() > 20_000) throw new IOException("Workspace has too many entries to fingerprint safely");
        long totalBytes = 0;
        for (Path path : files) {
            if (Files.isSymbolicLink(path)) throw new IOException("Workspace contains a symbolic link");
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (attributes.isDirectory()) {
                digest.update((byte) 'D');
                continue;
            }
            if (!attributes.isRegularFile()) throw new IOException("Workspace contains a non-regular file");
            totalBytes = Math.addExact(totalBytes, attributes.size());
            if (totalBytes > 128L * 1024 * 1024) {
                throw new IOException("Workspace exceeds the fingerprint size limit");
            }
            digest.update((byte) 'F');
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean isExcludedVcsOrRepositoryPath(Path path) {
        Path relative = workspaceRoot.relativize(path);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.equals(".git") || name.equals(".m2")) return true;
        }
        return false;
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
