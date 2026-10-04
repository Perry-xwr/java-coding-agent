package com.agent.environment;

import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;

import java.util.Map;
import java.util.Objects;

/** A point-in-time environment decision for one registered tool. */
public record ToolAvailabilityDecision(
        String toolName,
        boolean available,
        String reason,
        String capabilitySnapshot
) {
    public ToolAvailabilityDecision {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        reason = Objects.requireNonNullElse(reason, "No availability reason provided.");
        capabilitySnapshot = Objects.requireNonNullElse(capabilitySnapshot, "UNKNOWN");
    }

    public static ToolAvailabilityDecision available(String toolName, String reason, String snapshot) {
        return new ToolAvailabilityDecision(toolName, true, reason, snapshot);
    }

    public static ToolAvailabilityDecision unavailable(String toolName, String reason, String snapshot) {
        return new ToolAvailabilityDecision(toolName, false, reason, snapshot);
    }

    public ToolResult rejectionResult() {
        if (available) {
            throw new IllegalStateException("Available tools do not have a rejection result");
        }
        return ToolResult.failure(ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT,
                "Tool " + toolName + " is unavailable in the current workspace: " + reason,
                Map.of("availability", "UNAVAILABLE_IN_ENVIRONMENT", "toolName", toolName,
                        "reason", reason, "capabilitySnapshot", capabilitySnapshot));
    }
}
