package com.agent.agent;

import java.util.Locale;
import java.util.Map;

/** Selects whether the coding Agent plans separately before its normal execution loop. */
public enum PlanningMode {
    REACTIVE,
    PLAN_EXECUTE,
    ADAPTIVE;

    public static PlanningMode fromEnvironment() {
        return fromValue(System.getenv("PLANNING_MODE"));
    }

    public static PlanningMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return REACTIVE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "reactive" -> REACTIVE;
            case "plan-execute" -> PLAN_EXECUTE;
            case "adaptive" -> ADAPTIVE;
            default -> throw new IllegalArgumentException(
                    "PLANNING_MODE must be 'reactive', 'plan-execute', or 'adaptive'");
        };
    }
}
