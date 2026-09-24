package com.agent.benchmark;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.TerminationReason;
import com.agent.tool.ToolErrorCode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class BenchmarkMetricsCalculator {
    public BenchmarkMetrics calculate(List<BenchmarkRunRecord> records) {
        int total = records.size();
        List<BenchmarkRunRecord> evaluable = records.stream()
                .filter(record -> !record.evaluatorError())
                .toList();
        int successes = (int) evaluable.stream().filter(record -> record.evaluation().success()).count();
        int completed = (int) records.stream().filter(record -> record.run().trajectory().completed()).count();
        List<AgentStep> allTools = records.stream().flatMap(record -> toolSteps(record).stream()).toList();
        long invalidCalls = allTools.stream().filter(step -> hasError(
                step,
                ToolErrorCode.TOOL_NOT_FOUND,
                ToolErrorCode.INVALID_ARGUMENTS
        )).count();
        long testFailures = allTools.stream()
                .filter(step -> hasError(step, ToolErrorCode.TEST_FAILED))
                .count();
        long diagnosticFailures = allTools.stream()
                .filter(step -> hasError(step, ToolErrorCode.TEST_FAILED))
                .filter(step -> step.toolResult().metadata().containsKey("diagnosticType"))
                .count();
        long noEffectPatches = allTools.stream()
                .filter(step -> hasError(step, ToolErrorCode.NO_EFFECT_CHANGE))
                .count();
        List<BenchmarkRunRecord> recoveryCandidates = records.stream()
                .filter(record -> toolSteps(record).stream()
                        .anyMatch(step -> hasError(step, ToolErrorCode.TEST_FAILED)))
                .toList();
        long recovered = recoveryCandidates.stream()
                .filter(record -> !record.evaluatorError() && record.evaluation().success())
                .count();
        long recoveryAttempts = recoveryCandidates.stream()
                .mapToLong(this::recoveryAttempts)
                .sum();
        long maxSteps = records.stream().filter(record ->
                record.run().trajectory().terminationReason() == TerminationReason.MAX_STEPS
        ).count();
        List<AgentStep> feedbackSteps = records.stream()
                .flatMap(record -> record.run().trajectory().steps().stream())
                .filter(step -> step.actionType() == AgentActionType.RUNTIME_FEEDBACK)
                .toList();
        long noPatchGuards = feedbackSteps.stream()
                .filter(step -> hasFeedback(step, "PREMATURE_FINAL_GUARD:"))
                .count();
        long validationGuards = feedbackSteps.stream()
                .filter(step -> hasFeedback(step, "VALIDATION_GUARD:"))
                .count();
        long failedTestGuards = feedbackSteps.stream()
                .filter(step -> hasFeedback(step, "TEST_FAILED_GUARD:"))
                .count();
        long repeatedWarnings = feedbackSteps.stream()
                .filter(step -> hasFeedback(step, "REPEATED_ACTION_WARNING:"))
                .count();
        long budgetWarnings = feedbackSteps.stream()
                .filter(step -> hasFeedback(step, "STEP_BUDGET_WARNING:"))
                .count();
        long plansCreated = countActions(records, AgentActionType.PLAN_CREATED);
        long planUpdates = countActions(records, AgentActionType.PLAN_UPDATED);
        long planCompletionWarnings = countActions(
                records,
                AgentActionType.PLAN_COMPLETION_FEEDBACK
        );
        long requirementsTotal = records.stream()
                .map(record -> record.run().trajectory().plan())
                .filter(java.util.Objects::nonNull)
                .mapToLong(plan -> plan.requirements().size())
                .sum();
        long requirementsCompleted = records.stream()
                .map(record -> record.run().trajectory().plan())
                .filter(java.util.Objects::nonNull)
                .mapToLong(com.agent.agent.AgentPlan::completedRequirementCount)
                .sum();
        long requirementsRemainingAtFinal = records.stream()
                .filter(record -> record.run().trajectory().completed())
                .map(record -> record.run().trajectory().plan())
                .filter(java.util.Objects::nonNull)
                .mapToLong(com.agent.agent.AgentPlan::remainingRequirementCount)
                .sum();
        List<AgentStep> exactPatches = allTools.stream()
                .filter(step -> "apply_patch".equals(step.toolName()))
                .toList();
        List<AgentStep> lineEdits = allTools.stream()
                .filter(step -> "replace_lines".equals(step.toolName()))
                .toList();
        long exactPatchFailures = exactPatches.stream()
                .filter(step -> step.toolResult() != null && !step.toolResult().success())
                .count();
        long lineEditSuccesses = lineEdits.stream()
                .filter(step -> step.toolResult() != null && step.toolResult().success())
                .count();
        long staleEditFailures = lineEdits.stream()
                .filter(step -> hasError(step, ToolErrorCode.STALE_EDIT_CONTEXT))
                .count();
        long patchFallbackCount = records.stream().mapToLong(this::patchFallbacks).sum();
        long rereadsAfterFailure = records.stream()
                .mapToLong(this::rereadsAfterTestFailure)
                .sum();
        long prematureFinalAttempts = noPatchGuards + validationGuards + failedTestGuards;
        List<BenchmarkRunRecord> requiredToolTasks = records.stream()
                .filter(record -> !record.task().requiredTools().isEmpty())
                .toList();
        long requiredToolsUsed = requiredToolTasks.stream().filter(this::usedRequiredTools).count();

        return new BenchmarkMetrics(
                total,
                evaluable.size(),
                successes,
                total - evaluable.size(),
                rate(successes, evaluable.size()),
                rate(completed, total),
                average(records, record -> record.run().trajectory().steps().size()),
                average(
                        evaluable.stream().filter(record -> record.evaluation().success()).toList(),
                        record -> record.run().trajectory().steps().size()
                ),
                rate(allTools.size(), total),
                rate(invalidCalls, allTools.size()),
                testFailures,
                diagnosticFailures,
                recovered,
                recoveryAttempts,
                rate(recovered, recoveryCandidates.size()),
                prematureFinalAttempts,
                prematureFinalAttempts,
                validationGuards,
                repeatedWarnings,
                noEffectPatches,
                rereadsAfterFailure,
                budgetWarnings,
                plansCreated,
                planUpdates,
                requirementsTotal,
                requirementsCompleted,
                requirementsRemainingAtFinal,
                planCompletionWarnings,
                exactPatches.size(),
                exactPatchFailures,
                lineEdits.size(),
                lineEditSuccesses,
                lineEdits.size() - lineEditSuccesses,
                staleEditFailures,
                patchFallbackCount,
                rate(maxSteps, total),
                average(records, record -> record.run().trajectory().durationMs()),
                rate(requiredToolsUsed, requiredToolTasks.size()),
                toolUsage(allTools),
                breakdown(evaluable, record -> record.task().category().name()),
                breakdown(evaluable, record -> record.task().difficulty().name()),
                failureCounts(records)
        );
    }

    private static long countActions(
            List<BenchmarkRunRecord> records,
            AgentActionType actionType
    ) {
        return records.stream()
                .flatMap(record -> record.run().trajectory().steps().stream())
                .filter(step -> step.actionType() == actionType)
                .count();
    }

    private Map<String, ToolUsageMetrics> toolUsage(List<AgentStep> steps) {
        Map<String, ToolUsageMetrics> result = new LinkedHashMap<>();
        for (String tool : List.of(
                "read_file", "list_files", "search_code", "apply_patch", "run_maven_test"
                , "replace_lines"
        )) {
            List<AgentStep> matching = steps.stream()
                    .filter(step -> tool.equals(step.toolName()))
                    .toList();
            int successful = (int) matching.stream()
                    .filter(step -> step.toolResult() != null && step.toolResult().success())
                    .count();
            result.put(tool, new ToolUsageMetrics(
                    matching.size(),
                    successful,
                    matching.size() - successful,
                    rate(successful, matching.size())
            ));
        }
        return Map.copyOf(result);
    }

    private long patchFallbacks(BenchmarkRunRecord record) {
        boolean failedExactPatch = false;
        long fallbacks = 0;
        for (AgentStep step : toolSteps(record)) {
            if ("apply_patch".equals(step.toolName()) && (hasError(
                    step, ToolErrorCode.TEXT_NOT_FOUND, ToolErrorCode.MULTIPLE_MATCHES
            ))) {
                failedExactPatch = true;
            } else if (failedExactPatch && "replace_lines".equals(step.toolName())) {
                fallbacks++;
                failedExactPatch = false;
            }
        }
        return fallbacks;
    }

    private Map<String, BreakdownMetrics> breakdown(
            List<BenchmarkRunRecord> records,
            Function<BenchmarkRunRecord, String> classifier
    ) {
        Map<String, List<BenchmarkRunRecord>> groups = records.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        classifier,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        Map<String, BreakdownMetrics> result = new LinkedHashMap<>();
        groups.forEach((name, group) -> {
            int successful = (int) group.stream().filter(record -> record.evaluation().success()).count();
            result.put(name, new BreakdownMetrics(
                    group.size(),
                    successful,
                    rate(successful, group.size()),
                    average(group, record -> record.run().trajectory().steps().size())
            ));
        });
        return Map.copyOf(result);
    }

    private Map<String, Long> failureCounts(List<BenchmarkRunRecord> records) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FailureCategory category : FailureCategory.values()) {
            long count = records.stream()
                    .filter(record -> record.failureCategory() == category)
                    .count();
            if (count > 0) {
                counts.put(category.name(), count);
            }
        }
        return Map.copyOf(counts);
    }

    private boolean usedRequiredTools(BenchmarkRunRecord record) {
        List<String> used = toolSteps(record).stream().map(AgentStep::toolName).toList();
        return record.task().requiredTools().stream().allMatch(required ->
                used.contains(required)
                        || ("apply_patch".equals(required) && used.contains("replace_lines"))
        );
    }

    private long recoveryAttempts(BenchmarkRunRecord record) {
        boolean failureSeen = false;
        long attempts = 0;
        for (AgentStep step : toolSteps(record)) {
            if (hasError(step, ToolErrorCode.TEST_FAILED)) {
                failureSeen = true;
            } else if (failureSeen && (isEditTool(step.toolName())
                    || "run_maven_test".equals(step.toolName()))) {
                attempts++;
            }
        }
        return attempts;
    }

    private long rereadsAfterTestFailure(BenchmarkRunRecord record) {
        String lastModifiedFile = null;
        boolean failedTest = false;
        boolean countedForFailure = false;
        long count = 0;
        for (AgentStep step : toolSteps(record)) {
            if (isEditTool(step.toolName())
                    && step.toolResult() != null
                    && step.toolResult().success()
                    && Boolean.TRUE.equals(step.toolResult().metadata().get("changed"))) {
                Object path = step.arguments().get("path");
                lastModifiedFile = path == null ? null : path.toString();
            } else if (hasError(step, ToolErrorCode.TEST_FAILED)) {
                failedTest = true;
                countedForFailure = false;
            } else if (failedTest
                    && !countedForFailure
                    && "read_file".equals(step.toolName())
                    && lastModifiedFile != null
                    && lastModifiedFile.equals(String.valueOf(step.arguments().get("path")))) {
                count++;
                countedForFailure = true;
            }
        }
        return count;
    }

    private static boolean isEditTool(String toolName) {
        return "apply_patch".equals(toolName) || "replace_lines".equals(toolName);
    }

    private static List<AgentStep> toolSteps(BenchmarkRunRecord record) {
        return record.run().trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .toList();
    }

    private static boolean hasError(AgentStep step, ToolErrorCode... codes) {
        return step.toolResult() != null
                && !step.toolResult().success()
                && Arrays.asList(codes).contains(step.toolResult().errorCode());
    }

    private static boolean hasFeedback(AgentStep step, String prefix) {
        return step.errorMessage() != null && step.errorMessage().startsWith(prefix);
    }

    private static double average(
            List<BenchmarkRunRecord> records,
            java.util.function.ToLongFunction<BenchmarkRunRecord> value
    ) {
        return records.stream().mapToLong(value).average().orElse(0.0);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
