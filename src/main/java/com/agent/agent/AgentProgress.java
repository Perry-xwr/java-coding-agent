package com.agent.agent;

import com.agent.tool.ToolResult;
import com.agent.tool.ToolErrorCode;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.DiagnosticLocation;
import com.agent.environment.verification.VerificationCapability;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;

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
    private boolean convergenceGuidancePending;
    private final Set<String> pathsRequiringFreshRead = new HashSet<>();
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
    private long mutationSequence;
    private final Map<String, Long> mutationSequences = new HashMap<>();
    private final Map<String, VerificationResult> verificationResults = new HashMap<>();
    private final Map<String, VerificationFailureContext> verificationFailures = new HashMap<>();
    private final Map<String, Integer> verificationFailureCounts = new HashMap<>();
    private final Map<String, Integer> repairMutationCounts = new HashMap<>();
    private final Map<String, Integer> totalRepairMutationCounts = new HashMap<>();
    private final Map<String, Integer> successfulRecoveryCounts = new HashMap<>();
    private final Map<String, String> lastMutationTools = new HashMap<>();
    private final Map<String, Integer> lastSuccessfulReadSteps = new HashMap<>();
    private final Map<String, String> lastSuccessfulReadIdentities = new HashMap<>();

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
            if (lastModifiedFile != null && !lastModifiedFile.isBlank()) {
                long sequence = ++mutationSequence;
                String key = pathKey(lastModifiedFile);
                mutationSequences.put(key, sequence);
                lastMutationTools.put(key, toolName);
                if (verificationFailures.containsKey(key)
                        && lastSuccessfulReadSteps.getOrDefault(key, 0)
                        > verificationFailures.get(key).failureStep()) {
                    repairMutationCounts.merge(key, 1, Integer::sum);
                    totalRepairMutationCounts.merge(key, 1, Integer::sum);
                }
                verificationResults.remove(key);
            }
            verificationRequired = true;
            postMutationReadSeen = false;
            convergenceGuidancePending = true;
            String changedPath = text(arguments.get("path"));
            if (changedPath != null && !changedPath.isBlank()) {
                pathsRequiringFreshRead.add(pathKey(changedPath));
            }
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
                String key = pathKey(path);
                lastSuccessfulReadSteps.put(key, stepIndex);
                lastSuccessfulReadIdentities.put(key, "step:" + stepIndex + ":hash:" + result.output().hashCode());
                pathsRequiringFreshRead.remove(pathKey(path));
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

    /** Returns true once after the latest successful mutation has been reread. */
    public boolean consumeConvergenceGuidanceAfterReread() {
        if (!convergenceGuidancePending || !postMutationReadSeen) {
            return false;
        }
        convergenceGuidancePending = false;
        return true;
    }

    /** A successful mutation invalidates prior edit context for that exact workspace path. */
    public boolean requiresFreshReadBeforeMutation(String path) {
        return path != null && !path.isBlank() && pathsRequiringFreshRead.contains(pathKey(path));
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

    public boolean latestJavaMutationRequiresMavenVerification() {
        if (!lastMutationIsJavaSource()) {
            return false;
        }
        VerificationResult result = verificationResults.get(pathKey(lastModifiedFile));
        return result == null || "none".equals(result.verifierId())
                || "java-maven-project".equals(result.verifierId());
    }

    public long currentMutationSequence(String path) {
        return path == null ? 0 : mutationSequences.getOrDefault(pathKey(path), 0L);
    }

    public void observeVerification(VerificationResult result) {
        observeVerification(result, "unknown", "unknown", VerificationCapability.unknown(),
                DiagnosticLocation.unknown(), 0);
    }

    public VerificationFailureContext observeVerification(
            VerificationResult result, String mutationTool, String rereadIdentity,
            VerificationCapability capability, DiagnosticLocation location, int failureStep
    ) {
        if (result == null || result.file() == null) {
            return null;
        }
        String key = pathKey(result.file().toString());
        long currentSequence = mutationSequences.getOrDefault(key, 0L);
        if (currentSequence != 0 && currentSequence == result.mutationSequence()) {
            verificationResults.put(key, result);
            if (result.status() == VerificationStatus.FAIL) {
                int count = verificationFailureCounts.merge(key, 1, Integer::sum);
                VerificationFailureContext context = new VerificationFailureContext(
                        result.file().toString(), result.verifierId(), result.status(),
                        result.diagnosticSummary(), location == null ? DiagnosticLocation.unknown() : location,
                        result.mutationSequence(),
                        lastMutationTools.getOrDefault(key, mutationTool),
                        lastSuccessfulReadIdentities.getOrDefault(key, rereadIdentity), capability, count,
                        failureStep);
                verificationFailures.put(key, context);
                repairMutationCounts.put(key, 0);
                return context;
            }
            if (result.status() == VerificationStatus.PASS) {
                VerificationFailureContext failed = verificationFailures.get(key);
                if (failed != null && repairMutationCounts.getOrDefault(key, 0) > 0) {
                    successfulRecoveryCounts.merge(key, 1, Integer::sum);
                }
                verificationFailures.remove(key);
                repairMutationCounts.remove(key);
            } else if (result.status() == VerificationStatus.UNAVAILABLE
                    || result.status() == VerificationStatus.NOT_APPLICABLE) {
                verificationFailures.remove(key);
                repairMutationCounts.remove(key);
            }
        }
        return null;
    }

    public boolean requiresRepairRead(String path) {
        if (path == null) return false;
        VerificationFailureContext failure = verificationFailures.get(pathKey(path));
        return failure != null && lastSuccessfulReadSteps.getOrDefault(pathKey(path), 0) <= failure.failureStep();
    }

    public VerificationFailureContext verificationFailure(String path) {
        return path == null ? null : verificationFailures.get(pathKey(path));
    }

    public int verificationFailureCount(String path) {
        return path == null ? 0 : verificationFailureCounts.getOrDefault(pathKey(path), 0);
    }
    public int repairMutationCount(String path) {
        return path == null ? 0 : totalRepairMutationCounts.getOrDefault(pathKey(path), 0);
    }
    public int successfulRecoveryCount(String path) {
        return path == null ? 0 : successfulRecoveryCounts.getOrDefault(pathKey(path), 0);
    }

    public boolean hasCurrentVerificationFailure() {
        return verificationResults.entrySet().stream().anyMatch(entry ->
                mutationSequences.getOrDefault(entry.getKey(), 0L) == entry.getValue().mutationSequence()
                        && entry.getValue().status() == VerificationStatus.FAIL);
    }

    public String currentVerificationFailureKey() {
        return currentVerificationResults().stream()
                .filter(result -> result.status() == VerificationStatus.FAIL)
                .map(result -> pathKey(result.file().toString()) + "#" + result.mutationSequence())
                .sorted().collect(java.util.stream.Collectors.joining("|"))
                + "|recoveries=" + successfulRecoveryCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public List<VerificationResult> currentUnavailableVerifications() {
        return currentVerificationResults().stream()
                .filter(result -> result.status() == VerificationStatus.UNAVAILABLE)
                .toList();
    }

    public List<VerificationResult> currentVerificationResults() {
        return verificationResults.entrySet().stream()
                .filter(entry -> mutationSequences.getOrDefault(entry.getKey(), 0L)
                        == entry.getValue().mutationSequence())
                .map(Map.Entry::getValue)
                .toList();
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

    public TaskRequirement startNextPlanStep() {
        if (plan == null) {
            return null;
        }
        for (int index = 0; index < plan.requirements().size(); index++) {
            TaskRequirement requirement = plan.requirements().get(index);
            if (requirement.status() == RequirementStatus.PENDING) {
                TaskRequirement started = new TaskRequirement(requirement.id(), requirement.description(),
                        RequirementStatus.IN_PROGRESS, "");
                replaceRequirement(index, started, started.id());
                return started;
            }
        }
        return null;
    }

    /** Records successful action execution only; it is not semantic or verification evidence. */
    public TaskRequirement completeCurrentPlanStep(String toolName, String actionEvidence) {
        if (!isRelevantPlanAction(toolName)) {
            return null;
        }
        return changeCurrentPlanStep(RequirementStatus.COMPLETED, actionEvidence);
    }

    public TaskRequirement blockCurrentPlanStep(String actionEvidence) {
        return changeCurrentPlanStep(RequirementStatus.BLOCKED, actionEvidence);
    }

    public void resetBlockedPlanStep() {
        if (plan == null) {
            return;
        }
        for (int index = 0; index < plan.requirements().size(); index++) {
            TaskRequirement requirement = plan.requirements().get(index);
            if (requirement.status() == RequirementStatus.BLOCKED) {
                TaskRequirement pending = new TaskRequirement(requirement.id(), requirement.description(),
                        RequirementStatus.PENDING, "");
                replaceRequirement(index, pending, pending.id());
                return;
            }
        }
    }

    private TaskRequirement changeCurrentPlanStep(RequirementStatus status, String evidence) {
        if (plan == null) {
            return null;
        }
        for (int index = 0; index < plan.requirements().size(); index++) {
            TaskRequirement requirement = plan.requirements().get(index);
            if (requirement.status() == RequirementStatus.IN_PROGRESS) {
                TaskRequirement updated = new TaskRequirement(requirement.id(), requirement.description(),
                        status, status == RequirementStatus.COMPLETED ? evidence : "");
                replaceRequirement(index, updated, "");
                return updated;
            }
        }
        return null;
    }

    private boolean isRelevantPlanAction(String toolName) {
        if (plan == null || toolName == null) {
            return false;
        }
        String focus = plan.requirements().stream()
                .filter(requirement -> requirement.status() == RequirementStatus.IN_PROGRESS)
                .map(TaskRequirement::description).findFirst().orElse("")
                .toLowerCase(java.util.Locale.ROOT);
        return switch (toolName) {
            case "list_files", "find_files", "read_file", "search_code" ->
                    containsAny(focus, "read", "inspect", "review", "locate", "find", "search", "check", "confirm",
                            "查看", "读取", "阅读", "搜索", "查找", "定位", "检查", "确认", "分析");
            case "apply_patch", "replace_lines", "insert_before", "insert_after", "create_file" ->
                    containsAny(focus, "edit", "modify", "change", "fix", "repair", "update", "implement",
                            "add", "create", "write", "replace", "patch", "编辑", "修改", "修复", "创建",
                            "新建", "新增", "添加", "实现", "写入", "替换", "更新");
            case "run_maven_test" -> containsAny(focus, "test", "verify", "validation", "validate", "build",
                    "测试", "验证", "校验", "构建");
            default -> false;
        };
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void replaceRequirement(int index, TaskRequirement replacement, String focus) {
        java.util.ArrayList<TaskRequirement> requirements = new java.util.ArrayList<>(plan.requirements());
        requirements.set(index, replacement);
        String currentFocus = focus;
        if (currentFocus == null || currentFocus.isBlank()) {
            currentFocus = requirements.stream()
                    .filter(requirement -> requirement.status() == RequirementStatus.IN_PROGRESS
                            || requirement.status() == RequirementStatus.PENDING)
                    .findFirst().map(TaskRequirement::id).orElse("");
        }
        plan = new AgentPlan(plan.goal(), requirements, currentFocus, "");
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

    public String compactExecutionPlanContext() {
        if (plan == null) {
            return "Current execution plan unavailable; continue with the original task using observed evidence.";
        }
        String steps = plan.requirements().stream()
                .map(requirement -> requirement.id() + ": " + requirement.description()
                        + " [" + requirement.status() + "]")
                .collect(java.util.stream.Collectors.joining("; "));
        return "Current Execution Plan (guidance only; statuses track actions, not semantic verification)"
                + "\nGoal: " + plan.goal() + "\nSteps: " + steps
                + "\nCurrent focus: " + plan.currentFocus()
                + "\nFollow the latest tool observations and existing runtime verification requirements.";
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String pathKey(String path) {
        try {
            return java.nio.file.Path.of(path).normalize().toString();
        } catch (java.nio.file.InvalidPathException exception) {
            return path;
        }
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
        return "apply_patch".equals(toolName)
                || "replace_lines".equals(toolName)
                || "insert_before".equals(toolName)
                || "insert_after".equals(toolName);
    }

    private static boolean isWorkspaceMutationTool(String toolName) {
        return "apply_patch".equals(toolName)
                || "replace_lines".equals(toolName)
                || "insert_before".equals(toolName)
                || "insert_after".equals(toolName)
                || "create_file".equals(toolName);
    }
}
