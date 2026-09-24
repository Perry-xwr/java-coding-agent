package com.agent.agent;

import com.agent.tool.ToolResult;
import com.agent.tool.ToolErrorCode;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class AgentProgress {
    private boolean workspaceMutationSucceeded;
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
    private int lastMutationFailureStep;
    private int lastSuccessfulReadStep;
    private boolean verificationRequired;
    private boolean postMutationReadSeen;
    private boolean postMutationTestSeen;
    private Boolean postMutationTestPassed;
    private ToolErrorCode postMutationTestErrorCode;
    private String consecutiveEditFailurePath;
    private int consecutiveEditFailures;
    private String requiredRereadPath;
    private final Set<String> successfullyReadPaths = new HashSet<>();
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
        if (isWorkspaceMutationTool(toolName)
                && result.errorCode() == ToolErrorCode.NO_EFFECT_CHANGE) {
            noEffectPatchCount++;
        }
        if (result.success()
                && Boolean.TRUE.equals(result.metadata().get("changed"))) {
            workspaceMutationSucceeded = true;
            lastModifiedFile = text(arguments.get("path"));
            lastPatchStep = stepIndex;
            verificationRequired = true;
            postMutationReadSeen = false;
            postMutationTestSeen = false;
            postMutationTestPassed = null;
            postMutationTestErrorCode = null;
            contextActionsAfterFailure = 0;
        }
        if (isWorkspaceMutationTool(toolName) && !result.success()) {
            lastMutationFailureStep = stepIndex;
        }
        if (isEditTool(toolName)) {
            observeEditAttempt(text(arguments.get("path")), result);
        }
        if ("read_file".equals(toolName) && result.success()) {
            String path = text(arguments.get("path"));
            if (path != null) {
                successfullyReadPaths.add(path);
                lastSuccessfulReadStep = stepIndex;
                if (verificationRequired
                        && stepIndex > lastPatchStep
                        && path.equals(lastModifiedFile)) {
                    postMutationReadSeen = true;
                }
                if (path.equals(requiredRereadPath)) {
                    requiredRereadPath = null;
                    consecutiveEditFailures = 0;
                    consecutiveEditFailurePath = null;
                }
            }
        }
        if ("run_maven_test".equals(toolName)) {
            runTest = true;
            lastTestPassed = result.success();
            if (verificationRequired && stepIndex > lastPatchStep) {
                postMutationTestSeen = true;
                postMutationTestPassed = result.success();
                postMutationTestErrorCode = result.errorCode();
            }
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
        return workspaceMutationSucceeded;
    }

    public boolean hasSuccessfulMutation() {
        return workspaceMutationSucceeded;
    }

    public boolean hasReadEvidenceForNoChange() {
        return !successfullyReadPaths.isEmpty()
                && lastSuccessfulReadStep >= lastMutationFailureStep;
    }

    public boolean hasReadEvidenceAfter(int stepIndex) {
        return !successfullyReadPaths.isEmpty() && lastSuccessfulReadStep > stepIndex;
    }

    public boolean verificationRequired() {
        return verificationRequired;
    }

    public boolean postMutationReadSeen() {
        return postMutationReadSeen;
    }

    public boolean postMutationTestSeen() {
        return postMutationTestSeen;
    }

    public Boolean postMutationTestPassed() {
        return postMutationTestPassed;
    }

    public ToolErrorCode postMutationTestErrorCode() {
        return postMutationTestErrorCode;
    }

    public boolean lastMutationIsJavaSource() {
        return lastModifiedFile != null
                && lastModifiedFile.toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }

    public boolean requiresRereadBeforeEdit(String path) {
        return requiredRereadPath != null && requiredRereadPath.equals(path);
    }

    public String requiredRereadPath() {
        return requiredRereadPath;
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

    private void observeEditAttempt(String path, ToolResult result) {
        if (result.success()) {
            consecutiveEditFailurePath = null;
            consecutiveEditFailures = 0;
            requiredRereadPath = null;
            return;
        }
        if (path == null) {
            return;
        }
        if (path.equals(consecutiveEditFailurePath)) {
            consecutiveEditFailures++;
        } else {
            consecutiveEditFailurePath = path;
            consecutiveEditFailures = 1;
        }
        if (consecutiveEditFailures >= 2) {
            requiredRereadPath = path;
        }
    }

    private static boolean isEditTool(String toolName) {
        return "apply_patch".equals(toolName) || "replace_lines".equals(toolName);
    }

    private static boolean isWorkspaceMutationTool(String toolName) {
        return "apply_patch".equals(toolName)
                || "replace_lines".equals(toolName)
                || "create_file".equals(toolName);
    }
}
