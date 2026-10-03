package com.agent.benchmark.planning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.AgentStep;
import com.agent.tool.ToolResult;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic behavioral metrics; planning metrics never determine task success. */
public record PlanningBenchmarkMetrics(
        int providerRequests,
        int toolSteps,
        int mutationCount,
        int targetDrift,
        int typedFailures,
        int repeatedIdenticalFailure,
        int explicitReads,
        int verificationAttempts,
        int replanCount,
        int planFallback,
        int planStepsTotal,
        int planStepsCompleted,
        String planOutcome,
        boolean conversationalCompletion
) {
    private static final Set<String> MUTATIONS = Set.of(
            "create_file", "apply_patch", "insert_before", "insert_after", "replace_lines");

    public static PlanningBenchmarkMetrics from(AgentTrajectory trajectory, int providerRequests,
                                                List<String> allowedTargets) {
        int toolSteps = 0, mutations = 0, drift = 0, failures = 0, repeated = 0;
        int reads = 0, verification = 0, replans = 0, fallbacks = 0, planTotal = 0, planCompleted = 0;
        String outcome = "NONE";
        FailureKey previous = null;
        Set<String> allowed = Set.copyOf(allowedTargets);
        for (AgentStep step : trajectory.steps()) {
            if (step.actionType() == AgentActionType.PLAN_CREATED
                    || step.actionType() == AgentActionType.REPLAN) {
                Object requirements = step.arguments().get("requirements");
                if (requirements instanceof List<?> list) planTotal += list.size();
                if (step.actionType() == AgentActionType.REPLAN) replans++;
            } else if (step.actionType() == AgentActionType.PLAN_FALLBACK) {
                fallbacks++;
            } else if (step.actionType() == AgentActionType.PLAN_STEP_UPDATE) {
                if ("COMPLETED".equals(step.arguments().get("status"))) planCompleted++;
                if ("PLAN_OUTCOME".equals(step.arguments().get("event"))) {
                    outcome = String.valueOf(step.arguments().getOrDefault("outcome", "UNKNOWN"));
                }
            }
            if (step.actionType() != AgentActionType.TOOL_CALL) continue;
            toolSteps++;
            ToolResult result = step.toolResult();
            if ("read_file".equals(step.toolName())) reads++;
            if ("run_maven_test".equals(step.toolName())) verification++;
            if (result != null && !result.success()) {
                failures++;
                FailureKey current = new FailureKey(step.toolName(), new TreeMap<>(step.arguments()).toString(),
                        result.errorCode().name());
                if (current.equals(previous)) repeated++;
                previous = current;
            } else {
                previous = null;
            }
            if (MUTATIONS.contains(step.toolName()) && result != null && result.success()
                    && !Boolean.FALSE.equals(result.metadata().get("changed"))) {
                mutations++;
                String path = path(step);
                if (path == null || !allowed.contains(path)) drift++;
            }
        }
        return new PlanningBenchmarkMetrics(providerRequests, toolSteps, mutations, drift, failures, repeated,
                reads, verification, replans, fallbacks, planTotal, planCompleted, outcome,
                trajectory.completed());
    }

    private static String path(AgentStep step) {
        Object candidate = step.toolResult().metadata().get("path");
        if (candidate == null) candidate = step.arguments().get("path");
        return candidate instanceof String value ? value.replace('\\', '/') : null;
    }

    private record FailureKey(String tool, String arguments, String error) { }
}
