package com.agent.benchmark.memory;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Workspace- and trajectory-based evaluator; final answer prose is never scored. */
public final class MemoryBenchmarkEvaluator {
    public Evaluation evaluate(
            MemoryBenchmarkTask task,
            Path workspace,
            Path fixture,
            List<MemoryRuntimeHarness.TurnResult> turns,
            MemoryBenchmarkMetrics metrics
    ) throws IOException {
        List<String> failures = new ArrayList<>();
        List<?> routes = turns.stream().map(turn -> turn.routing().mode()).toList();
        if (!routes.equals(task.expectedRoutes())) {
            failures.add("routes=" + routes);
        }
        if (task.requireCompletion() && turns.stream().anyMatch(turn -> !turn.trajectory().completed())) {
            failures.add("incomplete turn");
        }

        List<String> tools = toolSteps(turns).stream().map(AgentStep::toolName).toList();
        for (String required : task.requiredTools()) {
            if (!tools.contains(required)) {
                failures.add("missing tool " + required);
            }
        }
        List<String> successfulReads = toolSteps(turns).stream()
                .filter(step -> "read_file".equals(step.toolName()))
                .filter(step -> step.toolResult() != null && step.toolResult().success())
                .map(MemoryBenchmarkEvaluator::path)
                .toList();
        for (String required : task.requiredReadTargets()) {
            if (!successfulReads.contains(required)) {
                failures.add("missing successful read " + required);
            }
        }
        if (metrics.toolSteps() > task.maxToolSteps()) {
            failures.add("tool step cap exceeded");
        }
        if (metrics.targetDriftCount() > 0) {
            failures.add("mutation target drift=" + metrics.targetDriftCount());
        }
        if (task.ambiguityRequiresNoMutation() && metrics.mutationCount() > 0) {
            failures.add("ambiguous task mutated workspace");
        }

        for (var expected : task.expectedFileContents().entrySet()) {
            Path file = safeResolve(workspace, expected.getKey());
            if (!Files.isRegularFile(file)) {
                failures.add("missing file " + expected.getKey());
            } else if (!Files.readString(file, StandardCharsets.UTF_8).equals(expected.getValue())) {
                failures.add("unexpected content " + expected.getKey());
            }
        }
        for (String relative : task.unchangedFiles()) {
            Path actual = safeResolve(workspace, relative);
            Path original = safeResolve(fixture, relative);
            if (!Files.isRegularFile(actual) || !Files.isRegularFile(original)
                    || !Files.readString(actual, StandardCharsets.UTF_8)
                    .equals(Files.readString(original, StandardCharsets.UTF_8))) {
                failures.add("file changed " + relative);
            }
        }
        return new Evaluation(failures.isEmpty(), failures);
    }

    private static List<AgentStep> toolSteps(List<MemoryRuntimeHarness.TurnResult> turns) {
        return turns.stream().flatMap(turn -> turn.trajectory().steps().stream())
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();
    }

    private static String path(AgentStep step) {
        Object metadataPath = step.toolResult().metadata().get("path");
        Object candidate = metadataPath == null ? step.arguments().get("path") : metadataPath;
        return candidate instanceof String value ? value.replace('\\', '/') : null;
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("memory-v1 evaluator path escaped root");
        }
        return resolved;
    }

    public record Evaluation(boolean passed, List<String> failures) {
        public Evaluation {
            failures = List.copyOf(failures);
        }
    }
}
