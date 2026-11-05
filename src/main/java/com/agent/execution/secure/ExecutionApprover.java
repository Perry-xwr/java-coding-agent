package com.agent.execution.secure;

/** Package-private seam: fake approvers are available to same-package tests, not app configuration. */
interface ExecutionApprover {
    ApprovalDecision approve(ExecutionApprovalPrompt prompt);
}
