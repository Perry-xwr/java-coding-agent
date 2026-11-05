package com.agent.execution.secure;

import java.util.Objects;

/** Safe, bounded details shown to the user; contains no environment values or source contents. */
public record ExecutionApprovalPrompt(String requestDigest, String challenge,
                                      ExecutionRequest.Action action, String workspaceCopy,
                                      String selectedTest, String imageReference,
                                      String isolationSummary, String riskNotice) {
    public ExecutionApprovalPrompt {
        Objects.requireNonNull(requestDigest);
        Objects.requireNonNull(challenge);
        Objects.requireNonNull(action);
        Objects.requireNonNull(workspaceCopy);
        Objects.requireNonNull(imageReference);
        Objects.requireNonNull(isolationSummary);
        Objects.requireNonNull(riskNotice);
        selectedTest = selectedTest == null ? "all tests" : selectedTest;
    }
}
