package com.agent.benchmark.editreliability;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.WorkspacePathResolver;
import com.agent.tool.execution.DefaultProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Independently verifies final workspace bytes; it never trusts model prose or verifier trajectory steps. */
public final class EditReliabilityEvaluator {
    public Evaluation evaluate(EditReliabilityTask task, Path workspace, AgentTrajectory trajectory) throws IOException {
        List<String> failures = new ArrayList<>();
        WorkspacePathResolver safePaths = new WorkspacePathResolver(workspace);
        for (var expected : task.expectedFiles().entrySet()) {
            Path file;
            try {
                file = safePaths.resolveExisting(expected.getKey());
            } catch (Exception exception) {
                failures.add("EXPECTED_FILE_MISSING_OR_UNSAFE:" + expected.getKey());
                continue;
            }
            if (!Files.isRegularFile(file)) {
                failures.add("EXPECTED_FILE_MISSING:" + expected.getKey());
                continue;
            }
            String content = Files.readString(file);
            for (String snippet : expected.getValue()) {
                if (!content.contains(snippet)) failures.add("EXPECTED_CONTENT_MISSING:" + expected.getKey());
            }
        }
        Set<String> expectedTargets = task.expectedFiles().keySet().stream()
                .map(value -> value.replace('\\', '/')).collect(java.util.stream.Collectors.toSet());
        List<AgentStep> mutations = trajectory.steps().stream()
                .filter(EditReliabilityEvaluator::successfulMutation).toList();
        if (mutations.isEmpty()) failures.add("NO_SUCCESSFUL_MUTATION");
        mutations.forEach(step -> {
            String path = stringPath(step);
            if (path != null && !expectedTargets.contains(path)) failures.add("TARGET_DRIFT:" + path);
        });

        PostEditVerificationService independent = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
        boolean syntaxValid = true;
        boolean syntaxFailed = false;
        boolean infrastructureError = false;
        List<String> verifierIds = new ArrayList<>();
        for (String target : task.expectedFiles().keySet()) {
            try {
                safePaths.resolveExisting(target);
            } catch (Exception exception) {
                syntaxValid = false;
                continue;
            }
            var result = independent.verify(target, 1);
            verifierIds.add(result.verifierId());
            if (result.status() == VerificationStatus.FAIL) {
                syntaxValid = false;
                syntaxFailed = true;
                failures.add("FINAL_SYNTAX_INVALID:" + target);
            } else if (result.status() == VerificationStatus.UNAVAILABLE) {
                syntaxValid = false;
                infrastructureError = true;
                failures.add("INDEPENDENT_VERIFIER_UNAVAILABLE:" + target);
            } else if (result.status() != VerificationStatus.PASS) {
                syntaxValid = false;
            }
        }
        boolean completed = trajectory.completed();
        boolean outcome = !mutations.isEmpty() && failures.stream().noneMatch(value -> value.startsWith("EXPECTED_")
                || value.startsWith("TARGET_DRIFT:") || "NO_SUCCESSFUL_MUTATION".equals(value));
        boolean taskSuccess = completed && outcome && syntaxValid && !infrastructureError;
        boolean falseSuccess = completed && (!outcome || syntaxFailed);
        return new Evaluation(task.id(), taskSuccess, completed, outcome, syntaxValid,
                falseSuccess, completed && syntaxFailed, infrastructureError, List.copyOf(failures),
                List.copyOf(verifierIds));
    }

    private static boolean successfulMutation(AgentStep step) {
        if (step.actionType() != AgentActionType.TOOL_CALL || step.toolResult() == null || !step.toolResult().success()) {
            return false;
        }
        return switch (step.toolName() == null ? "" : step.toolName()) {
            case "apply_patch", "replace_lines", "insert_before", "insert_after", "create_file" ->
                    !Boolean.FALSE.equals(step.toolResult().metadata().get("changed"));
            default -> false;
        };
    }

    private static String stringPath(AgentStep step) {
        Object path = step.toolResult().metadata().getOrDefault("path", step.arguments().get("path"));
        return path instanceof String value ? value.replace('\\', '/') : null;
    }

    public record Evaluation(String taskId, boolean taskSuccess, boolean conversationalCompletion,
                             boolean workspaceOutcome, boolean finalSyntaxValid, boolean falseSuccess,
                             boolean syntaxFalseSuccess, boolean infrastructureError, List<String> failures,
                             List<String> evaluatorVerifierIds) {
        public Evaluation { failures = List.copyOf(failures); evaluatorVerifierIds = List.copyOf(evaluatorVerifierIds); }
    }
}
