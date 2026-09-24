package com.agent.agent;

import com.agent.tool.ToolResult;
import com.agent.tool.ToolErrorCode;

import java.util.Map;

public final class AgentProgress {
    private boolean successfulPatch;
    private boolean runTest;
    private Boolean lastTestPassed;
    private boolean lastToolFailed;
    private String lastModifiedFile;
    private int lastPatchStep;
    private int lastTestFailureStep;
    private boolean rereadModifiedFileAfterFailure;
    private int contextActionsAfterFailure;
    private int noEffectPatchCount;
    private int rereadAfterFailureCount;
    private String latestDiagnosticType;
    private String latestDiagnosticSummary;
    private AgentPlan plan;

    public void observe(
            String toolName,
            Map<String, Object> arguments,
            ToolResult result,
            int stepIndex
    ) {
        lastToolFailed = !result.success();
        if (isEditTool(toolName)
                && result.errorCode() == ToolErrorCode.NO_EFFECT_CHANGE) {
            noEffectPatchCount++;
        }
        if (isEditTool(toolName)
                && result.success()
                && Boolean.TRUE.equals(result.metadata().get("changed"))) {
            successfulPatch = true;
            lastModifiedFile = text(arguments.get("path"));
            lastPatchStep = stepIndex;
            contextActionsAfterFailure = 0;
        }
        if ("run_maven_test".equals(toolName)) {
            runTest = true;
            lastTestPassed = result.success();
            if (!result.success() && result.errorCode() == ToolErrorCode.TEST_FAILED) {
                lastTestFailureStep = stepIndex;
                rereadModifiedFileAfterFailure = false;
                contextActionsAfterFailure = 0;
                latestDiagnosticType = text(result.metadata().get("diagnosticType"));
                latestDiagnosticSummary = text(result.metadata().get("diagnosticSummary"));
            }
        } else if (Boolean.FALSE.equals(lastTestPassed)
                && ("read_file".equals(toolName) || "search_code".equals(toolName))) {
            contextActionsAfterFailure++;
            if ("read_file".equals(toolName)
                    && lastModifiedFile != null
                    && lastModifiedFile.equals(text(arguments.get("path")))
                    && !rereadModifiedFileAfterFailure) {
                rereadModifiedFileAfterFailure = true;
                rereadAfterFailureCount++;
            }
        }
    }

    public boolean hasSuccessfulPatch() {
        return successfulPatch;
    }

    public boolean hasRunTest() {
        return runTest;
    }

    public Boolean lastTestPassed() {
        return lastTestPassed;
    }

    public boolean lastToolFailed() {
        return lastToolFailed;
    }

    public boolean needsCurrentFileReread() {
        return Boolean.FALSE.equals(lastTestPassed)
                && lastModifiedFile != null
                && !rereadModifiedFileAfterFailure;
    }

    public int contextActionsAfterFailure() {
        return contextActionsAfterFailure;
    }

    public String latestDiagnosticSummary() {
        return latestDiagnosticSummary;
    }

    public String latestDiagnosticType() {
        return latestDiagnosticType;
    }

    public String lastModifiedFile() {
        return lastModifiedFile;
    }

    public int lastPatchStep() {
        return lastPatchStep;
    }

    public int lastTestFailureStep() {
        return lastTestFailureStep;
    }

    public boolean hasRereadModifiedFileAfterFailure() {
        return rereadModifiedFileAfterFailure;
    }

    public int noEffectPatchCount() {
        return noEffectPatchCount;
    }

    public int rereadAfterFailureCount() {
        return rereadAfterFailureCount;
    }

    public void updatePlan(AgentPlan updatedPlan) {
        plan = java.util.Objects.requireNonNull(updatedPlan, "updatedPlan must not be null");
    }

    public AgentPlan plan() {
        return plan;
    }

    public int completedRequirementCount() {
        return plan == null ? 0 : plan.completedRequirementCount();
    }

    public int remainingRequirementCount() {
        return plan == null ? 0 : plan.remainingRequirementCount();
    }

    public String currentFocus() {
        return plan == null ? "" : plan.currentFocus();
    }

    public String compactPlanContext() {
        if (plan == null) {
            return "Current Progress\nPlan: not created. Before editing, provide a compact "
                    + "<plan_update> JSON plan covering every independent task requirement.";
        }
        String completed = plan.requirements().stream()
                .filter(requirement -> requirement.status() == RequirementStatus.COMPLETED)
                .map(requirement -> requirement.id() + ": " + requirement.description())
                .collect(java.util.stream.Collectors.joining("; "));
        String remaining = plan.remainingRequirements().stream()
                .map(requirement -> requirement.id() + ": " + requirement.description()
                        + " [" + requirement.status() + "]")
                .collect(java.util.stream.Collectors.joining("; "));
        return "Current Progress\nGoal: " + plan.goal()
                + "\nFocus: " + plan.currentFocus()
                + "\nCompleted: " + (completed.isBlank() ? "none" : completed)
                + "\nRemaining: " + (remaining.isBlank() ? "none" : remaining);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isEditTool(String toolName) {
        return "apply_patch".equals(toolName) || "replace_lines".equals(toolName);
    }
}
