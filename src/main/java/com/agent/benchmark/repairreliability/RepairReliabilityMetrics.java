package com.agent.benchmark.repairreliability;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolErrorCode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Observable run metrics derived from trajectory and independent evaluation. */
public record RepairReliabilityMetrics(
        String taskId, String language, String taskCategory, RepairReliabilityMode mode,
        boolean taskSuccess, boolean workspaceOutcome, boolean conversationalCompletion,
        VerificationStatus finalSyntaxStatus, boolean falseSuccess, boolean syntaxFalseSuccess,
        int providerRequests, int toolSteps, int reads, int mutations,
        int verificationAttempts, int verificationFailures, int verificationPasses, int verificationUnavailable,
        boolean repairEligibleRun, boolean recoveredRun, int filesWithVerificationFailure, int freshReadsAfterFailure,
        int repairAttemptWithoutFreshRead, int mutationAfterRequiredFreshRead, int repairGuardRejections,
        int repairMutations, int successfulRecoveries, int repeatedVerificationFailures,
        int unresolvedFailuresAtEnd, int diagnosticHadLocation, boolean diagnosticLocationAvailable,
        int repairTargetedSameFailedFile,
        int toolSelectionMismatch, int targetDrift, int typedFailures, int repeatedToolFailures,
        boolean requestCapHit, boolean infrastructureError, String infrastructureErrorCategory
) {
    public static RepairReliabilityMetrics from(RepairReliabilityTask task, RepairReliabilityMode mode,
                                                AgentTrajectory trajectory, int requests,
                                                RepairReliabilityEvaluator.Evaluation evaluation,
                                                boolean mavenWorkspace, boolean providerInfrastructureError) {
        List<AgentStep> steps = trajectory.steps();
        Map<String, Integer> failAt = new HashMap<>();
        Map<String, Integer> failCounts = new HashMap<>();
        Map<String, Integer> readAt = new HashMap<>();
        Map<String, Integer> repairMutationAt = new HashMap<>();
        Set<String> recovered = new HashSet<>();
        Set<String> failedFiles = new HashSet<>();
        int verifyAttempts = 0, failures = 0, passes = 0, unavailable = 0, reads = 0, mutations = 0;
        int freshReads = 0, blindAttempts = 0, afterFresh = 0, guardRejects = 0, repairMutations = 0;
        int diagnosticLocations = 0, sameFileRepairs = 0, toolCalls = 0, typedFailures = 0;
        int targetDrift = 0, mavenMismatch = 0, repeatedToolFailures = 0;
        boolean requestCapHit = false;
        Map<String, Integer> toolFailureCounts = new HashMap<>();
        Set<String> expectedTargets = task.expectedFiles().keySet().stream().map(RepairReliabilityMetrics::norm)
                .collect(java.util.stream.Collectors.toSet());
        for (AgentStep step : steps) {
            if (step.errorMessage() != null && step.errorMessage().contains("BUDGET_CAP_REACHED")) {
                requestCapHit = true;
            }
            if (step.actionType() == AgentActionType.POST_EDIT_VERIFICATION) {
                verifyAttempts++;
                String file = norm(String.valueOf(step.arguments().get("file")));
                String status = String.valueOf(step.arguments().get("status"));
                if (VerificationStatus.FAIL.name().equals(status)) {
                    failures++;
                    failedFiles.add(file);
                    failCounts.merge(file, 1, Integer::sum);
                    failAt.put(file, step.stepIndex());
                    repairMutationAt.remove(file);
                    if (step.errorMessage() != null && step.errorMessage().matches(
                            "(?is).*(?:line\\s+\\d+|:\\d+(?::\\d+)?\\b).*")) {
                        diagnosticLocations++;
                    }
                } else if (VerificationStatus.PASS.name().equals(status)) {
                    passes++;
                    Integer mutationStep = repairMutationAt.get(file);
                    if (mutationStep != null && mutationStep < step.stepIndex()
                            && failAt.getOrDefault(file, Integer.MAX_VALUE) < mutationStep) {
                        recovered.add(file);
                        failAt.remove(file);
                        repairMutationAt.remove(file);
                    }
                } else if (VerificationStatus.UNAVAILABLE.name().equals(status)) unavailable++;
            }
            if (step.actionType() != AgentActionType.TOOL_CALL || step.toolName() == null) continue;
            toolCalls++;
            if (step.toolResult() != null && !step.toolResult().success()) {
                if (step.toolResult().errorCode() != null) typedFailures++;
                if (step.toolResult().errorCode() == ToolErrorCode.REPAIR_REQUIRES_FRESH_READ) guardRejects++;
                String failureKey = step.toolName() + "|" + step.toolResult().errorCode();
                if (toolFailureCounts.merge(failureKey, 1, Integer::sum) > 1) repeatedToolFailures++;
            }
            if ("read_file".equals(step.toolName())) {
                reads++;
                if (step.toolResult() != null && step.toolResult().success()) {
                    String file = path(step);
                    if (file != null && failAt.containsKey(file) && step.stepIndex() > failAt.get(file)) {
                        readAt.put(file, step.stepIndex());
                        freshReads++;
                    }
                }
            }
            if ("run_maven_test".equals(step.toolName()) && !mavenWorkspace) mavenMismatch++;
            if (isMutation(step.toolName()) && step.toolResult() != null && step.toolResult().success()
                    && Boolean.TRUE.equals(step.toolResult().metadata().get("changed"))) {
                mutations++;
                String file = path(step);
                if (file != null && !expectedTargets.contains(file)) targetDrift++;
                if (file != null && failAt.containsKey(file)) {
                    repairMutations++;
                    sameFileRepairs++;
                    repairMutationAt.put(file, step.stepIndex());
                    if (readAt.getOrDefault(file, 0) > failAt.get(file)) afterFresh++;
                    else blindAttempts++;
                } else if (file != null && failAt.keySet().stream().anyMatch(failed -> !failed.equals(file))) {
                    // A change to another file never recovers a failed file.
                }
            } else if (isMutation(step.toolName()) && failAt.containsKey(path(step))
                    && readAt.getOrDefault(path(step), 0) <= failAt.get(path(step))) {
                blindAttempts++;
            }
        }
        int repeatedFailures = failCounts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
        int unresolved = failAt.size();
        int finalTargetDrift = (int) evaluation.failures().stream().filter(value -> value.startsWith("TARGET_DRIFT"))
                .count();
        return new RepairReliabilityMetrics(task.id(), task.language(), task.category(), mode,
                evaluation.taskSuccess(), evaluation.workspaceOutcome(), evaluation.conversationalCompletion(),
                evaluation.finalSyntaxStatus(), evaluation.falseSuccess(), evaluation.syntaxFalseSuccess(),
                requests, toolCalls, reads, mutations, verifyAttempts, failures, passes, unavailable,
                failures > 0, failures > 0 && unresolved == 0 && !recovered.isEmpty(),
                failedFiles.size(), freshReads,
                blindAttempts, afterFresh, guardRejects,
                repairMutations, recovered.size(), repeatedFailures, unresolved, diagnosticLocations,
                diagnosticLocations > 0,
                sameFileRepairs, mavenMismatch, targetDrift + finalTargetDrift, typedFailures,
                repeatedToolFailures, requestCapHit,
                evaluation.infrastructureError() || providerInfrastructureError,
                infrastructureCategory(steps, evaluation, providerInfrastructureError));
    }

    private static String infrastructureCategory(List<AgentStep> steps,
                                                  RepairReliabilityEvaluator.Evaluation evaluation,
                                                  boolean providerInfrastructureError) {
        if (providerInfrastructureError) return "PROVIDER_TRANSPORT";
        if (evaluation.failures().stream().anyMatch(failure ->
                failure.startsWith("INDEPENDENT_VERIFIER_UNAVAILABLE"))) {
            return "EVALUATOR_VERIFIER_UNAVAILABLE";
        }
        if (evaluation.failures().stream().anyMatch(failure ->
                failure.startsWith("WORKSPACE_INFRASTRUCTURE_FAILURE"))) {
            return "WORKSPACE_RUNTIME";
        }
        boolean verifierUnavailable = steps.stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && VerificationStatus.UNAVAILABLE.name().equals(step.arguments().get("status")));
        if (evaluation.infrastructureError() && verifierUnavailable) return "VERIFIER_UNAVAILABLE";
        return evaluation.infrastructureError() ? "EVALUATOR_OR_WORKSPACE_FAILURE" : "NONE";
    }

    private static String path(AgentStep step) {
        Object value = step.toolResult() == null ? step.arguments().get("path")
                : step.toolResult().metadata().getOrDefault("path", step.arguments().get("path"));
        return value instanceof String text ? norm(text) : null;
    }
    private static String norm(String path) { return path.replace('\\', '/'); }
    private static boolean isMutation(String name) {
        return List.of("apply_patch", "replace_lines", "insert_before", "insert_after", "create_file").contains(name);
    }
}
