package com.agent.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AgentPlan(
        String goal,
        List<TaskRequirement> requirements,
        String currentFocus,
        String notes
) {
    public static final int MAX_REQUIREMENTS = 8;

    public AgentPlan {
        requireText(goal, "goal");
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements must not be null"));
        if (requirements.isEmpty() || requirements.size() > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException("requirements must contain between 1 and "
                    + MAX_REQUIREMENTS + " entries");
        }
        Set<String> ids = new HashSet<>();
        for (TaskRequirement requirement : requirements) {
            if (!ids.add(requirement.id())) {
                throw new IllegalArgumentException("duplicate requirement id: " + requirement.id());
            }
        }
        currentFocus = currentFocus == null ? "" : currentFocus.trim();
        notes = notes == null ? "" : notes.trim();
    }

    public int completedRequirementCount() {
        return (int) requirements.stream()
                .filter(requirement -> requirement.status() == RequirementStatus.COMPLETED)
                .count();
    }

    public int remainingRequirementCount() {
        return requirements.size() - completedRequirementCount();
    }

    public List<TaskRequirement> remainingRequirements() {
        return requirements.stream()
                .filter(requirement -> requirement.status() != RequirementStatus.COMPLETED)
                .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
