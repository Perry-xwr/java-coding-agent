package com.agent.benchmark.editreliability;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.environment.verification.VerificationStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record EditReliabilityMetrics(String taskId, String language, EditReliabilityTaskCategory taskCategory,
                                     EditReliabilityMode mode, boolean taskSuccess, boolean workspaceOutcome,
                                     boolean conversationalCompletion, boolean finalSyntaxValid,
                                     boolean falseSuccess, boolean syntaxFalseSuccess, int providerRequests,
                                     int toolSteps, int reads, int mutations, int verificationAttempts,
                                     int verificationPasses, int verificationFailures, int verificationUnavailable,
                                     int verificationNotApplicable, int repairAttempts,
                                     boolean recoveredAfterVerificationFailure, int targetDrift,
                                     int typedFailures, int repeatedFailures, boolean maxStepTermination,
                                     boolean infrastructureError) {
    private static final Set<String> MUTATIONS = Set.of("create_file", "apply_patch", "insert_before",
            "insert_after", "replace_lines");

    public static EditReliabilityMetrics from(EditReliabilityTask task, EditReliabilityMode mode,
                                               AgentTrajectory trajectory, int providerRequests,
                                               EditReliabilityEvaluator.Evaluation evaluation) {
        int tools = 0, reads = 0, mutations = 0, attempts = 0, passes = 0, failures = 0, unavailable = 0;
        int notApplicable = 0, repairAttempts = 0, drift = 0, typed = 0, repeated = 0;
        boolean recovered = false;
        Set<String> failedVerificationPaths = new java.util.HashSet<>();
        String previousFailure = null;
        Set<String> expected = task.expectedFiles().keySet().stream().map(v -> v.replace('\\', '/'))
                .collect(java.util.stream.Collectors.toSet());
        for (AgentStep step : trajectory.steps()) {
            if (step.actionType() == AgentActionType.POST_EDIT_VERIFICATION) {
                String status = String.valueOf(step.arguments().get("status"));
                if (!VerificationStatus.NOT_APPLICABLE.name().equals(status)) attempts++;
                String file = String.valueOf(step.arguments().get("file")).replace('\\', '/');
                if (VerificationStatus.PASS.name().equals(status)) {
                    passes++;
                    if (failedVerificationPaths.remove(file)) recovered = true;
                }
                if (VerificationStatus.FAIL.name().equals(status)) { failures++; failedVerificationPaths.add(file); }
                if (VerificationStatus.UNAVAILABLE.name().equals(status)) unavailable++;
                if (VerificationStatus.NOT_APPLICABLE.name().equals(status)) notApplicable++;
            }
            if (step.actionType() != AgentActionType.TOOL_CALL) continue;
            tools++;
            if ("read_file".equals(step.toolName())) reads++;
            if (step.toolResult() != null && !step.toolResult().success()) {
                typed++;
                String failureKey = step.toolName() + step.arguments() + step.toolResult().errorCode();
                if (failureKey.equals(previousFailure)) repeated++;
                previousFailure = failureKey;
            } else previousFailure = null;
            if (MUTATIONS.contains(step.toolName()) && step.toolResult() != null && step.toolResult().success()
                    && !Boolean.FALSE.equals(step.toolResult().metadata().get("changed"))) {
                mutations++;
                Object target = step.toolResult().metadata().getOrDefault("path", step.arguments().get("path"));
                if (target instanceof String path) {
                    String normalized = path.replace('\\', '/');
                    if (!expected.contains(normalized)) drift++;
                    if (failedVerificationPaths.contains(normalized)) repairAttempts++;
                }
            }
        }
        return new EditReliabilityMetrics(task.id(), task.language(), task.category(), mode,
                evaluation.taskSuccess(), evaluation.workspaceOutcome(), evaluation.conversationalCompletion(),
                evaluation.finalSyntaxValid(), evaluation.falseSuccess(), evaluation.syntaxFalseSuccess(),
                providerRequests, tools, reads, mutations, attempts, passes, failures, unavailable, notApplicable,
                repairAttempts, recovered && failures > 0, drift, typed, repeated,
                trajectory.terminationReason().name().equals("MAX_STEPS"), evaluation.infrastructureError());
    }
}
