package com.agent.agent;

import java.util.Objects;

public record TaskRequirement(
        String id,
        String description,
        RequirementStatus status,
        String evidence
) {
    public TaskRequirement {
        requireText(id, "id");
        requireText(description, "description");
        Objects.requireNonNull(status, "status must not be null");
        evidence = evidence == null ? "" : evidence.trim();
        if (status == RequirementStatus.COMPLETED && evidence.isBlank()) {
            throw new IllegalArgumentException("completed requirement must include evidence");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
