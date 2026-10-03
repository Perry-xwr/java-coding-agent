package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.PlanningMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Evaluates observed workspace state and explicitly requested operations, never routing or final prose. */
public final class AdaptivePlanningEvaluator {
    public Evaluation evaluate(AdaptivePlanningTask task, Path workspace, Path fixture,
                              AgentTrajectory trajectory, AdaptivePlanningMetrics metrics) throws IOException {
        List<String> failures = new ArrayList<>();
        List<AgentStep> toolCalls = trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL).toList();
        if (metrics.toolSteps() > task.maxToolSteps()) failures.add("TOOL_STEP_LIMIT_EXCEEDED");
        if (metrics.targetDrift() > 0) failures.add("MUTATION_TARGET_DRIFT");
        if (task.requireMutation() && metrics.mutationCount() == 0) failures.add("NO_SUCCESSFUL_MUTATION");

        for (String target : task.requiredReadTargets()) {
            if (toolCalls.stream().noneMatch(step -> "read_file".equals(step.toolName())
                    && step.toolResult() != null && step.toolResult().success()
                    && target.equals(path(step)))) {
                failures.add("REQUIRED_READ_NOT_OBSERVED:" + target);
            }
        }
        for (var expected : task.expectedFileContains().entrySet()) {
            Path path = resolve(workspace, expected.getKey());
            if (!Files.isRegularFile(path)) {
                failures.add("EXPECTED_FILE_MISSING:" + expected.getKey());
                continue;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            for (String snippet : expected.getValue()) {
                if (!content.contains(snippet)) failures.add("EXPECTED_CONTENT_ABSENT:" + expected.getKey());
            }
        }
        for (String relative : task.unchangedFiles()) {
            Path actual = resolve(workspace, relative);
            Path original = resolve(fixture, relative);
            if (!Files.isRegularFile(actual) || !Files.isRegularFile(original)
                    || !Files.readString(actual, StandardCharsets.UTF_8)
                    .equals(Files.readString(original, StandardCharsets.UTF_8))) {
                failures.add("UNEXPECTED_FILE_CHANGE:" + relative);
            }
        }
        for (String relative : task.forbiddenPaths()) {
            if (Files.exists(resolve(workspace, relative))) failures.add("FORBIDDEN_PATH_EXISTS:" + relative);
        }
        if (task.requireMavenPass()) {
            int latestMutation = toolCalls.stream().filter(AdaptivePlanningEvaluator::isMutation)
                    .filter(step -> step.toolResult() != null && step.toolResult().success())
                    .mapToInt(AgentStep::stepIndex).max().orElse(0);
            if (toolCalls.stream().filter(step -> "run_maven_test".equals(step.toolName()))
                    .noneMatch(step -> step.toolResult() != null && step.toolResult().success()
                            && step.stepIndex() > latestMutation)) {
                failures.add("MAVEN_NOT_PASSED_AFTER_LATEST_MUTATION");
            }
        }

        boolean passed = failures.isEmpty();
        return new Evaluation(passed, List.copyOf(failures),
                passed ? "EXPECTED_WORKSPACE_OUTCOME" : "UNSATISFIED_WORKSPACE_OUTCOME",
                metrics.conversationalCompletion());
    }

    private static boolean isMutation(AgentStep step) {
        return switch (step.toolName() == null ? "" : step.toolName()) {
            case "create_file", "apply_patch", "insert_before", "insert_after", "replace_lines" -> true;
            default -> false;
        };
    }

    private static String path(AgentStep step) {
        Object value = step.toolResult().metadata().get("path");
        if (value == null) value = step.arguments().get("path");
        return value instanceof String text ? text.replace('\\', '/') : null;
    }

    private static Path resolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IOException("evaluator path escaped root");
        return resolved;
    }

    public record Evaluation(boolean taskSuccess, List<String> failures,
                             String workspaceOutcome, boolean conversationalCompletion) {
        public Evaluation {
            failures = List.copyOf(failures);
        }
    }
}
