package com.agent.agent;

import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;

import java.util.Locale;
import java.util.Objects;

/** Classification derived only from verifier/tool evidence, never from model prose. */
public enum RepairFailureCategory {
    SOURCE_SYNTAX_ERROR(true),
    COMPILATION_ERROR(true),
    TEST_ASSERTION_FAILURE(true),
    TEST_RUNTIME_ERROR(true),
    BUILD_CONFIGURATION_FAILURE(false),
    DEPENDENCY_UNAVAILABLE(false),
    TOOL_UNAVAILABLE(false),
    APPROVAL_DENIED(false),
    APPROVAL_UNAVAILABLE(false),
    TIMEOUT(false),
    CANCELLED(false),
    PROCESS_START_FAILED(false),
    STALE_EDIT_CONTEXT(false),
    TESTS_SKIPPED(false),
    TEST_SUMMARY_UNAVAILABLE(false),
    UNKNOWN(false);

    private final boolean sourceRepairCandidate;

    RepairFailureCategory(boolean sourceRepairCandidate) {
        this.sourceRepairCandidate = sourceRepairCandidate;
    }

    public boolean sourceRepairCandidate() {
        return sourceRepairCandidate;
    }

    public static RepairFailureCategory fromVerification(VerificationResult result) {
        if (result == null || result.status() != VerificationStatus.FAIL) return UNKNOWN;
        return fromVerifierEvidence(result.verifierId(), result.diagnosticSummary());
    }

    public static RepairFailureCategory fromVerifierEvidence(String verifierId, String diagnosticSummary) {
        String verifier = Objects.toString(verifierId, "").toLowerCase(Locale.ROOT);
        String diagnostic = Objects.toString(diagnosticSummary, "").toLowerCase(Locale.ROOT);
        return verifier.contains("syntax") || verifier.contains("python")
                || diagnostic.contains("syntax") || diagnostic.contains("expected")
                || diagnostic.contains("illegal start") || diagnostic.contains("unclosed")
                ? SOURCE_SYNTAX_ERROR : COMPILATION_ERROR;
    }

    public static RepairFailureCategory fromToolResult(ToolResult result) {
        if (result == null || result.success() || result.errorCode() == null) return UNKNOWN;
        Object rawType = result.metadata().get("diagnosticType");
        String diagnosticType = Objects.toString(rawType, "").toUpperCase(Locale.ROOT);
        String testStatus = Objects.toString(result.metadata().get("testStatus"), "").toUpperCase(Locale.ROOT);
        String approvalStatus = Objects.toString(result.metadata().get("approvalStatus"), "").toUpperCase(Locale.ROOT);
        if ("APPROVAL_DENIED".equals(testStatus) || "DENIED".equals(approvalStatus)) return APPROVAL_DENIED;
        if ("APPROVAL_UNAVAILABLE".equals(testStatus) || "UNAVAILABLE".equals(approvalStatus)) {
            return APPROVAL_UNAVAILABLE;
        }
        return switch (result.errorCode()) {
            case APPROVAL_DENIED -> APPROVAL_DENIED;
            case APPROVAL_UNAVAILABLE -> APPROVAL_UNAVAILABLE;
            case TOOL_UNAVAILABLE_IN_ENVIRONMENT, TOOL_NOT_FOUND -> TOOL_UNAVAILABLE;
            case PROCESS_TIMEOUT -> TIMEOUT;
            case PROCESS_CANCELLED -> CANCELLED;
            case PROCESS_START_FAILED -> PROCESS_START_FAILED;
            case STALE_EDIT_CONTEXT, REPAIR_REQUIRES_FRESH_READ -> STALE_EDIT_CONTEXT;
            case TESTS_SKIPPED -> TESTS_SKIPPED;
            case TEST_SUMMARY_UNAVAILABLE -> TEST_SUMMARY_UNAVAILABLE;
            case DEPENDENCY_UNAVAILABLE -> DEPENDENCY_UNAVAILABLE;
            case BUILD_CONFIGURATION_FAILURE -> BUILD_CONFIGURATION_FAILURE;
            case TEST_RUNTIME_ERROR -> TEST_RUNTIME_ERROR;
            case TEST_FAILED -> "ASSERTION".equals(diagnosticType) || integer(result, "testsFailed") > 0
                    ? TEST_ASSERTION_FAILURE : TEST_RUNTIME_ERROR;
            case BUILD_FAILED -> switch (diagnosticType) {
                case "SYNTAX" -> SOURCE_SYNTAX_ERROR;
                case "COMPILATION" -> COMPILATION_ERROR;
                case "DEPENDENCY" -> DEPENDENCY_UNAVAILABLE;
                case "BUILD_CONFIGURATION" -> BUILD_CONFIGURATION_FAILURE;
                default -> "DEPENDENCY_UNAVAILABLE".equals(testStatus) ? DEPENDENCY_UNAVAILABLE
                        : "BUILD_CONFIGURATION_FAILURE".equals(testStatus) ? BUILD_CONFIGURATION_FAILURE
                        : TEST_SUMMARY_UNAVAILABLE;
            };
            default -> UNKNOWN;
        };
    }

    private static int integer(ToolResult result, String name) {
        Object value = result.metadata().get(name);
        return value instanceof Number number ? number.intValue() : -1;
    }
}
