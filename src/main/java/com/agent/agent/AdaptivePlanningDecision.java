package com.agent.agent;

import java.util.List;
import java.util.Objects;

/** Explainable local choice of the effective planning strategy for one CODE task. */
public record AdaptivePlanningDecision(
        PlanningMode selectedMode,
        Confidence confidence,
        List<String> reasons
) {
    public enum Confidence { HIGH, MEDIUM, LOW }

    public AdaptivePlanningDecision {
        Objects.requireNonNull(selectedMode, "selectedMode must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
        if (selectedMode == PlanningMode.ADAPTIVE) {
            throw new IllegalArgumentException("selectedMode must be an effective planning mode");
        }
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
    }
}
