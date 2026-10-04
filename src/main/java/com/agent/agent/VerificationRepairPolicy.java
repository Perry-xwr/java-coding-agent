package com.agent.agent;

/** Selects whether failed post-edit verification uses guided repair or V1.8-style observation only. */
public enum VerificationRepairPolicy {
    VERIFICATION_ONLY,
    GUIDED_REPAIR;

    public boolean guidedRepairEnabled() {
        return this == GUIDED_REPAIR;
    }
}
