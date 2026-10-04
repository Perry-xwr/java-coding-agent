package com.agent.benchmark.repairreliability;

import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.tool.WorkspacePathResolver;
import com.agent.tool.execution.DefaultProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Independent final-state evaluator; trajectory repair events are deliberately ignored. */
public final class RepairReliabilityEvaluator {
    private final VerifierRegistry verifiers;

    public RepairReliabilityEvaluator() { this(BuiltInCodeVerifiers.registry(new DefaultProcessRunner())); }
    public RepairReliabilityEvaluator(VerifierRegistry verifiers) {
        this.verifiers = java.util.Objects.requireNonNull(verifiers);
    }

    public Evaluation evaluate(RepairReliabilityTask task, Path workspace, AgentTrajectory trajectory)
            throws IOException {
        WorkspacePathResolver paths = new WorkspacePathResolver(workspace);
        List<String> failures = new ArrayList<>();
        boolean contentValid = true;
        boolean infrastructureError = false;
        for (var expected : task.expectedFiles().entrySet()) {
            try {
                Path file = paths.resolveExisting(expected.getKey());
                String content = Files.readString(file);
                for (String snippet : expected.getValue()) {
                    if (!content.contains(snippet)) {
                        contentValid = false;
                        failures.add("EXPECTED_CONTENT_MISSING:" + expected.getKey());
                    }
                }
            } catch (Exception exception) {
                contentValid = false;
                failures.add("EXPECTED_FILE_MISSING_OR_UNSAFE:" + expected.getKey());
                if (exception instanceof IOException && !(exception instanceof java.nio.file.NoSuchFileException)) {
                    infrastructureError = true;
                    failures.add("WORKSPACE_INFRASTRUCTURE_FAILURE:" + expected.getKey());
                }
            }
        }
        Set<String> expectedTargets = task.expectedFiles().keySet().stream()
                .map(RepairReliabilityEvaluator::normalize).collect(java.util.stream.Collectors.toSet());
        List<AgentStep> mutations = trajectory.steps().stream()
                .filter(RepairReliabilityEvaluator::successfulMutation).toList();
        boolean targetDrift = mutations.stream().map(RepairReliabilityEvaluator::mutationPath)
                .filter(java.util.Objects::nonNull).anyMatch(path -> !expectedTargets.contains(path));
        if (mutations.isEmpty()) failures.add("NO_SUCCESSFUL_MUTATION");
        if (targetDrift) failures.add("TARGET_DRIFT");

        PostEditVerificationService verifier = new PostEditVerificationService(workspace, verifiers);
        VerificationStatus finalStatus = VerificationStatus.PASS;
        for (String target : task.expectedFiles().keySet()) {
            var result = verifier.verify(target, 1);
            if (result.status() == VerificationStatus.FAIL) {
                finalStatus = VerificationStatus.FAIL;
                failures.add("FINAL_SYNTAX_INVALID:" + target);
            } else if (result.status() == VerificationStatus.UNAVAILABLE) {
                if (finalStatus != VerificationStatus.FAIL) finalStatus = VerificationStatus.UNAVAILABLE;
                infrastructureError = true;
                failures.add("INDEPENDENT_VERIFIER_UNAVAILABLE:" + target);
            } else if (result.status() != VerificationStatus.PASS && finalStatus != VerificationStatus.FAIL) {
                finalStatus = VerificationStatus.UNAVAILABLE;
            }
        }
        boolean completed = trajectory.completed() && trajectory.terminationReason() == TerminationReason.FINAL_ANSWER;
        boolean outcome = contentValid && !mutations.isEmpty() && !targetDrift;
        boolean success = completed && outcome && finalStatus == VerificationStatus.PASS && !infrastructureError;
        boolean falseSuccess = completed && (!outcome || finalStatus == VerificationStatus.FAIL);
        return new Evaluation(success, completed, outcome, finalStatus, falseSuccess,
                completed && finalStatus == VerificationStatus.FAIL, infrastructureError, List.copyOf(failures));
    }

    private static boolean successfulMutation(AgentStep step) {
        return step.actionType() == com.agent.agent.AgentActionType.TOOL_CALL
                && step.toolResult() != null && step.toolResult().success()
                && Boolean.TRUE.equals(step.toolResult().metadata().get("changed"))
                && List.of("apply_patch", "replace_lines", "insert_before", "insert_after", "create_file")
                .contains(step.toolName());
    }
    private static String mutationPath(AgentStep step) {
        Object value = step.toolResult().metadata().getOrDefault("path", step.arguments().get("path"));
        return value instanceof String path ? normalize(path) : null;
    }
    private static String normalize(String value) { return value.replace('\\', '/'); }

    public record Evaluation(boolean taskSuccess, boolean conversationalCompletion, boolean workspaceOutcome,
                             VerificationStatus finalSyntaxStatus, boolean falseSuccess,
                             boolean syntaxFalseSuccess, boolean infrastructureError, List<String> failures) {
        public Evaluation { failures = List.copyOf(failures); }
    }
}
