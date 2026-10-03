package com.agent.benchmark.repairreliability;

import java.util.List;

public enum RepairReliabilityRoundOrder {
    VERIFICATION_THEN_GUIDED(List.of(RepairReliabilityMode.VERIFICATION_ONLY, RepairReliabilityMode.GUIDED_REPAIR)),
    GUIDED_THEN_VERIFICATION(List.of(RepairReliabilityMode.GUIDED_REPAIR, RepairReliabilityMode.VERIFICATION_ONLY));

    private final List<RepairReliabilityMode> modes;
    RepairReliabilityRoundOrder(List<RepairReliabilityMode> modes) { this.modes = List.copyOf(modes); }
    public List<RepairReliabilityMode> modes() { return modes; }
}
