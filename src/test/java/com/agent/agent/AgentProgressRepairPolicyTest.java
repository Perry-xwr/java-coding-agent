package com.agent.agent;

import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProgressRepairPolicyTest {
    private static final String SOURCE = "src/main/java/Calculator.java";

    @Test
    void sourceFailureRequiresFreshTargetReadAndChangedMutationBeforeRetest() {
        AgentProgress progress = new AgentProgress();
        progress.observe("apply_context_patch", Map.of("path", SOURCE), changed(), 1);
        progress.observe("run_maven_test", Map.of(), assertionFailure(), 2);

        assertTrue(progress.requiresRepairRead(SOURCE));
        assertNotNull(progress.mavenRetryBlockReason());
        assertEquals("REPAIR_REQUIRES_FRESH_READ_AFTER_TEST_FAILURE", progress.mutationBlockReason(SOURCE));

        progress.observe("read_file", Map.of("path", SOURCE), ToolResult.success("latest source"), 3);
        assertFalse(progress.requiresRepairRead(SOURCE));
        progress.observe("apply_context_patch", Map.of("path", SOURCE), changed(), 4);

        assertNull(progress.mavenRetryBlockReason());
        assertEquals(1, progress.repairMutationsForTask());
    }

    @Test
    void identicalFailuresAndExcessiveRepairsAreBounded() {
        AgentProgress progress = new AgentProgress();
        progress.observe("apply_context_patch", Map.of("path", SOURCE), changed(), 1);
        for (int i = 0; i < AgentProgress.MAX_MAVEN_ATTEMPTS_PER_TASK; i++) {
            int failureStep = 2 + i * 3;
            progress.observe("run_maven_test", Map.of(), assertionFailure(), failureStep);
            assertNotNull(progress.mavenRetryBlockReason());
            if (i < 2) {
                progress.observe("read_file", Map.of("path", SOURCE), ToolResult.success("fresh"), failureStep + 1);
                progress.observe("apply_context_patch", Map.of("path", SOURCE), changed(), failureStep + 2);
            }
        }
        assertEquals(2, progress.repairMutationsForTask());
        assertEquals("MAVEN_ATTEMPT_LIMIT_REACHED", progress.mavenRetryBlockReason());
        assertEquals("REPAIR_ATTEMPT_LIMIT_REACHED", progress.mutationBlockReason(SOURCE));
    }

    @Test
    void approvalOrInfrastructureFailureBlocksBothRepairAndAnotherTest() {
        AgentProgress progress = new AgentProgress();
        progress.observe("run_maven_test", Map.of(), ToolResult.failure(ToolErrorCode.APPROVAL_DENIED,
                "denied", Map.of("testStatus", "APPROVAL_DENIED", "approvalStatus", "DENIED",
                        "executed", false)), 1);

        assertEquals(RepairFailureCategory.APPROVAL_DENIED, progress.nonRepairableFailureCategory());
        assertTrue(progress.mutationBlockReason(SOURCE).startsWith("REPAIR_BLOCKED_BY_NON_SOURCE_FAILURE"));
        assertTrue(progress.mavenRetryBlockReason().startsWith("MAVEN_FAILURE_NOT_SOURCE_REPAIRABLE"));

        AgentProgress unavailable = new AgentProgress();
        unavailable.observe("run_maven_test", Map.of(), ToolResult.failure(
                ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT, "unavailable"), 1);
        assertEquals(RepairFailureCategory.TOOL_UNAVAILABLE, unavailable.nonRepairableFailureCategory());
        assertNull(unavailable.mutationBlockReason(SOURCE));
        assertTrue(unavailable.mavenRetryBlockReason().startsWith("MAVEN_FAILURE_NOT_SOURCE_REPAIRABLE"));
    }

    private static ToolResult changed() {
        return ToolResult.success("changed", Map.of("changed", true));
    }

    private static ToolResult assertionFailure() {
        return ToolResult.failureWithOutput(ToolErrorCode.TEST_FAILED, "failed", "untrusted output",
                Map.of("testStatus", "TEST_FAILED", "diagnosticType", "ASSERTION", "testsFailed", 1,
                        "executed", true, "approvalStatus", "APPROVED"));
    }
}
