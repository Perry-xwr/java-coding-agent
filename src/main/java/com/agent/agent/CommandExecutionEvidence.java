package com.agent.agent;

import com.agent.execution.secure.ApprovalDecision;
import com.agent.execution.secure.ExecutionResult;

import java.util.Objects;

/** Explicitly distinguishes consent from a process that actually started. */
public record CommandExecutionEvidence(String toolCallId, ApprovalDecision.Status approvalStatus,
                                       ExecutionResult.Status executionStatus, boolean actuallyExecuted,
                                       Integer exitCode, String requestDigest, String containerName) {
    public CommandExecutionEvidence {
        if (toolCallId == null || toolCallId.isBlank()) throw new IllegalArgumentException("toolCallId is required");
        Objects.requireNonNull(approvalStatus);
        Objects.requireNonNull(executionStatus);
        requestDigest = requestDigest == null ? "" : requestDigest;
        containerName = containerName == null ? "" : containerName;
        if (executionStatus == ExecutionResult.Status.EXITED && (!actuallyExecuted || exitCode == null)) {
            throw new IllegalArgumentException("EXITED evidence requires an actual process and exit code");
        }
    }

    public static CommandExecutionEvidence from(String toolCallId, ExecutionResult result) {
        Objects.requireNonNull(result);
        return new CommandExecutionEvidence(toolCallId, result.approvalStatus(), result.status(),
                result.executed(), result.exitCode(), result.requestDigest(), result.containerName());
    }
}
