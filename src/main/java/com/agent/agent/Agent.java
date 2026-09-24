package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolResult;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Agent {
    public static final int MAX_ITERATIONS = 10;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful coding assistant. Use tools when they are needed to inspect files.";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Message systemMessage;
    private final int maxIterations;
    private final TaskMode taskMode;
    private final boolean diagnosticRecovery;
    private final boolean planningEnabled;
    private final AgentEventListener eventListener;
    private final List<Message> history = new ArrayList<>();

    public Agent(LLMClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT);
    }

    public Agent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this(llmClient, toolRegistry, systemPrompt, MAX_ITERATIONS);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations
    ) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, TaskMode.READ_ONLY);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode
    ) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, taskMode, false);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery
    ) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, false);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            boolean planningEnabled
    ) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, planningEnabled, AgentEventListener.NO_OP);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            boolean planningEnabled,
            AgentEventListener eventListener
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
        this.taskMode = Objects.requireNonNull(taskMode, "taskMode must not be null");
        this.diagnosticRecovery = diagnosticRecovery;
        this.planningEnabled = planningEnabled;
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener must not be null");
        this.systemMessage = Message.system(
                Objects.requireNonNull(systemPrompt, "systemPrompt must not be null")
        );
        history.add(systemMessage);
    }

    public String run(String input) throws IOException {
        AgentRunResult result = runWithTrajectory(input);
        return switch (result.trajectory().terminationReason()) {
            case FINAL_ANSWER -> result.finalAnswer();
            case MAX_STEPS -> throw new IllegalStateException(
                    "Agent exceeded maximum iterations: " + maxIterations
            );
            case LLM_ERROR -> throw new IOException(lastError(result.trajectory()));
        };
    }

    public AgentRunResult runWithTrajectory(String input) {
        String task = Objects.requireNonNull(input, "input must not be null");
        String runId = UUID.randomUUID().toString();
        long runStartedAt = System.currentTimeMillis();
        long runStartedNanos = System.nanoTime();
        List<AgentStep> steps = new ArrayList<>();
        AgentProgress progress = new AgentProgress();
        int prematureFinalGuards = 0;
        int validationGuards = 0;
        int failedTestGuards = 0;
        int repeatedActionWarnings = 0;
        int patchFreshnessWarnings = 0;
        int searchChurnWarnings = 0;
        int budgetWarnings = 0;
        int planCompletionWarnings = 0;
        String previousFailedAction = null;
        int consecutiveIdenticalFailures = 0;

        history.add(Message.user(task));

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (diagnosticRecovery
                    && taskMode == TaskMode.CODE_MODIFICATION
                    && progress.needsCurrentFileReread()
                    && patchFreshnessWarnings < 1) {
                patchFreshnessWarnings++;
                String feedback = "PATCH_FRESHNESS_WARNING: The previous edit caused or did not "
                        + "resolve a test failure. Re-read the current version of "
                        + progress.lastModifiedFile()
                        + " before constructing another patch.";
                steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                history.add(Message.system(feedback));
            }
            if (diagnosticRecovery
                    && taskMode == TaskMode.CODE_MODIFICATION
                    && iteration == maxIterations - 2
                    && budgetWarnings < 1) {
                budgetWarnings++;
                String feedback = "STEP_BUDGET_WARNING: You are approaching the step limit. "
                        + "Prioritize resolving the current concrete failure and validating the fix.";
                steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                history.add(Message.system(feedback));
            }
            long llmStartedAt = System.currentTimeMillis();
            long llmStartedNanos = System.nanoTime();
            LLMResponse response;
            try {
                response = llmClient.chat(
                        decisionMessages(progress),
                        toolRegistry.definitions()
                );
            } catch (IOException exception) {
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentActionType.ERROR,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        messageOrType(exception),
                        llmStartedAt,
                        elapsedMs(llmStartedNanos)
                ));
                return result(
                        runId,
                        task,
                        steps,
                        null,
                        TerminationReason.LLM_ERROR,
                        false,
                        runStartedAt,
                        runStartedNanos,
                        progress.plan()
                );
            }
            String rawContent = response.content() == null ? "" : response.content();
            AgentPlanCodec.ParsedContent parsedContent = planningEnabled
                    && taskMode == TaskMode.CODE_MODIFICATION
                    ? AgentPlanCodec.parse(rawContent)
                    : new AgentPlanCodec.ParsedContent(null, rawContent);
            String content = parsedContent.visibleContent();
            if (parsedContent.plan() != null) {
                boolean created = progress.plan() == null;
                progress.updatePlan(parsedContent.plan());
                steps.add(planStep(
                        steps.size() + 1,
                        created ? AgentActionType.PLAN_CREATED : AgentActionType.PLAN_UPDATED,
                        parsedContent.plan()
                ));
            }

            List<ToolCall> toolCalls = response.toolCalls();
            history.add(Message.assistant(rawContent, toolCalls));
            if (toolCalls.isEmpty()) {
                String feedback = null;
                if (taskMode == TaskMode.CODE_MODIFICATION) {
                    if (!progress.hasSuccessfulPatch() && prematureFinalGuards < 1) {
                        prematureFinalGuards++;
                        feedback = "PREMATURE_FINAL_GUARD: The task requires an actual workspace "
                                + "modification, but no file has been successfully changed. Do not only "
                                + "describe or show the proposed fix. Use apply_patch, then validate it.";
                    } else if (progress.hasRunTest()
                            && Boolean.FALSE.equals(progress.lastTestPassed())
                            && failedTestGuards < 1) {
                        failedTestGuards++;
                        feedback = "TEST_FAILED_GUARD: The latest test execution failed. Key diagnostic: "
                                + diagnosticOrFallback(progress)
                                + ". Resolve this concrete failure before declaring completion; reread "
                                + "the current source, make a targeted repair, and retest.";
                    } else if (planningEnabled
                            && (progress.plan() == null
                            || progress.remainingRequirementCount() > 0)
                            && planCompletionWarnings < 1) {
                        planCompletionWarnings++;
                        feedback = planCompletionFeedback(progress);
                    } else if (progress.hasSuccessfulPatch()
                            && !progress.hasRunTest()
                            && validationGuards < 1) {
                        validationGuards++;
                        feedback = "VALIDATION_GUARD: The workspace was modified but has not been "
                                + "validated. Use run_maven_test when applicable before declaring completion.";
                    }
                }
                if (feedback != null) {
                    AgentStep feedbackStep = feedback.startsWith("PLAN_COMPLETION_FEEDBACK:")
                            ? planCompletionFeedbackStep(steps.size() + 1, content, feedback)
                            : runtimeFeedbackStep(steps.size() + 1, content, feedback);
                    steps.add(feedbackStep);
                    history.add(Message.system(feedback));
                    continue;
                }
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentActionType.FINAL_ANSWER,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        content,
                        null,
                        System.currentTimeMillis(),
                        0
                ));
                return result(
                        runId,
                        task,
                        steps,
                        content,
                        TerminationReason.FINAL_ANSWER,
                        true,
                        runStartedAt,
                        runStartedNanos,
                        progress.plan()
                );
            }

            for (ToolCall toolCall : toolCalls) {
                long toolStartedAt = System.currentTimeMillis();
                long toolStartedNanos = System.nanoTime();
                Map<String, Object> parsedArguments = parseArguments(toolCall.arguments());
                eventListener.toolStarted(toolCall.name(), parsedArguments);
                ToolResult toolResult = toolRegistry.execute(
                        toolCall.name(),
                        toolCall.arguments()
                );
                eventListener.toolFinished(toolCall.name(), toolResult);
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentActionType.TOOL_CALL,
                        toolCall.name(),
                        toolCall.id(),
                        toolCall.arguments(),
                        parsedArguments,
                        toolResult,
                        null,
                        null,
                        toolStartedAt,
                        elapsedMs(toolStartedNanos)
                ));
                progress.observe(
                        toolCall.name(),
                        parsedArguments,
                        toolResult,
                        steps.size()
                );
                history.add(Message.tool(toolCall.id(), serializeObservation(toolResult)));

                if (diagnosticRecovery
                        && progress.contextActionsAfterFailure() >= 3
                        && searchChurnWarnings < 1) {
                    searchChurnWarnings++;
                    String feedback = "SEARCH_CHURN_WARNING: You have gathered additional source "
                            + "context after the test failure but have not attempted a repair. Use the "
                            + "evidence to make a targeted change, or identify the specific missing information.";
                    steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                    history.add(Message.system(feedback));
                }

                String failedAction = toolCall.name() + "\n" + toolCall.arguments();
                if (!toolResult.success()) {
                    if (failedAction.equals(previousFailedAction)) {
                        consecutiveIdenticalFailures++;
                    } else {
                        previousFailedAction = failedAction;
                        consecutiveIdenticalFailures = 1;
                    }
                    if (taskMode == TaskMode.CODE_MODIFICATION
                            && consecutiveIdenticalFailures >= 2
                            && repeatedActionWarnings < 1) {
                        repeatedActionWarnings++;
                        String feedback = "REPEATED_ACTION_WARNING: The previous identical tool call "
                                + "already failed. Change the arguments or use another strategy.";
                        steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                        history.add(Message.system(feedback));
                    }
                } else {
                    previousFailedAction = null;
                    consecutiveIdenticalFailures = 0;
                }
            }
        }

        String error = "Agent exceeded maximum iterations: " + maxIterations;
        steps.add(new AgentStep(
                steps.size() + 1,
                AgentActionType.ERROR,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                error,
                System.currentTimeMillis(),
                0
        ));
        return result(
                runId,
                task,
                steps,
                null,
                TerminationReason.MAX_STEPS,
                false,
                runStartedAt,
                runStartedNanos,
                progress.plan()
        );
    }

    public List<Message> history() {
        return List.copyOf(history);
    }

    public void clearHistory() {
        history.clear();
        history.add(systemMessage);
    }

    private static AgentRunResult result(
            String runId,
            String task,
            List<AgentStep> steps,
            String finalAnswer,
            TerminationReason terminationReason,
            boolean completed,
            long startedAtEpochMs,
            long startedNanos,
            AgentPlan plan
    ) {
        AgentTrajectory trajectory = new AgentTrajectory(
                runId,
                task,
                steps,
                finalAnswer,
                terminationReason,
                completed,
                null,
                startedAtEpochMs,
                elapsedMs(startedNanos),
                plan
        );
        return new AgentRunResult(finalAnswer, trajectory);
    }

    private List<Message> decisionMessages(AgentProgress progress) {
        if (!planningEnabled || taskMode != TaskMode.CODE_MODIFICATION) {
            return List.copyOf(history);
        }
        List<Message> messages = new ArrayList<>(history);
        messages.add(Message.system(progress.compactPlanContext()));
        return List.copyOf(messages);
    }

    private static AgentStep planStep(int stepIndex, AgentActionType type, AgentPlan plan) {
        Map<String, Object> arguments = OBJECT_MAPPER.convertValue(
                plan,
                new TypeReference<Map<String, Object>>() { }
        );
        return new AgentStep(
                stepIndex, type, null, null, null, arguments, null,
                null, null, System.currentTimeMillis(), 0
        );
    }

    private static AgentStep planCompletionFeedbackStep(
            int stepIndex,
            String attemptedFinalAnswer,
            String feedback
    ) {
        return new AgentStep(
                stepIndex, AgentActionType.PLAN_COMPLETION_FEEDBACK, null, null, null,
                Map.of(), null, attemptedFinalAnswer, feedback,
                System.currentTimeMillis(), 0
        );
    }

    private static String planCompletionFeedback(AgentProgress progress) {
        if (progress.plan() == null) {
            return "PLAN_COMPLETION_FEEDBACK: No task plan has been recorded. Identify all "
                    + "independent requirements and provide a compact plan update before finalizing.";
        }
        String remaining = progress.plan().remainingRequirements().stream()
                .map(requirement -> "- " + requirement.id() + ": " + requirement.description())
                .collect(java.util.stream.Collectors.joining("\n"));
        return "PLAN_COMPLETION_FEEDBACK: Your current plan still contains unfinished "
                + "requirements:\n" + remaining
                + "\nA passing visible test does not prove these requirements are complete. "
                + "Review the task, update the workspace and plan evidence, then validate again.";
    }

    private static AgentStep runtimeFeedbackStep(
            int stepIndex,
            String attemptedFinalAnswer,
            String feedback
    ) {
        return new AgentStep(
                stepIndex,
                AgentActionType.RUNTIME_FEEDBACK,
                null,
                null,
                null,
                Map.of(),
                null,
                attemptedFinalAnswer,
                feedback,
                System.currentTimeMillis(),
                0
        );
    }

    private static Map<String, Object> parseArguments(String rawArguments) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(rawArguments);
            if (parsed != null && parsed.isObject()) {
                return OBJECT_MAPPER.convertValue(
                        parsed,
                        new TypeReference<Map<String, Object>>() { }
                );
            }
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // Raw arguments remain available on the step when parsing fails.
        }
        return Map.of();
    }

    private static String serializeObservation(ToolResult result) {
        if (result.success()) {
            return result.output();
        }
        if (result.errorCode() == ToolErrorCode.TEST_FAILED) {
            String summary = Objects.toString(
                    result.metadata().get("diagnosticSummary"),
                    "Maven test execution failed"
            );
            return "TEST FAILED\n\nKey diagnostics:\n" + summary
                    + "\n\nRelevant output:\n" + Objects.toString(result.output(), "")
                    + "\n\nError code: " + result.errorCode();
        }
        try {
            Map<String, Object> observation = new java.util.LinkedHashMap<>();
            observation.put("success", false);
            observation.put("errorCode", result.errorCode().name());
            observation.put("errorMessage", result.errorMessage());
            if (result.output() != null) {
                observation.put("output", result.output());
            }
            observation.put("metadata", result.metadata());
            return OBJECT_MAPPER.writeValueAsString(observation);
        } catch (JsonProcessingException exception) {
            return "Tool failed [" + result.errorCode() + "]: " + result.errorMessage();
        }
    }

    private static String diagnosticOrFallback(AgentProgress progress) {
        String summary = progress.latestDiagnosticSummary();
        return summary == null || summary.isBlank() ? "Maven test execution failed" : summary;
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String lastError(AgentTrajectory trajectory) {
        return trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.ERROR)
                .map(AgentStep::errorMessage)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse("LLM request failed");
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
