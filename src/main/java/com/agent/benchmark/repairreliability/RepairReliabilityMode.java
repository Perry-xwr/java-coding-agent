package com.agent.benchmark.repairreliability;

import com.agent.agent.VerificationRepairPolicy;

public enum RepairReliabilityMode {
    VERIFICATION_ONLY(VerificationRepairPolicy.VERIFICATION_ONLY),
    GUIDED_REPAIR(VerificationRepairPolicy.GUIDED_REPAIR);

    private final VerificationRepairPolicy runtimePolicy;
    RepairReliabilityMode(VerificationRepairPolicy runtimePolicy) { this.runtimePolicy = runtimePolicy; }
    public VerificationRepairPolicy runtimePolicy() { return runtimePolicy; }
}
