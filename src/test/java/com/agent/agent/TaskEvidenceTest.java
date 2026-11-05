package com.agent.agent;

import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.agent.agent.TaskEvidence.CompletionAssessment.*;
import static com.agent.agent.TaskEvidence.EvidenceStatus.*;
import static com.agent.agent.TaskEvidence.ExecutionStatus.*;
import static com.agent.agent.TaskEvidence.MutationStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskEvidenceTest {
    @Test
    void successfulCreateWithoutFunctionalTestIsNotClaimedAsFunctionallyVerified() {
        TaskEvidence evidence = evidence(tool(1, "create_file", ToolResult.success("Created", Map.of("changed", true))),
                finalAnswer(2));
        assertEquals(COMPLETED, evidence.executionStatus());
        assertEquals(SUCCESS, evidence.mutationStatus());
        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertEquals(EXECUTED_NOT_FUNCTIONALLY_VERIFIED, evidence.completionAssessment());
    }

    @Test
    void javacPassDoesNotBecomeFunctionalTestPass() {
        TaskEvidence evidence = evidence(tool(1, "apply_patch", changed()),
                verify(2, "java-javac", VerificationStatus.PASS), finalAnswer(3));
        assertEquals(PASS, evidence.verificationStatus());
        assertEquals(TaskEvidence.VerificationKind.COMPILATION, evidence.verificationKind());
        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertEquals(EXECUTED_NOT_FUNCTIONALLY_VERIFIED, evidence.completionAssessment());
    }

    @Test
    void pythonSyntaxPassDoesNotBecomeFunctionalTestPass() {
        TaskEvidence evidence = evidence(tool(1, "apply_context_patch", changed()),
                verify(2, "python-py-compile", VerificationStatus.PASS), finalAnswer(3));
        assertEquals(PASS, evidence.verificationStatus());
        assertEquals(TaskEvidence.VerificationKind.SYNTAX, evidence.verificationKind());
        assertEquals(NOT_RUN, evidence.functionalVerification());
    }

    @Test
    void compileFailureIsReportedSeparatelyFromTaskExecution() {
        TaskEvidence evidence = evidence(tool(1, "insert_after", changed()),
                verify(2, "java-javac", VerificationStatus.FAIL), finalAnswer(3));
        assertEquals(COMPLETED, evidence.executionStatus());
        assertEquals(FAIL, evidence.verificationStatus());
        assertEquals(VERIFICATION_FAILED, evidence.completionAssessment());
    }

    @Test
    void failedMutationDoesNotCountAsWorkspaceChange() {
        TaskEvidence evidence = evidence(tool(1, "create_file",
                ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR, "write failed")), finalAnswer(2));
        assertEquals(TaskEvidence.MutationStatus.FAILED, evidence.mutationStatus());
        assertEquals(MUTATION_FAILED, evidence.completionAssessment());
    }

    @Test
    void assistantClaimCannotCreateFunctionalPassEvidence() {
        TaskEvidence evidence = evidence(finalAnswer(1));
        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertEquals(EXECUTION_COMPLETED, evidence.completionAssessment());
        assertTrue(evidence.evidenceSources().isEmpty());
    }

    @Test
    void modelFinalGenerationIsDistinctFromSystemGuardTermination() {
        AgentStep guardFinal = new AgentStep(1, AgentActionType.FINAL_ANSWER, null, null, null,
                Map.of("origin", "SYSTEM_GUARD"), null, "Verification was required.", null, 1, 0);
        TaskEvidence evidence = TaskEvidence.from(trajectory(List.of(guardFinal), TerminationReason.FINAL_ANSWER, false));
        assertFalse(evidence.modelFinalAnswerGenerated());
        assertEquals(TaskEvidence.ExecutionStatus.FAILED, evidence.executionStatus());
        assertEquals(EXECUTION_INCOMPLETE, evidence.completionAssessment());
    }

    @Test
    void actualMavenTestToolResultCanProvideFunctionalPassEvidence() {
        ToolResult result = ToolResult.success("BUILD SUCCESS\nTests run: 4, Failures: 0, Errors: 0, Skipped: 0",
                Map.of("executed", true, "approvalStatus", "APPROVED", "testStatus", "TEST_PASSED",
                        "testSummaryAvailable", true, "testsRun", 4, "testsPassed", 4,
                        "testsFailed", 0, "testsErrors", 0, "testsSkipped", 0));
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", result), finalAnswer(2));
        assertEquals(PASS, evidence.functionalVerification());
        assertEquals(FUNCTIONAL_TEST_PASS_EVIDENCE, evidence.completionAssessment());
        assertTrue(evidence.evidenceSources().stream().anyMatch(source -> source.name().equals("run_maven_test")
                && source.eventType().equals("FUNCTIONAL_TEST_RESULT") && source.status() == PASS));
    }

    @Test
    void actualMavenTestFailureIsReportedAsFunctionalFailure() {
        ToolResult result = ToolResult.failureWithOutput(ToolErrorCode.TEST_FAILED, "tests failed",
                "Tests run: 4, Failures: 1, Errors: 0, Skipped: 0", Map.of("executed", true,
                        "approvalStatus", "APPROVED", "testStatus", "TEST_FAILED",
                        "testSummaryAvailable", true, "testsRun", 4, "testsPassed", 3,
                        "testsFailed", 1, "testsErrors", 0, "testsSkipped", 0));
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", result), finalAnswer(2));
        assertEquals(FAIL, evidence.functionalVerification());
        assertEquals(VERIFICATION_FAILED, evidence.completionAssessment());
    }

    @Test
    void laterMutationInvalidatesEarlierFunctionalPassEvidence() {
        ToolResult pass = ToolResult.success("tests passed", Map.of("executed", true,
                "approvalStatus", "APPROVED", "testStatus", "TEST_PASSED", "testSummaryAvailable", true,
                "testsRun", 1, "testsPassed", 1, "testsFailed", 0, "testsErrors", 0, "testsSkipped", 0));
        TaskEvidence evidence = evidence(tool(1, "apply_patch", changed()), tool(2, "run_maven_test", pass),
                tool(3, "apply_context_patch", changed()), finalAnswer(4));

        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertEquals(EXECUTED_NOT_FUNCTIONALLY_VERIFIED, evidence.completionAssessment());
    }

    @Test
    void mavenBuildSuccessWithoutTestCountIsUnavailableNotPass() {
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", ToolResult.success("BUILD SUCCESS")),
                finalAnswer(2));
        assertEquals(NOT_RUN, evidence.functionalVerification());
    }

    @Test
    void approvalWithoutExecutionIsNotFunctionalTestEvidence() {
        ToolResult denied = ToolResult.failure(ToolErrorCode.APPROVAL_DENIED, "denied",
                Map.of("approvalStatus", "DENIED", "executed", false, "testStatus", "APPROVAL_DENIED"));
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", denied), finalAnswer(2));
        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertTrue(evidence.evidenceSources().stream().anyMatch(source -> source.eventType().equals("MAVEN_APPROVAL")
                && source.status() == FAIL));
        assertFalse(evidence.evidenceSources().stream().anyMatch(source ->
                source.eventType().equals("MAVEN_PROCESS_EXECUTION")));
    }

    @Test
    void blockedRetryIsNotRecordedAsAnApprovalOrExecutedTest() {
        ToolResult blocked = ToolResult.failure(ToolErrorCode.MAVEN_RETRY_BLOCKED, "retry blocked",
                Map.of("approvalStatus", "NOT_REQUESTED", "executed", false,
                        "testStatus", "MAVEN_RETRY_BLOCKED"));
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", blocked), finalAnswer(2));

        assertEquals(NOT_RUN, evidence.functionalVerification());
        assertTrue(evidence.evidenceSources().stream().anyMatch(source -> source.eventType().equals("MAVEN_APPROVAL")
                && source.status() == NOT_RUN));
        assertFalse(evidence.evidenceSources().stream().anyMatch(source ->
                source.eventType().equals("MAVEN_PROCESS_EXECUTION")));
    }

    @Test
    void allSkippedTestsDoNotCountAsExecutedFunctionalVerification() {
        TaskEvidence evidence = evidence(tool(1, "run_maven_test", ToolResult.failureWithOutput(
                ToolErrorCode.TESTS_SKIPPED, "all skipped", "Tests run: 3, Failures: 0, Errors: 0, Skipped: 3",
                Map.of("executed", true, "approvalStatus", "APPROVED", "testStatus", "TESTS_SKIPPED",
                        "testSummaryAvailable", true, "testsRun", 3, "testsPassed", 0,
                        "testsFailed", 0, "testsErrors", 0, "testsSkipped", 3))), finalAnswer(2));
        assertEquals(UNAVAILABLE, evidence.functionalVerification());
    }

    @Test
    void verifierUnavailableRemainsUnavailable() {
        TaskEvidence evidence = evidence(tool(1, "create_file", changed()),
                verify(2, "python-py-compile", VerificationStatus.UNAVAILABLE), finalAnswer(3));
        assertEquals(UNAVAILABLE, evidence.verificationStatus());
        assertEquals(NOT_RUN, evidence.functionalVerification());
    }

    @Test
    void latestVerificationAfterRepairIsTheCurrentEvidence() {
        TaskEvidence evidence = evidence(tool(1, "apply_patch", changed()),
                verify(2, "java-javac", VerificationStatus.FAIL),
                tool(3, "apply_context_patch", changed()),
                verify(4, "java-javac", VerificationStatus.PASS), finalAnswer(5));
        assertEquals(PASS, evidence.verificationStatus());
        assertEquals(2, evidence.evidenceSources().stream()
                .filter(source -> source.eventType().equals("VERIFICATION_EVENT")).count());
    }

    @Test
    void aPassingCheckForAnotherFileDoesNotHideAnUnresolvedFailure() {
        TaskEvidence evidence = evidence(
                verify(1, "java-javac", VerificationStatus.FAIL, "A.java"),
                verify(2, "java-javac", VerificationStatus.PASS, "B.java"), finalAnswer(3));
        assertEquals(FAIL, evidence.verificationStatus());
        assertEquals(TaskEvidence.VerificationKind.COMPILATION, evidence.verificationKind());
    }

    @Test
    void threeEditTurnsRetainTheirActualMutationAndVerificationEvents() {
        TaskEvidence evidence = evidence(
                tool(1, "create_file", changed()), verify(2, "java-javac", VerificationStatus.PASS),
                tool(3, "apply_context_patch", changed()), verify(4, "java-javac", VerificationStatus.PASS),
                tool(5, "apply_context_patch", changed()), verify(6, "java-javac", VerificationStatus.PASS),
                finalAnswer(7));
        assertEquals(SUCCESS, evidence.mutationStatus());
        assertEquals(3, evidence.evidenceSources().stream()
                .filter(source -> source.eventType().equals("WORKSPACE_MUTATION"))
                .count());
        assertEquals(PASS, evidence.verificationStatus());
    }

    @Test
    void chatAndReadCanCompleteWithoutMutationOrRequiredCompilation() {
        TaskEvidence chat = evidence(finalAnswer(1));
        TaskEvidence read = evidence(tool(1, "read_file", ToolResult.success("summary")), finalAnswer(2));
        assertEquals(COMPLETED, chat.executionStatus());
        assertEquals(NOT_MODIFIED, chat.mutationStatus());
        assertEquals(NOT_RUN, chat.verificationStatus());
        assertEquals(EXECUTION_COMPLETED, chat.completionAssessment());
        assertEquals(NOT_MODIFIED, read.mutationStatus());
        assertEquals(EXECUTION_COMPLETED, read.completionAssessment());
        assertTrue(read.evidenceSources().stream().anyMatch(source -> source.name().equals("read_file")
                && source.eventType().equals("TOOL_RESULT") && source.status() == PASS));
    }

    @Test
    void budgetAndTimeoutRemainDistinctExecutionStates() {
        assertEquals(REQUEST_BUDGET_EXCEEDED, TaskEvidence.from(trajectory(List.of(),
                TerminationReason.REQUEST_BUDGET_EXCEEDED, false)).executionStatus());
        assertEquals(TIMED_OUT, TaskEvidence.from(trajectory(List.of(), TerminationReason.TASK_TIMEOUT,
                false)).executionStatus());
    }

    @Test
    void evidenceSourcesDoNotContainToolOutputOrFileContents() {
        String privateContent = "private source code body";
        TaskEvidence evidence = evidence(tool(1, "read_file", ToolResult.success(privateContent)), finalAnswer(2));
        assertFalse(evidence.toString().contains(privateContent));
    }

    private static TaskEvidence evidence(AgentStep... steps) {
        return TaskEvidence.from(trajectory(List.of(steps), TerminationReason.FINAL_ANSWER, true));
    }

    private static AgentTrajectory trajectory(List<AgentStep> steps, TerminationReason reason, boolean completed) {
        return new AgentTrajectory("run", "task", steps, "done", reason, completed, null, 1, 1);
    }

    private static AgentStep tool(int index, String name, ToolResult result) {
        return new AgentStep(index, AgentActionType.TOOL_CALL, name, "call-" + index, "{}", Map.of(),
                result, null, null, 1, 0);
    }

    private static AgentStep verify(int index, String verifier, VerificationStatus status) {
        return verify(index, verifier, status, "safe-relative.java");
    }

    private static AgentStep verify(int index, String verifier, VerificationStatus status, String file) {
        return new AgentStep(index, AgentActionType.POST_EDIT_VERIFICATION, "post_edit_verification", null,
                null, Map.of("verifier", verifier, "status", status.name(), "file", file),
                null, null, null, 1, 0);
    }

    private static AgentStep finalAnswer(int index) {
        return new AgentStep(index, AgentActionType.FINAL_ANSWER, null, null, null, Map.of("origin", "MODEL"), null,
                "The task and tests succeeded.", null, 1, 0);
    }

    private static ToolResult changed() {
        return ToolResult.success("Applied", Map.of("changed", true));
    }
}
