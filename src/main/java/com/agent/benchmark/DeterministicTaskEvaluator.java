package com.agent.benchmark;

import com.agent.agent.AgentRunResult;
import com.agent.tool.RunMavenTestTool;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DeterministicTaskEvaluator implements TaskEvaluator {
    private final FixtureWorkspaceManager workspaceManager;
    private final Path hiddenRoot;
    private final Path localRepository;
    private final ProcessRunner processRunner;
    private final Map<String, EvaluationSpec> specs;

    public DeterministicTaskEvaluator(
            FixtureWorkspaceManager workspaceManager,
            Path hiddenRoot,
            Path localRepository,
            ProcessRunner processRunner,
            Map<String, EvaluationSpec> specs
    ) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager);
        this.hiddenRoot = Objects.requireNonNull(hiddenRoot).toAbsolutePath().normalize();
        this.localRepository = Objects.requireNonNull(localRepository).toAbsolutePath().normalize();
        this.processRunner = Objects.requireNonNull(processRunner);
        this.specs = Map.copyOf(Objects.requireNonNull(specs));
    }

    @Override
    public EvaluationResult evaluate(
            BenchmarkTask task,
            Path workspace,
            AgentRunResult run
    ) throws IOException {
        boolean formatValid = expectedFilesReadable(task, workspace);
        boolean requiredChange = expectedFilesChanged(task, workspace);
        boolean contentValid = evaluateContent(task, workspace);
        EvaluationSpec spec = specs.get(task.id());
        boolean strictContentRequired = spec != null && spec.strictContentCheck();

        boolean testsPassed = false;
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (task.evaluationType() != EvaluationType.FILE_CONTENT) {
            injectHiddenTests(task, workspace);
            RunMavenTestTool maven = new RunMavenTestTool(
                    workspace,
                    processRunner,
                    localRepository
            );
            String arguments = task.targetTest() == null || task.targetTest().isBlank()
                    ? "{}"
                    : "{\"testClass\":\"" + task.targetTest() + "\"}";
            ToolResult testResult = maven.execute(arguments);
            if (testResult.errorCode() == ToolErrorCode.PROCESS_START_FAILED
                    || testResult.errorCode() == ToolErrorCode.PROCESS_TIMEOUT
                    || testResult.errorCode() == ToolErrorCode.TOOL_EXECUTION_ERROR) {
                throw new IOException("Evaluator could not run Maven: " + testResult.errorMessage());
            }
            testsPassed = testResult.success();
            metrics.putAll(testResult.metadata());
        }

        boolean success = switch (task.evaluationType()) {
            case MAVEN_TEST -> testsPassed;
            case FILE_CONTENT -> formatValid && requiredChange && contentValid;
            case COMBINED -> testsPassed && formatValid && requiredChange
                    && (!strictContentRequired || contentValid);
        };
        String failure = success ? null : failureReason(
                testsPassed,
                formatValid,
                requiredChange,
                contentValid,
                task.evaluationType(),
                strictContentRequired
        );
        metrics.put("behavioralPassed", testsPassed);
        metrics.put("contentCheckPassed", contentValid);
        metrics.put("strictContentRequired", strictContentRequired);
        metrics.put("finalSuccess", success);
        metrics.put("contentValid", contentValid);
        metrics.put("agentCompleted", run.trajectory().completed());
        return new EvaluationResult(
                task.id(),
                success,
                testsPassed,
                formatValid,
                requiredChange,
                contentValid,
                strictContentRequired,
                failure,
                metrics
        );
    }

    private boolean expectedFilesReadable(BenchmarkTask task, Path workspace) {
        try {
            for (String file : task.expectedFiles()) {
                Path target = confined(workspace, file);
                if (!Files.isRegularFile(target)) {
                    return false;
                }
                Files.readString(target, StandardCharsets.UTF_8);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean expectedFilesChanged(BenchmarkTask task, Path workspace) {
        try {
            Path pristine = workspaceManager.fixture(task.fixture());
            for (String file : task.expectedFiles()) {
                Path actual = confined(workspace, file);
                Path original = confined(pristine, file);
                if (!Files.isRegularFile(actual) || !Files.isRegularFile(original)) {
                    return false;
                }
                if (Files.mismatch(actual, original) == -1) {
                    return false;
                }
            }
            return !task.expectedFiles().isEmpty();
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean evaluateContent(BenchmarkTask task, Path workspace) {
        EvaluationSpec spec = specs.get(task.id());
        if (spec == null || task.expectedFiles().isEmpty()) {
            return true;
        }
        try {
            String content = Files.readString(
                    confined(workspace, task.expectedFiles().get(0)),
                    StandardCharsets.UTF_8
            );
            return spec.requiredContains().stream().allMatch(content::contains)
                    && spec.forbiddenContains().stream().noneMatch(content::contains);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void injectHiddenTests(BenchmarkTask task, Path workspace) throws IOException {
        Path source = hiddenRoot.resolve(task.id()).normalize();
        if (!source.startsWith(hiddenRoot)) {
            throw new IOException("Hidden test path escaped root");
        }
        if (Files.isDirectory(source)) {
            FixtureWorkspaceManager.copyTree(source, workspace);
        }
    }

    private static Path confined(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Path escaped root: " + relative);
        }
        return resolved;
    }

    private static String failureReason(
            boolean testsPassed,
            boolean formatValid,
            boolean requiredChange,
            boolean contentValid,
            EvaluationType type,
            boolean strictContentRequired
    ) {
        if (!formatValid) {
            return "Expected file is missing or not valid UTF-8";
        }
        if (!requiredChange && type != EvaluationType.MAVEN_TEST) {
            return "Required change is missing";
        }
        if (!contentValid && (type == EvaluationType.FILE_CONTENT || strictContentRequired)) {
            return "Deterministic content check failed";
        }
        if (!testsPassed && type != EvaluationType.FILE_CONTENT) {
            return "Hidden Maven tests failed";
        }
        return "Evaluation failed";
    }
}
