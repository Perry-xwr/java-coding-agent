package com.agent.benchmark;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentStep;
import com.agent.agent.TerminationReason;
import com.agent.tool.ToolErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FailureClassifier {
    public FailureCategory classify(
            BenchmarkTask task,
            AgentRunResult run,
            EvaluationResult evaluation,
            boolean evaluatorError
    ) {
        if (evaluatorError) {
            return FailureCategory.EVALUATOR_ERROR;
        }
        if (evaluation.success()) {
            return FailureCategory.NONE;
        }
        if (run.trajectory().terminationReason() == TerminationReason.MAX_STEPS) {
            return FailureCategory.MAX_STEPS;
        }
        if (run.trajectory().terminationReason() == TerminationReason.LLM_ERROR) {
            return FailureCategory.LLM_ERROR;
        }
        List<AgentStep> tools = toolSteps(run);
        if (containsError(tools, ToolErrorCode.WORKSPACE_VIOLATION)) {
            return FailureCategory.WORKSPACE_VIOLATION;
        }
        if (containsError(tools, ToolErrorCode.TOOL_NOT_FOUND)) {
            return FailureCategory.TOOL_NOT_FOUND;
        }
        if (containsError(tools, ToolErrorCode.INVALID_ARGUMENTS)) {
            return FailureCategory.INVALID_ARGUMENTS;
        }
        if (hasRepeatedAction(tools)) {
            return FailureCategory.LOOP_OR_REPEATED_ACTION;
        }
        boolean patchAttempted = tools.stream().anyMatch(step -> isEditTool(step.toolName()));
        boolean patchSucceeded = tools.stream().anyMatch(step -> isEditTool(step.toolName())
                && step.toolResult() != null && step.toolResult().success());
        if (patchAttempted && !patchSucceeded) {
            return FailureCategory.PATCH_FAILED;
        }
        if (containsError(tools, ToolErrorCode.TEST_FAILED)) {
            return FailureCategory.TEST_FAILED_UNRECOVERED;
        }
        if (!evaluation.requiredChangePresent()) {
            if (run.trajectory().terminationReason() == TerminationReason.FINAL_ANSWER
                    && !usedRequiredTools(task, tools)) {
                return FailureCategory.PREMATURE_FINAL;
            }
            return FailureCategory.NO_REQUIRED_CHANGE;
        }
        return FailureCategory.UNKNOWN;
    }

    private static List<AgentStep> toolSteps(AgentRunResult run) {
        return run.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();
    }

    private static boolean containsError(List<AgentStep> steps, ToolErrorCode code) {
        return steps.stream().anyMatch(step -> step.toolResult() != null
                && !step.toolResult().success()
                && step.toolResult().errorCode() == code);
    }

    private static boolean usedRequiredTools(BenchmarkTask task, List<AgentStep> steps) {
        return task.requiredTools().stream().allMatch(required -> steps.stream()
                .anyMatch(step -> required.equals(step.toolName())
                        || ("apply_patch".equals(required)
                        && "replace_lines".equals(step.toolName()))));
    }

    private static boolean isEditTool(String toolName) {
        return "apply_patch".equals(toolName) || "replace_lines".equals(toolName);
    }

    private static boolean hasRepeatedAction(List<AgentStep> steps) {
        Map<String, Integer> counts = new HashMap<>();
        for (AgentStep step : steps) {
            String key = step.toolName() + "\n" + step.rawArguments();
            if (counts.merge(key, 1, Integer::sum) >= 3) {
                return true;
            }
        }
        return false;
    }
}
