package com.agent;

import java.util.Objects;

/** Immutable, provider-free AUTO routing result. */
public record RoutingDecision(CliMode mode, RoutingConfidence confidence, RoutingReason reason) {
    public RoutingDecision {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (mode == CliMode.AUTO) {
            throw new IllegalArgumentException("AUTO is not a routable target mode");
        }
    }
}
