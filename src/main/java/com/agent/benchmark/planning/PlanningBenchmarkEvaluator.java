package com.agent.benchmark.planning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Scores observable workspace state and typed tool evidence, never final-answer prose. */
public final class PlanningBenchmarkEvaluator {
    public Evaluation evaluate(PlanningBenchmarkTask task, Path workspace, Path fixture,
                              AgentTrajectory trajectory, PlanningBenchmarkMetrics metrics) throws IOException {
        List<String> failures = new ArrayList<>();
        List<AgentStep> toolSteps = trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL).toList();
        for (String tool : task.requiredTools()) {
            if (toolSteps.stream().noneMatch(step -> tool.equals(step.toolName())
                    && step.toolResult() != null && step.toolResult().success())) {
                failures.add("missing successful required tool: " + tool);
            }
        }
        for (String target : task.requiredReadTargets()) {
            if (toolSteps.stream().noneMatch(step -> "read_file".equals(step.toolName())
                    && step.toolResult() != null && step.toolResult().success()
                    && target.equals(path(step)))) {
                failures.add("missing successful read: " + target);
            }
        }
        if (metrics.toolSteps() > task.maxToolSteps()) failures.add("tool-step limit exceeded");
        if (metrics.targetDrift() > 0) failures.add("mutation outside allowed target");
        if (task.requireSuccessfulMutation() && metrics.mutationCount() == 0) {
            failures.add("no successful workspace mutation");
        }
        for (var expected : task.expectedFileContains().entrySet()) {
            Path file = resolve(workspace, expected.getKey());
            if (!Files.isRegularFile(file)) {
                failures.add("missing expected file: " + expected.getKey());
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String text : expected.getValue()) {
                if (!content.contains(text)) failures.add("expected content absent from " + expected.getKey());
            }
        }
        for (String relative : task.unchangedFiles()) {
            Path actual = resolve(workspace, relative);
            Path original = resolve(fixture, relative);
            if (!Files.isRegularFile(actual) || !Files.isRegularFile(original)
                    || !Files.readString(actual, StandardCharsets.UTF_8)
                    .equals(Files.readString(original, StandardCharsets.UTF_8))) {
                failures.add("file changed: " + relative);
            }
        }
        for (String relative : task.forbiddenPaths()) {
            Path file = resolve(workspace, relative);
            if (Files.exists(file)) failures.add("forbidden path exists: " + relative);
        }
        if (task.requireMavenPass()) {
            int latestMutation = toolSteps.stream()
                    .filter(step -> isMutation(step.toolName()))
                    .filter(step -> step.toolResult() != null && step.toolResult().success())
                    .mapToInt(AgentStep::stepIndex).max().orElse(0);
            if (toolSteps.stream().filter(step -> "run_maven_test".equals(step.toolName()))
                    .noneMatch(step -> step.toolResult() != null && step.toolResult().success()
                            && step.stepIndex() > latestMutation)) {
                failures.add("no successful Maven verification after latest mutation");
            }
        }
        return new Evaluation(failures.isEmpty(), List.copyOf(failures), metrics.conversationalCompletion());
    }

    private static String path(AgentStep step) {
        Object value = step.toolResult().metadata().get("path");
        if (value == null) value = step.arguments().get("path");
        return value instanceof String text ? text.replace('\\', '/') : null;
    }

    private static boolean isMutation(String toolName) {
        return java.util.Set.of("create_file", "apply_patch", "insert_before", "insert_after", "replace_lines")
                .contains(toolName);
    }

    private static Path resolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IOException("evaluator path escaped workspace");
        return resolved;
    }

    public record Evaluation(boolean taskOutcomeSuccess, List<String> failures,
                             boolean conversationalCompletion) {
        public Evaluation {
            failures = List.copyOf(failures);
        }
    }
}
