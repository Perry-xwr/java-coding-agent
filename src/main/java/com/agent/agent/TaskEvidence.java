package com.agent.agent;

import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A read-only, privacy-minimal assessment derived from the actual agent trajectory. */
public record TaskEvidence(
        ExecutionStatus executionStatus,
        MutationStatus mutationStatus,
        EvidenceStatus verificationStatus,
        VerificationKind verificationKind,
        String verificationSource,
        EvidenceStatus functionalVerification,
        CompletionAssessment completionAssessment,
        boolean modelFinalAnswerGenerated,
        List<EvidenceSource> evidenceSources,
        List<TaskChangeReview.FileChange> changedFiles,
        List<CommandExecutionEvidence> commandExecutionEvidence
) {
    private static final List<String> MUTATION_TOOLS = List.of(
            "create_file", "apply_patch", "apply_context_patch", "replace_lines", "insert_before", "insert_after"
    );

    public TaskEvidence {
        Objects.requireNonNull(executionStatus);
        Objects.requireNonNull(mutationStatus);
        Objects.requireNonNull(verificationStatus);
        Objects.requireNonNull(verificationKind);
        Objects.requireNonNull(functionalVerification);
        Objects.requireNonNull(completionAssessment);
        evidenceSources = List.copyOf(evidenceSources);
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        commandExecutionEvidence = commandExecutionEvidence == null ? List.of() : List.copyOf(commandExecutionEvidence);
    }

    public TaskEvidence(ExecutionStatus executionStatus, MutationStatus mutationStatus,
                        EvidenceStatus verificationStatus, VerificationKind verificationKind,
                        String verificationSource, EvidenceStatus functionalVerification,
                        CompletionAssessment completionAssessment, boolean modelFinalAnswerGenerated,
                        List<EvidenceSource> evidenceSources, List<TaskChangeReview.FileChange> changedFiles) {
        this(executionStatus, mutationStatus, verificationStatus, verificationKind, verificationSource,
                functionalVerification, completionAssessment, modelFinalAnswerGenerated, evidenceSources,
                changedFiles, List.of());
    }

    public TaskEvidence(ExecutionStatus executionStatus, MutationStatus mutationStatus,
                        EvidenceStatus verificationStatus, VerificationKind verificationKind,
                        String verificationSource, EvidenceStatus functionalVerification,
                        CompletionAssessment completionAssessment, boolean modelFinalAnswerGenerated,
                        List<EvidenceSource> evidenceSources) {
        this(executionStatus, mutationStatus, verificationStatus, verificationKind, verificationSource,
                functionalVerification, completionAssessment, modelFinalAnswerGenerated, evidenceSources, List.of(), List.of());
    }

    public static TaskEvidence from(AgentTrajectory trajectory) {
        return from(trajectory, TaskChangeReview.empty(trajectory.runId()));
    }

    public static TaskEvidence from(AgentTrajectory trajectory, TaskChangeReview changeReview) {
        Objects.requireNonNull(trajectory, "trajectory");
        int successfulMutations = 0;
        int failedMutations = 0;
        boolean finalAnswer = false;
        Map<String, VerificationEvidence> verificationByTarget = new java.util.LinkedHashMap<>();
        FunctionalEvidence functional = new FunctionalEvidence(EvidenceStatus.NOT_RUN, null);
        List<EvidenceSource> sources = new ArrayList<>();

        for (AgentStep step : trajectory.steps()) {
            if (step.actionType() == AgentActionType.FINAL_ANSWER
                    && "MODEL".equals(step.arguments().get("origin"))) finalAnswer = true;
            if (step.actionType() == AgentActionType.POST_EDIT_VERIFICATION) {
                VerificationEvidence evidence = verification(step);
                if (evidence != null) {
                    String target = Objects.toString(step.arguments().get("file"), "unknown-target");
                    verificationByTarget.put(target + "\u0000" + evidence.source(), evidence);
                    sources.add(new EvidenceSource(step.stepIndex(), "VERIFICATION_EVENT",
                            evidence.kind().name(), evidence.source(), evidence.status()));
                }
            }
            if (step.actionType() != AgentActionType.TOOL_CALL || step.toolName() == null) continue;
            ToolResult result = step.toolResult();
            if (result != null) {
                sources.add(new EvidenceSource(step.stepIndex(), "TOOL_RESULT", step.toolName(),
                        step.toolCallId(), result.success() ? EvidenceStatus.PASS : EvidenceStatus.FAIL));
            }
            if (MUTATION_TOOLS.contains(step.toolName())) {
                if (result != null && result.success()
                        && Boolean.TRUE.equals(result.metadata().get("changed"))) {
                    successfulMutations++;
                    // A later edit invalidates prior functional-test evidence for the current workspace state.
                    functional = new FunctionalEvidence(EvidenceStatus.NOT_RUN, null);
                    sources.add(new EvidenceSource(step.stepIndex(), "WORKSPACE_MUTATION", step.toolName(),
                            step.toolCallId(), EvidenceStatus.PASS));
                } else if (result == null || !result.success()) {
                    failedMutations++;
                    sources.add(new EvidenceSource(step.stepIndex(), "WORKSPACE_MUTATION", step.toolName(),
                            step.toolCallId(), EvidenceStatus.FAIL));
                }
            }
            if ("run_maven_test".equals(step.toolName())) {
                String approval = Objects.toString(result == null ? null
                        : result.metadata().get("approvalStatus"), "UNAVAILABLE");
                EvidenceStatus approvalEvidence = "APPROVED".equals(approval) ? EvidenceStatus.PASS
                        : "DENIED".equals(approval) ? EvidenceStatus.FAIL
                        : "NOT_REQUESTED".equals(approval) ? EvidenceStatus.NOT_RUN : EvidenceStatus.UNAVAILABLE;
                sources.add(new EvidenceSource(step.stepIndex(), "MAVEN_APPROVAL", "run_maven_test",
                        step.toolCallId(), approvalEvidence));
                if (result != null && "APPROVED".equals(approval)
                        && Boolean.TRUE.equals(result.metadata().get("executed"))) {
                    sources.add(new EvidenceSource(step.stepIndex(), "MAVEN_PROCESS_EXECUTION",
                            "run_maven_test", step.toolCallId(), EvidenceStatus.PASS));
                }
                if (result != null && Boolean.TRUE.equals(result.metadata().get("executed"))) {
                    functional = functionalEvidence(step, result);
                }
                sources.add(new EvidenceSource(step.stepIndex(), "FUNCTIONAL_TEST_RESULT", step.toolName(),
                        step.toolCallId(), result != null && Boolean.TRUE.equals(result.metadata().get("executed"))
                                ? functionalEvidence(step, result).status() : EvidenceStatus.NOT_RUN));
            }
        }

        ExecutionStatus execution = executionStatus(trajectory);
        MutationStatus mutation = mutationStatus(successfulMutations, failedMutations);
        List<VerificationEvidence> currentVerifications = List.copyOf(verificationByTarget.values());
        EvidenceStatus verificationStatus = aggregateVerification(currentVerifications);
        VerificationKind verificationKind = aggregateKind(currentVerifications);
        String verificationSource = currentVerifications.isEmpty() ? null : currentVerifications.stream()
                .map(VerificationEvidence::source).distinct().reduce((left, right) -> left + "," + right).orElse(null);
        CompletionAssessment assessment = assessment(execution, mutation, verificationStatus,
                functional.status(), finalAnswer);
        return new TaskEvidence(execution, mutation, verificationStatus, verificationKind,
                verificationSource, functional.status(), assessment, finalAnswer, sources,
                changeReview.changedFiles(), List.of());
    }

    /** Adds a typed event emitted by a secure execution adapter; approval never implies execution. */
    public TaskEvidence withCommandExecutionEvidence(CommandExecutionEvidence event) {
        List<CommandExecutionEvidence> updated = new ArrayList<>(commandExecutionEvidence);
        updated.add(Objects.requireNonNull(event));
        return new TaskEvidence(executionStatus, mutationStatus, verificationStatus, verificationKind,
                verificationSource, functionalVerification, completionAssessment, modelFinalAnswerGenerated,
                evidenceSources, changedFiles, updated);
    }

    private static ExecutionStatus executionStatus(AgentTrajectory trajectory) {
        return switch (trajectory.terminationReason()) {
            case REQUEST_BUDGET_EXCEEDED -> ExecutionStatus.REQUEST_BUDGET_EXCEEDED;
            case TASK_TIMEOUT -> ExecutionStatus.TIMED_OUT;
            case MAX_STEPS -> ExecutionStatus.MAX_STEPS;
            case LLM_ERROR -> ExecutionStatus.FAILED;
            case DIAGNOSTICS_FAILURE -> ExecutionStatus.DIAGNOSTICS_FAILURE;
            case FINAL_ANSWER -> trajectory.completed() ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
        };
    }

    private static MutationStatus mutationStatus(int successes, int failures) {
        if (successes > 0 && failures > 0) return MutationStatus.PARTIAL;
        if (successes > 0) return MutationStatus.SUCCESS;
        if (failures > 0) return MutationStatus.FAILED;
        return MutationStatus.NOT_MODIFIED;
    }

    private static VerificationEvidence verification(AgentStep step) {
        Map<String, Object> metadata = step.arguments();
        Object rawStatus = metadata.get("status");
        Object rawVerifier = metadata.get("verifier");
        if (!(rawStatus instanceof String statusText) || !(rawVerifier instanceof String verifier)) return null;
        EvidenceStatus status;
        try {
            VerificationStatus parsed = VerificationStatus.valueOf(statusText);
            status = switch (parsed) {
                case PASS -> EvidenceStatus.PASS;
                case FAIL -> EvidenceStatus.FAIL;
                case UNAVAILABLE -> EvidenceStatus.UNAVAILABLE;
                case NOT_APPLICABLE -> EvidenceStatus.NOT_RUN;
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (status == EvidenceStatus.NOT_RUN) return null;
        return new VerificationEvidence(status, verificationKind(verifier), verifier);
    }

    private static VerificationKind verificationKind(String verifier) {
        String id = verifier.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("python") || id.contains("syntax") || id.contains("javac")) {
            return id.contains("javac") ? VerificationKind.COMPILATION : VerificationKind.SYNTAX;
        }
        if (id.contains("maven")) return VerificationKind.BUILD;
        return VerificationKind.OTHER;
    }

    private static EvidenceStatus aggregateVerification(List<VerificationEvidence> verifications) {
        if (verifications.isEmpty()) return EvidenceStatus.NOT_RUN;
        if (verifications.stream().anyMatch(item -> item.status() == EvidenceStatus.FAIL)) return EvidenceStatus.FAIL;
        if (verifications.stream().anyMatch(item -> item.status() == EvidenceStatus.UNAVAILABLE)) {
            return EvidenceStatus.UNAVAILABLE;
        }
        return EvidenceStatus.PASS;
    }

    private static VerificationKind aggregateKind(List<VerificationEvidence> verifications) {
        if (verifications.isEmpty()) return VerificationKind.NONE;
        List<VerificationKind> kinds = verifications.stream().map(VerificationEvidence::kind).distinct().toList();
        return kinds.size() == 1 ? kinds.get(0) : VerificationKind.MIXED;
    }

    private static FunctionalEvidence functionalEvidence(AgentStep step, ToolResult result) {
        if (result == null) return new FunctionalEvidence(EvidenceStatus.UNAVAILABLE, source(step));
        if (!Boolean.TRUE.equals(result.metadata().get("executed"))) {
            return new FunctionalEvidence(EvidenceStatus.NOT_RUN, source(step));
        }
        if (!"APPROVED".equals(result.metadata().get("approvalStatus"))) {
            return new FunctionalEvidence(EvidenceStatus.UNAVAILABLE, source(step));
        }
        String testStatus = Objects.toString(result.metadata().get("testStatus"), "");
        boolean summaryAvailable = Boolean.TRUE.equals(result.metadata().get("testSummaryAvailable"));
        int passed = integer(result.metadata().get("testsPassed"));
        int failures = integer(result.metadata().get("testsFailed"));
        int errors = integer(result.metadata().get("testsErrors"));
        EvidenceStatus status;
        if ("TEST_FAILED".equals(testStatus)
                && (failures > 0 || errors > 0
                || "ASSERTION".equals(result.metadata().get("diagnosticType")))) {
            status = EvidenceStatus.FAIL;
        } else if ("TEST_PASSED".equals(testStatus) && result.success() && summaryAvailable
                && passed > 0 && failures == 0 && errors == 0) {
            status = EvidenceStatus.PASS;
        } else {
            status = EvidenceStatus.UNAVAILABLE;
        }
        return new FunctionalEvidence(status, source(step));
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String source(AgentStep step) {
        return "run_maven_test#" + Objects.toString(step.toolCallId(), "step-" + step.stepIndex());
    }

    private static CompletionAssessment assessment(ExecutionStatus execution, MutationStatus mutation,
                                                   EvidenceStatus verification, EvidenceStatus functional,
                                                   boolean finalAnswer) {
        if (!finalAnswer || execution != ExecutionStatus.COMPLETED) return CompletionAssessment.EXECUTION_INCOMPLETE;
        if (verification == EvidenceStatus.FAIL || functional == EvidenceStatus.FAIL) {
            return CompletionAssessment.VERIFICATION_FAILED;
        }
        if (mutation == MutationStatus.FAILED) return CompletionAssessment.MUTATION_FAILED;
        if (functional == EvidenceStatus.PASS) return CompletionAssessment.FUNCTIONAL_TEST_PASS_EVIDENCE;
        if (mutation == MutationStatus.SUCCESS || mutation == MutationStatus.PARTIAL) {
            return CompletionAssessment.EXECUTED_NOT_FUNCTIONALLY_VERIFIED;
        }
        return CompletionAssessment.EXECUTION_COMPLETED;
    }

    public enum ExecutionStatus { COMPLETED, FAILED, MAX_STEPS, TIMED_OUT, REQUEST_BUDGET_EXCEEDED, DIAGNOSTICS_FAILURE }
    public enum MutationStatus { SUCCESS, NOT_MODIFIED, FAILED, PARTIAL }
    public enum EvidenceStatus { PASS, FAIL, UNAVAILABLE, NOT_RUN }
    public enum VerificationKind { NONE, SYNTAX, COMPILATION, BUILD, OTHER, MIXED }
    public enum CompletionAssessment {
        EXECUTION_COMPLETED, EXECUTED_NOT_FUNCTIONALLY_VERIFIED, FUNCTIONAL_TEST_PASS_EVIDENCE,
        MUTATION_FAILED, VERIFICATION_FAILED, EXECUTION_INCOMPLETE
    }

    /** Contains only event identifiers and classifications, never tool content or file snapshots. */
    public record EvidenceSource(int stepIndex, String eventType, String name, String eventId,
                                 EvidenceStatus status) { }
    private record VerificationEvidence(EvidenceStatus status, VerificationKind kind, String source) { }
    private record FunctionalEvidence(EvidenceStatus status, String source) { }
}
