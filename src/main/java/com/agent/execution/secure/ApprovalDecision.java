package com.agent.execution.secure;

import java.util.Objects;

/** Human approval result bound to one immutable request and policy digest. */
public record ApprovalDecision(Status status, String requestDigest, String approvalId) {
    public ApprovalDecision {
        Objects.requireNonNull(status, "status");
        requestDigest = requestDigest == null ? "" : requestDigest;
        approvalId = approvalId == null ? "" : approvalId;
        if (status == Status.APPROVED && (requestDigest.isBlank() || approvalId.isBlank())) {
            throw new IllegalArgumentException("Approved decisions require a request digest and approval id");
        }
    }

    public enum Status { APPROVED, DENIED, UNAVAILABLE }
}
