package com.agent.execution.secure;

import java.io.Console;
import java.util.UUID;

/** Uses the OS console directly (never the Agent's BufferedReader or model/tool output). */
final class ConsoleExecutionApprover implements ExecutionApprover {
    private final Console console;

    ConsoleExecutionApprover(Console console) {
        this.console = console;
    }

    static ConsoleExecutionApprover systemConsole() {
        return new ConsoleExecutionApprover(System.console());
    }

    @Override
    public ApprovalDecision approve(ExecutionApprovalPrompt prompt) {
        if (console == null) {
            return new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE, prompt.requestDigest(), "");
        }
        console.printf("%nSECURE EXECUTION APPROVAL%nAction: %s%nWorkspace copy: %s%nTest: %s%nImage: %s%n"
                        + "Isolation: %s%nRisk: %s%nTo approve this one request, type: APPROVE %s%n"
                        + "Type DENY or CANCEL to refuse.%n",
                prompt.action(), prompt.workspaceCopy(), prompt.selectedTest(), prompt.imageReference(),
                prompt.isolationSummary(), prompt.riskNotice(), prompt.challenge());
        String response = console.readLine("> ");
        return ExactExecutionConfirmation.evaluate(prompt,response);
    }
}
