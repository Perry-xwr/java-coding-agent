package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.PlanningMode;
import com.agent.tool.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Mode-independent runtime counters plus separately reported Adaptive routing evidence. */
public record AdaptivePlanningMetrics(
        PlanningMode effectiveMode,
        String routingConfidence,
        List<String> routingReasonCodes,
        Boolean routingMatch,
        int providerRequests,
        int toolSteps,
        int mutationCount,
        int targetDrift,
        int typedFailures,
        int repeatedFailures,
        int explicitReads,
        int verificationAttempts,
        int planCreated,
        int planFallback,
        int replanCount,
        int planStepsTotal,
        int planStepsCompleted,
        boolean conversationalCompletion
) {
    private static final Set<String> MUTATION_TOOLS = Set.of(
            "create_file", "apply_patch", "insert_before", "insert_after", "replace_lines");

    public AdaptivePlanningMetrics {
        Objects.requireNonNull(effectiveMode, "effectiveMode must not be null");
        routingReasonCodes = routingReasonCodes == null ? List.of() : List.copyOf(routingReasonCodes);
        if (providerRequests < 0 || toolSteps < 0 || mutationCount < 0 || targetDrift < 0
                || typedFailures < 0 || repeatedFailures < 0 || explicitReads < 0
                || verificationAttempts < 0 || planCreated < 0 || planFallback < 0 || replanCount < 0
                || planStepsTotal < 0 || planStepsCompleted < 0) {
            throw new IllegalArgumentException("metrics must not be negative");
        }
    }

    public static AdaptivePlanningMetrics from(AdaptivePlanningTask task, PlanningMode configuredMode,
                                               AgentTrajectory trajectory, int providerRequests) {
        int tools = 0, mutations = 0, drift = 0, failures = 0, repeated = 0;
        int reads = 0, verification = 0, created = 0, fallback = 0, replans = 0, planTotal = 0, planDone = 0;
        String previousFailure = null;
        PlanningMode effective = configuredMode;
        String confidence = null;
        List<String> reasons = List.of();
        for (AgentStep step : trajectory.steps()) {
            if (step.actionType() == AgentActionType.PLANNING_ROUTED) {
                effective = PlanningMode.valueOf(String.valueOf(step.arguments().get("effectiveMode")));
                confidence = String.valueOf(step.arguments().get("confidence"));
                Object rawReasons = step.arguments().get("reasons");
                if (rawReasons instanceof List<?> values) reasons = values.stream().map(String::valueOf).toList();
            } else if (step.actionType() == AgentActionType.PLAN_CREATED) {
                created++;
                Object requirements = step.arguments().get("requirements");
                if (requirements instanceof List<?> values) planTotal += values.size();
            } else if (step.actionType() == AgentActionType.PLAN_FALLBACK) {
                fallback++;
            } else if (step.actionType() == AgentActionType.REPLAN) {
                replans++;
                Object requirements = step.arguments().get("requirements");
                if (requirements instanceof List<?> values) planTotal += values.size();
            } else if (step.actionType() == AgentActionType.PLAN_STEP_UPDATE) {
                if ("COMPLETED".equals(step.arguments().get("status"))) planDone++;
            }
            if (step.actionType() != AgentActionType.TOOL_CALL) continue;
            tools++;
            if ("read_file".equals(step.toolName())) reads++;
            if ("run_maven_test".equals(step.toolName())) verification++;
            ToolResult result = step.toolResult();
            if (result != null && !result.success()) {
                failures++;
                String code = result.errorCode() == null ? "UNKNOWN" : result.errorCode().name();
                String key = step.toolName() + new TreeMap<>(step.arguments()) + code;
                if (key.equals(previousFailure)) repeated++;
                previousFailure = key;
            } else {
                previousFailure = null;
            }
            if (MUTATION_TOOLS.contains(step.toolName()) && result != null && result.success()
                    && !Boolean.FALSE.equals(result.metadata().get("changed"))) {
                mutations++;
                Object path = result.metadata().getOrDefault("path", step.arguments().get("path"));
                if (!(path instanceof String value)
                        || !task.allowedMutationTargets().contains(value.replace('\\', '/'))) drift++;
            }
        }
        Boolean routeMatch = configuredMode == PlanningMode.ADAPTIVE
                ? task.expectedAdaptiveMode() == effective : null;
        return new AdaptivePlanningMetrics(effective, confidence, reasons, routeMatch, providerRequests,
                tools, mutations, drift, failures, repeated, reads, verification, created, fallback,
                replans, planTotal, planDone, trajectory.completed());
    }
}
