package com.agent.execution.secure;

import java.util.UUID;

/** Shared R1 confirmation rule; UI transport must never replace the exact nonce with Y/N. */
public final class ExactExecutionConfirmation {
    private ExactExecutionConfirmation() { }
    public static ApprovalDecision evaluate(ExecutionApprovalPrompt prompt,String response){
        return response!=null&&response.equals("APPROVE "+prompt.challenge())
                ?new ApprovalDecision(ApprovalDecision.Status.APPROVED,prompt.requestDigest(),UUID.randomUUID().toString())
                :new ApprovalDecision(ApprovalDecision.Status.DENIED,prompt.requestDigest(),"");
    }
}
