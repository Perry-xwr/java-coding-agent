package com.agent.agent;

import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairFailureCategoryTest {
    @Test
    void mapsVerifierAndMavenEvidenceToRepairableCategories() {
        assertEquals(RepairFailureCategory.SOURCE_SYNTAX_ERROR,
                RepairFailureCategory.fromVerification(new VerificationResult(VerificationStatus.FAIL,
                        Path.of("a.py"), "python-syntax", "invalid syntax", "", 1)));
        assertEquals(RepairFailureCategory.COMPILATION_ERROR,
                RepairFailureCategory.fromVerification(new VerificationResult(VerificationStatus.FAIL,
                        Path.of("A.java"), "javac", "cannot find symbol", "", 1)));
        assertEquals(RepairFailureCategory.SOURCE_SYNTAX_ERROR,
                RepairFailureCategory.fromVerification(new VerificationResult(VerificationStatus.FAIL,
                        Path.of("A.java"), "javac", "illegal start of expression", "", 1)));
        assertEquals(RepairFailureCategory.TEST_ASSERTION_FAILURE, category(ToolErrorCode.TEST_FAILED,
                Map.of("diagnosticType", "ASSERTION", "testStatus", "TEST_FAILED", "testsFailed", 1)));
        assertEquals(RepairFailureCategory.TEST_RUNTIME_ERROR, category(ToolErrorCode.TEST_RUNTIME_ERROR,
                Map.of("diagnosticType", "TEST_RUNTIME", "testStatus", "TEST_RUNTIME_ERROR")));
    }

    @Test
    void infrastructureAndStalenessAreNotSourceRepairCandidates() {
        assertBlocked(ToolErrorCode.DEPENDENCY_UNAVAILABLE, Map.of("testStatus", "DEPENDENCY_UNAVAILABLE"),
                RepairFailureCategory.DEPENDENCY_UNAVAILABLE);
        assertBlocked(ToolErrorCode.BUILD_CONFIGURATION_FAILURE,
                Map.of("testStatus", "BUILD_CONFIGURATION_FAILURE"), RepairFailureCategory.BUILD_CONFIGURATION_FAILURE);
        assertBlocked(ToolErrorCode.APPROVAL_DENIED,
                Map.of("testStatus", "APPROVAL_DENIED", "approvalStatus", "DENIED"), RepairFailureCategory.APPROVAL_DENIED);
        assertBlocked(ToolErrorCode.APPROVAL_UNAVAILABLE,
                Map.of("testStatus", "APPROVAL_UNAVAILABLE", "approvalStatus", "UNAVAILABLE"), RepairFailureCategory.APPROVAL_UNAVAILABLE);
        assertBlocked(ToolErrorCode.PROCESS_TIMEOUT, Map.of("testStatus", "TIMED_OUT"), RepairFailureCategory.TIMEOUT);
        assertBlocked(ToolErrorCode.STALE_EDIT_CONTEXT, Map.of(), RepairFailureCategory.STALE_EDIT_CONTEXT);
        assertBlocked(ToolErrorCode.TESTS_SKIPPED, Map.of("testStatus", "TESTS_SKIPPED"), RepairFailureCategory.TESTS_SKIPPED);
        assertBlocked(ToolErrorCode.TEST_SUMMARY_UNAVAILABLE,
                Map.of("testStatus", "TEST_SUMMARY_UNAVAILABLE"), RepairFailureCategory.TEST_SUMMARY_UNAVAILABLE);
    }

    private static void assertBlocked(ToolErrorCode code, Map<String, Object> metadata,
                                     RepairFailureCategory expected) {
        RepairFailureCategory actual = category(code, metadata);
        assertEquals(expected, actual);
        assertFalse(actual.sourceRepairCandidate());
    }

    private static RepairFailureCategory category(ToolErrorCode code, Map<String, Object> metadata) {
        return RepairFailureCategory.fromToolResult(ToolResult.failure(code, "fixture", metadata));
    }
}
