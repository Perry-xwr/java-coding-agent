package com.agent.execution.secure;

/** Production bridge to the S1-A OS-console approver for fixed Maven test actions. */
public final class HostMavenExecutionApproval {
    private HostMavenExecutionApproval() { }

    public static ApprovalDecision request(ExecutionApprovalPrompt prompt) {
        return ConsoleExecutionApprover.systemConsole().approve(prompt);
    }
}
