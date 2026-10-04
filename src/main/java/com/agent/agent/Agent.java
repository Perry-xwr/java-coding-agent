package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.llm.StreamingLlmClient;
import com.agent.environment.AgentEnvironment;
import com.agent.environment.ToolAvailabilityDecision;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
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
    private final AgentEnvironment environment;
    private final Message systemMessage;
    private final int maxIterations;
    private final TaskMode taskMode;
    private final boolean diagnosticRecovery;
    private final boolean planningEnabled;
    private final PlanningMode planningMode;
    private final AgentPlanner planner = new AgentPlanner();
    private final AdaptivePlanningRouter adaptivePlanningRouter = new AdaptivePlanningRouter();
    private final AgentEventListener eventListener;
    private final boolean streamingEnabled;
    private final VerificationRepairPolicy verificationRepairPolicy;
    private final WorkspaceChangeRequirementDetector workspaceChangeRequirementDetector =
            new WorkspaceChangeRequirementDetector();
    private final List<Message> history = new ArrayList<>();

    public Agent(LLMClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT);
    }

    public Agent(LLMClient llmClient, AgentEnvironment environment) {
        this(llmClient, environment, DEFAULT_SYSTEM_PROMPT, MAX_ITERATIONS, TaskMode.READ_ONLY,
                false, false, AgentEventListener.NO_OP, false);
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
                diagnosticRecovery, planningEnabled, AgentEventListener.NO_OP, false);
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
        this(llmClient, toolRegistry, systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, planningEnabled, eventListener, false);
    }

    public Agent(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            boolean planningEnabled,
            AgentEventListener eventListener,
            boolean streamingEnabled
    ) {
        this(llmClient, registryEnvironment(toolRegistry), systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, planningEnabled, eventListener, streamingEnabled);
    }

    public Agent(
            LLMClient llmClient,
            AgentEnvironment environment,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            boolean planningEnabled,
            AgentEventListener eventListener,
            boolean streamingEnabled
    ) {
        this(llmClient, environment, systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, planningEnabled, eventListener, streamingEnabled,
                PlanningMode.REACTIVE, VerificationRepairPolicy.GUIDED_REPAIR);
    }

    public Agent(
            LLMClient llmClient,
            AgentEnvironment environment,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            AgentEventListener eventListener,
            boolean streamingEnabled,
            PlanningMode planningMode
    ) {
        this(llmClient, environment, systemPrompt, maxIterations, taskMode,
                diagnosticRecovery, false, eventListener, streamingEnabled, planningMode,
                VerificationRepairPolicy.GUIDED_REPAIR);
    }

    public Agent(
            LLMClient llmClient,
            AgentEnvironment environment,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            AgentEventListener eventListener,
            boolean streamingEnabled,
            PlanningMode planningMode,
            VerificationRepairPolicy verificationRepairPolicy
    ) {
        this(llmClient, environment, systemPrompt, maxIterations, taskMode, diagnosticRecovery,
                false, eventListener, streamingEnabled, planningMode, verificationRepairPolicy);
    }

    private Agent(
            LLMClient llmClient,
            AgentEnvironment environment,
            String systemPrompt,
            int maxIterations,
            TaskMode taskMode,
            boolean diagnosticRecovery,
            boolean planningEnabled,
            AgentEventListener eventListener,
            boolean streamingEnabled,
            PlanningMode planningMode,
            VerificationRepairPolicy verificationRepairPolicy
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
        this.taskMode = Objects.requireNonNull(taskMode, "taskMode must not be null");
        this.diagnosticRecovery = diagnosticRecovery;
        this.planningEnabled = planningEnabled;
        this.planningMode = Objects.requireNonNull(planningMode, "planningMode must not be null");
        this.verificationRepairPolicy = Objects.requireNonNull(verificationRepairPolicy,
                "verificationRepairPolicy must not be null");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener must not be null");
        this.streamingEnabled = streamingEnabled;
        this.systemMessage = Message.system(
                Objects.requireNonNull(systemPrompt, "systemPrompt must not be null")
        );
        history.add(systemMessage);
    }

    private static AgentEnvironment registryEnvironment(ToolRegistry registry) {
        ToolRegistry checked = Objects.requireNonNull(registry, "toolRegistry must not be null");
        return new AgentEnvironment() {
            @Override
            public ToolResult execute(ToolCall toolCall) {
                Objects.requireNonNull(toolCall, "toolCall must not be null");
                return checked.execute(toolCall.name(), toolCall.arguments());
            }

            @Override
            public List<ToolDefinition> toolDefinitions() {
                return checked.definitions();
            }

            @Override
            public VerificationResult verifyPostEdit(String relativePath, long mutationSequence) {
                java.nio.file.Path path;
                try {
                    path = relativePath == null || relativePath.isBlank()
                            ? java.nio.file.Path.of(".") : java.nio.file.Path.of(relativePath).normalize();
                } catch (RuntimeException exception) {
                    path = java.nio.file.Path.of(".");
                }
                return new VerificationResult(VerificationStatus.NOT_APPLICABLE, path, "none", "",
                        "The compatibility ToolRegistry adapter does not provide code verification", mutationSequence);
            }
        };
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
        return runWithTrajectory(input, input);
    }

    /** Runs the composed execution input while routing from the original, current user turn. */
    public AgentRunResult runWithTrajectory(String input, String rawUserTask) {
        String task = Objects.requireNonNull(input, "input must not be null");
        String routingTask = Objects.requireNonNull(rawUserTask, "rawUserTask must not be null");
        String runId = UUID.randomUUID().toString();
        long runStartedAt = System.currentTimeMillis();
        long runStartedNanos = System.nanoTime();
        List<AgentStep> steps = new ArrayList<>();
        AgentProgress progress = new AgentProgress();
        WorkspaceChangeRequirementDetector.Requirement workspaceChangeRequirement =
                taskMode == TaskMode.CODE_MODIFICATION
                        ? workspaceChangeRequirementDetector.detect(routingTask)
                        : WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED;
        boolean workspaceChangeFeedbackAdded = false;
        int prematureFinalGuards = 0;
        int validationGuards = 0;
        int failedTestGuards = 0;
        int postMutationReadGuards = 0;
        int infrastructureTestWarnings = 0;
        int repeatedActionWarnings = 0;
        int patchFreshnessWarnings = 0;
        int searchChurnWarnings = 0;
        int budgetWarnings = 0;
        int planCompletionWarnings = 0;
        java.util.Set<String> postEditVerificationFailureFeedbackKeys = new java.util.HashSet<>();
        int unavailableVerificationWarnings = 0;
        int mutationGuardStep = 0;
        String previousFailedAction = null;
        int consecutiveIdenticalFailures = 0;
        if (taskMode == TaskMode.CODE_MODIFICATION && verificationRepairPolicy.guidedRepairEnabled()) {
            com.agent.environment.verification.VerificationCapability capability =
                    environment.verificationCapability("__capability_probe__.java");
            history.add(Message.system("WORKSPACE_VERIFICATION_CAPABILITY: workspace_kind="
                    + capability.workspaceKind() + "; Java verifier=" + capability.recommendedVerifier()
                    + (capability.workspaceKind()
                    == com.agent.environment.verification.VerificationCapability.WorkspaceKind.STANDALONE
                    ? "; Maven project not detected." : "")));
        }
        history.add(Message.user(task));

        PlanningMode effectivePlanningMode = planningMode;
        if (planningMode == PlanningMode.ADAPTIVE && taskMode == TaskMode.CODE_MODIFICATION) {
            AdaptivePlanningDecision decision = adaptivePlanningRouter.route(routingTask);
            effectivePlanningMode = decision.selectedMode();
            steps.add(adaptivePlanningStep(steps.size() + 1, decision));
        }
        boolean planExecute = effectivePlanningMode == PlanningMode.PLAN_EXECUTE
                && taskMode == TaskMode.CODE_MODIFICATION;
        boolean planningFallback = false;
        int replanCount = 0;
        if (planExecute) {
            try {
                AgentPlan plan = planner.create(llmClient, task);
                progress.updatePlan(plan);
                steps.add(planStep(steps.size() + 1, AgentActionType.PLAN_CREATED, plan));
                TaskRequirement started = progress.startNextPlanStep();
                if (started != null) {
                    steps.add(planStatusStep(steps.size() + 1, started, "STEP_STARTED", null));
                }
            } catch (IOException | IllegalArgumentException exception) {
                planningFallback = true;
                steps.add(planningFallbackStep(steps.size() + 1, "INITIAL_PLAN_FAILED",
                        exception.getClass().getSimpleName()));
                history.add(Message.system("PLAN_FALLBACK: planning was unavailable; continue with the "
                        + "existing reactive execution behavior."));
            }
        }

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
                response = completeDecision(decisionMessages(progress, planExecute));
            } catch (IOException exception) {
                appendPlanOutcome(steps, planExecute, planningFallback, progress.plan());
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
                if (workspaceChangeRequirement == WorkspaceChangeRequirementDetector.Requirement.REQUIRED
                        && !progress.hasSuccessfulMutation()) {
                    steps.add(new AgentStep(
                            steps.size() + 1,
                            AgentActionType.COMPLETION_GUARD,
                            null,
                            null,
                            null,
                            Map.of("reason", "WORKSPACE_CHANGE_REQUIRED", "currentTurnMutationSequence", 0),
                            null,
                            null,
                            "WORKSPACE_CHANGE_REQUIRED",
                            System.currentTimeMillis(),
                            0
                    ));
                    if (!workspaceChangeFeedbackAdded) {
                        history.add(Message.system("WORKSPACE_CHANGE_REQUIRED: This task explicitly requires "
                                + "a workspace change, but no successful workspace mutation has occurred "
                                + "during this user turn. Read the relevant workspace state and perform "
                                + "the requested change with an appropriate mutation tool before completing."));
                        workspaceChangeFeedbackAdded = true;
                    }
                    continue;
                }
                if (taskMode == TaskMode.CODE_MODIFICATION
                        && !progress.currentUnavailableVerifications().isEmpty()
                        && claimsVerificationPassed(content)) {
                    content = unavailableVerificationFinal(progress);
                }
                String feedback = null;
                if (taskMode == TaskMode.CODE_MODIFICATION) {
                    if (workspaceChangeRequirement == WorkspaceChangeRequirementDetector.Requirement.UNKNOWN
                            && !progress.hasSuccessfulMutation() && prematureFinalGuards < 1) {
                        prematureFinalGuards++;
                        mutationGuardStep = steps.size() + 1;
                        feedback = "PREMATURE_FINAL_GUARD: No workspace mutation succeeded. Do not "
                                + "claim that a file was created, modified, fixed, or updated. Continue "
                                + "with a recovery strategy. If the current file already satisfies the "
                                + "request, confirm that with read_file and explicitly report that no "
                                + "change was required; otherwise report the concrete failure honestly.";
                    } else if (workspaceChangeRequirement
                            == WorkspaceChangeRequirementDetector.Requirement.UNKNOWN
                            && !progress.hasSuccessfulMutation()
                            && !progress.hasReadEvidenceAfter(mutationGuardStep)) {
                        String failure = "Workspace modification failed: no write operation succeeded "
                                + "and no current file read confirmed that a change was unnecessary.";
                        return failedCompletion(
                                runId, task, steps, content, "MUTATION_FAILURE_FINAL", failure,
                                runStartedAt, runStartedNanos, progress.plan(), planExecute, planningFallback
                        );
                    } else if (progress.verificationRequired()
                            && !progress.postMutationReadSeen()
                            && postMutationReadGuards < 1) {
                        postMutationReadGuards++;
                        feedback = "POST_MUTATION_READ_GUARD: A workspace mutation succeeded, but the "
                                + "changed file has not been reread after the mutation. Read the exact "
                                + "changed file before claiming completion.";
                    } else if (progress.verificationRequired()
                            && !progress.postMutationReadSeen()) {
                        String failure = "Workspace modification was written but post-edit verification "
                                + "failed because the changed file was not reread.";
                        return failedCompletion(
                                runId, task, steps, content, "POST_MUTATION_READ_FAILURE", failure,
                                runStartedAt, runStartedNanos, progress.plan(), planExecute, planningFallback
                        );
                    } else if (progress.hasCurrentVerificationFailure()
                            && postEditVerificationFailureFeedbackKeys.add(progress.currentVerificationFailureKey())) {
                        feedback = postEditVerificationFailureFeedback(progress);
                    } else if (progress.hasCurrentVerificationFailure()) {
                        String failure = postEditVerificationFailureFeedback(progress);
                        return failedCompletion(
                                runId, task, steps, content, "POST_EDIT_VERIFICATION_FAILURE", failure,
                                runStartedAt, runStartedNanos, progress.plan(), planExecute, planningFallback
                        );
                    } else if (!progress.currentUnavailableVerifications().isEmpty()
                            && unavailableVerificationWarnings < 1) {
                        unavailableVerificationWarnings++;
                        feedback = "POST_EDIT_VERIFICATION_UNAVAILABLE_GUARD: A code verifier was not "
                                + "available for one or more changed files. You may finish, but must not "
                                + "claim syntax, compilation, or verification passed; explicitly state the "
                                + "unavailable verification in your final response.";
                    } else if (progress.postMutationTestSeen()
                            && Boolean.FALSE.equals(progress.postMutationTestPassed())
                            && isTestInfrastructureFailure(progress.postMutationTestErrorCode())
                            && infrastructureTestWarnings < 1) {
                        infrastructureTestWarnings++;
                        feedback = "TEST_INFRASTRUCTURE_WARNING: The changed file was reread, but Maven "
                                + "verification could not complete because of "
                                + progress.postMutationTestErrorCode()
                                + ". Do not claim tests passed. Report that the change was written and "
                                + "reread, and state the test infrastructure failure explicitly.";
                    } else if (progress.postMutationTestSeen()
                            && Boolean.FALSE.equals(progress.postMutationTestPassed())
                            && !isTestInfrastructureFailure(progress.postMutationTestErrorCode())
                            && failedTestGuards < 1) {
                        failedTestGuards++;
                        feedback = "TEST_FAILED_GUARD: The latest post-mutation test failed. Key diagnostic: "
                                + diagnosticOrFallback(progress)
                                + ". Do not claim completion; inspect the diagnostic, reread the current "
                                + "source, repair it, and retest.";
                    } else if (progress.postMutationTestSeen()
                            && Boolean.FALSE.equals(progress.postMutationTestPassed())
                            && !isTestInfrastructureFailure(progress.postMutationTestErrorCode())) {
                        String failure = "Workspace modification was written and reread, but verification "
                                + "failed because the latest Maven test did not pass.";
                        return failedCompletion(
                                runId, task, steps, content, "TEST_VERIFICATION_FAILURE", failure,
                                runStartedAt, runStartedNanos, progress.plan(), planExecute, planningFallback
                        );
                    } else if (progress.latestJavaMutationRequiresMavenVerification()
                            && environment.projectTestVerificationAvailable()
                            && !progress.postMutationTestSeen()
                            && !Boolean.TRUE.equals(progress.postMutationTestPassed())
                            && validationGuards < 1) {
                        validationGuards++;
                        feedback = "JAVA_VERIFICATION_GUARD: A Java file was changed and reread, but no "
                                + "successful Maven test has verified the latest mutation. Run Maven tests "
                                + "before claiming that verification passed.";
                    } else if (progress.latestJavaMutationRequiresMavenVerification()
                            && environment.projectTestVerificationAvailable()
                            && !progress.postMutationTestSeen()
                            && !Boolean.TRUE.equals(progress.postMutationTestPassed())) {
                        String failure = "Java workspace modification was written and reread, but no "
                                + "successful Maven test verified the latest mutation.";
                        return failedCompletion(
                                runId, task, steps, content, "JAVA_VERIFICATION_FAILURE", failure,
                                runStartedAt, runStartedNanos, progress.plan(), planExecute, planningFallback
                        );
                    } else if (planningEnabled
                            && (progress.plan() == null
                            || progress.remainingRequirementCount() > 0)
                            && planCompletionWarnings < 1) {
                        planCompletionWarnings++;
                        feedback = planCompletionFeedback(progress);
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
                appendPlanOutcome(steps, planExecute, planningFallback, progress.plan());
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

            ToolCall replanTriggerCall = null;
            ToolResult replanTriggerResult = null;
            for (ToolCall toolCall : toolCalls) {
                long toolStartedAt = System.currentTimeMillis();
                long toolStartedNanos = System.nanoTime();
                Map<String, Object> parsedArguments = parseArguments(toolCall.arguments());
                eventListener.toolStarted(toolCall.name(), parsedArguments);
                String toolPath = Objects.toString(parsedArguments.get("path"), null);
                boolean repairReadWasRequired = "read_file".equals(toolCall.name())
                        && verificationRepairPolicy.guidedRepairEnabled()
                        && progress.requiresRepairRead(toolPath);
                ToolAvailabilityDecision availability = environment.toolAvailability(toolCall.name());
                ToolResult toolResult = !availability.available()
                        ? availability.rejectionResult()
                        : isWorkspaceMutationTool(toolCall.name())
                        && verificationRepairPolicy.guidedRepairEnabled()
                        && progress.requiresRepairRead(toolPath)
                        ? ToolResult.failure(
                                ToolErrorCode.REPAIR_REQUIRES_FRESH_READ,
                                "Verification failed for " + toolPath
                                        + "; read_file must successfully reread this file after the failure before repairing it"
                        )
                        : isWorkspaceMutationTool(toolCall.name())
                        && progress.requiresFreshReadBeforeMutation(toolPath)
                        ? ToolResult.failure(
                                ToolErrorCode.STALE_EDIT_CONTEXT,
                                "A successful mutation changed " + toolPath
                                        + "; read_file must refresh the current content before another mutation"
                        )
                        : isEditTool(toolCall.name())
                        && progress.requiresRereadBeforeEdit(toolPath)
                        ? ToolResult.failure(
                                ToolErrorCode.STALE_EDIT_CONTEXT,
                                "Two consecutive edits failed for " + toolPath
                                        + "; read_file must refresh the current content before another edit"
                        )
                        : environment.execute(toolCall);
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
                if (repairReadWasRequired && toolResult.success()) {
                    steps.add(new AgentStep(steps.size() + 1, AgentActionType.VERIFICATION_REPAIR,
                            null, null, null, Map.of("event", "REPAIR_READ_SATISFIED", "file", toolPath),
                            null, null, null, System.currentTimeMillis(), 0));
                }
                if (isSuccessfulWorkspaceMutation(toolCall.name(), toolResult)
                        && verificationRepairPolicy.guidedRepairEnabled()
                        && progress.verificationFailure(toolPath) != null) {
                    steps.add(new AgentStep(steps.size() + 1, AgentActionType.VERIFICATION_REPAIR,
                            null, null, null, Map.of("event", "REPAIR_ATTEMPT", "file", toolPath,
                                    "mutationTool", toolCall.name(),
                                    "repairMutationCount", progress.repairMutationCount(toolPath)),
                            null, null, null, System.currentTimeMillis(), 0));
                }
                history.add(Message.tool(toolCall.id(), serializeObservation(toolResult)));

                if (planExecute) {
                    if (toolResult.success()) {
                        TaskRequirement completed = progress.completeCurrentPlanStep(toolCall.name(),
                                "Action evidence only: " + toolCall.name() + " returned success; "
                                        + "semantic correctness still requires task-appropriate verification.");
                        if (completed != null) {
                            steps.add(planStatusStep(steps.size() + 1, completed,
                                    "ACTION_SUCCEEDED", toolCall.name()));
                            TaskRequirement next = progress.startNextPlanStep();
                            if (next != null) {
                                steps.add(planStatusStep(steps.size() + 1, next, "STEP_STARTED", null));
                            }
                        }
                    } else if (isReplanTrigger(toolResult.errorCode())) {
                        TaskRequirement blocked = progress.blockCurrentPlanStep(
                                "Observed tool failure: " + toolResult.errorCode());
                        if (blocked != null) {
                            steps.add(planStatusStep(steps.size() + 1, blocked,
                                    "STEP_BLOCKED", toolResult.errorCode().name()));
                        }
                        if (replanTriggerCall == null) {
                            replanTriggerCall = toolCall;
                            replanTriggerResult = toolResult;
                        }
                    }
                }

                if (isSuccessfulAutoRereadMutation(toolCall.name(), toolResult)) {
                    Map<String, Object> rereadArguments = Map.of("path", toolPath);
                    String rereadJson = serializeArguments(rereadArguments);
                    long rereadStartedAt = System.currentTimeMillis();
                    long rereadStartedNanos = System.nanoTime();
                    ToolResult rereadResult = environment.execute(new ToolCall(
                            UUID.randomUUID().toString(), "read_file", rereadJson));
                    steps.add(new AgentStep(
                            steps.size() + 1,
                            AgentActionType.AUTO_REREAD,
                            "read_file",
                            null,
                            rereadJson,
                            rereadArguments,
                            rereadResult,
                            null,
                            null,
                            rereadStartedAt,
                            elapsedMs(rereadStartedNanos)
                    ));
                    progress.observe("read_file", rereadArguments, rereadResult, steps.size());
                    history.add(Message.system(
                            "RUNTIME_AUTO_REREAD: Latest contents for " + toolPath + "\n"
                                    + serializeObservation(rereadResult)
                    ));
                    if (!rereadResult.success()) {
                        String feedback = "AUTO_REREAD_FAILURE: The workspace write succeeded, but the "
                                + "runtime could not reread " + toolPath + ". Do not claim completion; "
                                + "use read_file to obtain current contents before continuing.";
                        steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                        history.add(Message.system(feedback));
                    } else if (planExecute) {
                        TaskRequirement completed = progress.completeCurrentPlanStep("read_file",
                                "Action evidence only: runtime reread succeeded for " + toolPath + ".");
                        if (completed != null) {
                            steps.add(planStatusStep(steps.size() + 1, completed,
                                    "REREAD_SUCCEEDED", "AUTO_REREAD"));
                            TaskRequirement next = progress.startNextPlanStep();
                            if (next != null) {
                                steps.add(planStatusStep(steps.size() + 1, next, "STEP_STARTED", null));
                            }
                        }
                    }
                    if (rereadResult.success()) {
                        com.agent.environment.verification.VerificationCapability capability =
                                environment.verificationCapability(toolPath);
                        boolean repairingFailedFile = progress.verificationFailure(toolPath) != null
                                && progress.repairMutationCount(toolPath) > 0
                                && verificationRepairPolicy.guidedRepairEnabled();
                        VerificationResult verification = environment.verifyPostEdit(
                                toolPath, progress.currentMutationSequence(toolPath));
                        VerificationFailureContext failureContext = null;
                        if (verificationRepairPolicy.guidedRepairEnabled()) {
                            failureContext = progress.observeVerification(
                                    verification,
                                    toolCall.name(),
                                    "step:" + (steps.size() - 1),
                                    capability,
                                    com.agent.environment.verification.VerificationDiagnosticParser.parse(
                                            verification.verifierId(), verification.diagnosticSummary()),
                                    steps.size() + 1);
                        } else {
                            progress.observeVerification(verification);
                        }
                        Map<String, Object> verificationMetadata = Map.of(
                                "file", verification.file().toString(),
                                "verifier", verification.verifierId(),
                                "status", verification.status().name(),
                                "mutationSequence", verification.mutationSequence()
                        );
                        String diagnostic = verification.status() == VerificationStatus.FAIL
                                ? verification.diagnosticSummary()
                                : verification.status() == VerificationStatus.UNAVAILABLE
                                ? verification.unavailableReason() : "";
                        steps.add(new AgentStep(
                                steps.size() + 1,
                                AgentActionType.POST_EDIT_VERIFICATION,
                                "post_edit_verification",
                                null,
                                null,
                                verificationMetadata,
                                null,
                                null,
                                diagnostic,
                                System.currentTimeMillis(),
                                0
                        ));
                        String observation = verificationObservation(verification,
                                verificationRepairPolicy.guidedRepairEnabled() ? capability : null);
                        history.add(Message.system(observation));
                        if (failureContext != null) {
                            String directive = RepairDirective.from(failureContext).text();
                            history.add(Message.system(directive));
                            Map<String, Object> repairEvent = new java.util.LinkedHashMap<>();
                            repairEvent.put("event", "REPAIR_REQUIRED");
                            repairEvent.put("file", failureContext.file());
                            repairEvent.put("verifier", failureContext.verifier());
                            repairEvent.put("mutationSequence", failureContext.mutationSequence());
                            repairEvent.put("failureCount", failureContext.failureCount());
                            repairEvent.put("lastMutationTool", failureContext.lastMutationTool());
                            repairEvent.put("rereadIdentity", failureContext.lastSuccessfulRereadIdentity());
                            repairEvent.put("workspaceKind", failureContext.capability().workspaceKind().name());
                            repairEvent.put("recommendedVerifier", failureContext.capability().recommendedVerifier());
                            if (failureContext.location().known()) {
                                repairEvent.put("line", failureContext.location().line());
                                repairEvent.put("column", failureContext.location().column());
                            }
                            steps.add(new AgentStep(steps.size() + 1, AgentActionType.VERIFICATION_REPAIR,
                                    null, null, null, repairEvent, null, null, null,
                                    System.currentTimeMillis(), 0));
                        } else if (verification.status() == VerificationStatus.PASS
                                && repairingFailedFile
                                && progress.verificationFailure(toolPath) == null) {
                            steps.add(new AgentStep(steps.size() + 1, AgentActionType.VERIFICATION_REPAIR,
                                    null, null, null, Map.of("event", "REPAIR_RECOVERED", "file", toolPath,
                                            "successfulRecoveryCount", progress.successfulRecoveryCount(toolPath)),
                                    null, null, null, System.currentTimeMillis(), 0));
                        }
                    }
                }

                if (diagnosticRecovery
                        && taskMode == TaskMode.CODE_MODIFICATION
                        && progress.consumeConvergenceGuidanceAfterReread()) {
                    String feedback = "POST_EDIT_CONVERGENCE: The latest successful mutation has been reread. "
                            + "If the requested change is present, finish the task now instead of making "
                            + "unrequested cleanup or cosmetic edits."
                            + (progress.latestJavaMutationRequiresMavenVerification()
                            ? " This Java mutation is in a Maven workspace; run the required Maven verification."
                            : " Use the applicable post-edit verifier result; do not run Maven when it is not applicable.")
                            + " Continue editing only when the reread or verification shows that the requested "
                            + "change is incorrect or incomplete.";
                    steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                    history.add(Message.system(feedback));
                }

                String recoveryFeedback = editRecoveryFeedback(
                        toolCall.name(),
                        toolPath,
                        toolResult,
                        progress
                );
                if (recoveryFeedback != null) {
                    steps.add(runtimeFeedbackStep(steps.size() + 1, null, recoveryFeedback));
                    history.add(Message.system(recoveryFeedback));
                }

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

                if (isSuccessfulWorkspaceMutation(toolCall.name(), toolResult)) {
                    if (!isAutoRereadMutationTool(toolCall.name())) {
                        String feedback = "POST_MUTATION_READ_REQUIRED: A workspace write succeeded. "
                                + "Before another write or final answer, use read_file on the changed path "
                                + "and confirm the requested change and existing content are both intact.";
                        steps.add(runtimeFeedbackStep(steps.size() + 1, null, feedback));
                        history.add(Message.system(feedback));
                    }
                    break;
                }
            }
            if (planExecute && progress.plan() != null
                    && replanTriggerCall != null && replanCount < 1) {
                replanCount++;
                try {
                    AgentPlan replanned = planner.replan(llmClient, task, progress.plan(),
                            replanTriggerResult, replanTriggerCall.name());
                    progress.updatePlan(replanned);
                    steps.add(planStep(steps.size() + 1, AgentActionType.REPLAN, replanned));
                    TaskRequirement next = progress.startNextPlanStep();
                    if (next != null) {
                        steps.add(planStatusStep(steps.size() + 1, next, "STEP_STARTED", "REPLAN"));
                    }
                    history.add(Message.system("REPLAN: The plan was updated once using the observed "
                            + "typed tool failure. Continue through the existing tools and guards."));
                } catch (IOException | IllegalArgumentException exception) {
                    planningFallback = true;
                    progress.resetBlockedPlanStep();
                    TaskRequirement resumed = progress.startNextPlanStep();
                    if (resumed != null) {
                        steps.add(planStatusStep(steps.size() + 1, resumed, "STEP_RESUMED", "REPLAN_FAILED"));
                    }
                    steps.add(planningFallbackStep(steps.size() + 1, "REPLAN_FAILED",
                            exception.getClass().getSimpleName()));
                    history.add(Message.system("PLAN_FALLBACK: replan unavailable; continue with the "
                            + "current plan and existing reactive recovery behavior."));
                }
            }
        }

        String error = "Agent exceeded maximum iterations: " + maxIterations;
        appendPlanOutcome(steps, planExecute, planningFallback, progress.plan());
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

    private LLMResponse completeDecision(List<Message> messages) throws IOException {
        if (!streamingEnabled) {
            return llmClient.chat(messages, environment.toolDefinitions());
        }
        boolean[] started = {false};
        try {
            if (llmClient instanceof StreamingLlmClient streamingClient) {
                return streamingClient.stream(messages, environment.toolDefinitions(), delta -> {
                    if (!started[0]) {
                        started[0] = true;
                        eventListener.assistantMessageStarted();
                    }
                    eventListener.assistantTextDelta(delta);
                });
            }
            LLMResponse response = llmClient.chat(messages, environment.toolDefinitions());
            String content = response.content();
            if (content != null && !content.isEmpty()) {
                started[0] = true;
                eventListener.assistantMessageStarted();
                eventListener.assistantTextDelta(content);
            }
            return response;
        } finally {
            if (started[0]) {
                eventListener.assistantMessageFinished();
            }
        }
    }

    public List<Message> history() {
        return List.copyOf(history);
    }

    public PlanningMode planningMode() {
        return planningMode;
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

    private List<Message> decisionMessages(AgentProgress progress, boolean planExecute) {
        if (planExecute && progress.plan() != null) {
            List<Message> messages = new ArrayList<>(history);
            messages.add(Message.system(progress.compactExecutionPlanContext()));
            return List.copyOf(messages);
        }
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

    private AgentStep adaptivePlanningStep(int stepIndex, AdaptivePlanningDecision decision) {
        return new AgentStep(stepIndex, AgentActionType.PLANNING_ROUTED, null, null, null,
                Map.of("configuredMode", planningMode.name(),
                        "effectiveMode", decision.selectedMode().name(),
                        "confidence", decision.confidence().name(),
                        "reasons", decision.reasons()),
                null, null, null, System.currentTimeMillis(), 0);
    }

    private static AgentStep planStatusStep(
            int stepIndex, TaskRequirement requirement, String event, String detail
    ) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("stepId", requirement.id());
        arguments.put("description", requirement.description());
        arguments.put("status", requirement.status().name());
        arguments.put("event", event);
        if (detail != null) {
            arguments.put("detail", detail);
        }
        if (!requirement.evidence().isBlank()) {
            arguments.put("evidence", requirement.evidence());
        }
        return new AgentStep(stepIndex, AgentActionType.PLAN_STEP_UPDATE, null, null, null,
                arguments, null, null, null, System.currentTimeMillis(), 0);
    }

    private static AgentStep planningFallbackStep(int stepIndex, String reason, String failureType) {
        return new AgentStep(stepIndex, AgentActionType.PLAN_FALLBACK, null, null, null,
                Map.of("reason", reason, "failureType", failureType,
                        "outcome", "FALLBACK_REACTIVE"), null, null, null,
                System.currentTimeMillis(), 0);
    }

    private static void appendPlanOutcome(
            List<AgentStep> steps, boolean planExecute, boolean fallback, AgentPlan plan
    ) {
        if (!planExecute) {
            return;
        }
        String outcome = fallback ? "FALLBACK_REACTIVE"
                : plan != null && plan.remainingRequirementCount() == 0
                ? "COMPLETED" : "PARTIALLY_COMPLETED";
        steps.add(new AgentStep(steps.size() + 1, AgentActionType.PLAN_STEP_UPDATE,
                null, null, null, Map.of("event", "PLAN_OUTCOME", "outcome", outcome),
                null, null, null, System.currentTimeMillis(), 0));
    }

    private static boolean isReplanTrigger(ToolErrorCode errorCode) {
        return errorCode == ToolErrorCode.FILE_NOT_FOUND
                || errorCode == ToolErrorCode.TEXT_NOT_FOUND
                || errorCode == ToolErrorCode.TOOL_NOT_FOUND
                || errorCode == ToolErrorCode.TEST_FAILED
                || errorCode == ToolErrorCode.STALE_EDIT_CONTEXT
                || errorCode == ToolErrorCode.MULTIPLE_MATCHES
                || errorCode == ToolErrorCode.AMBIGUOUS_MATCH
                || errorCode == ToolErrorCode.INVALID_LINE_RANGE;
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

    private static AgentRunResult failedCompletion(
            String runId,
            String task,
            List<AgentStep> steps,
            String attemptedFinalAnswer,
            String feedbackCode,
            String failure,
            long runStartedAt,
            long runStartedNanos,
            AgentPlan plan,
            boolean planExecute,
            boolean planningFallback
    ) {
        steps.add(runtimeFeedbackStep(
                steps.size() + 1,
                attemptedFinalAnswer,
                feedbackCode + ": " + failure
        ));
        appendPlanOutcome(steps, planExecute, planningFallback, plan);
        steps.add(new AgentStep(
                steps.size() + 1,
                AgentActionType.FINAL_ANSWER,
                null,
                null,
                null,
                Map.of(),
                null,
                failure,
                null,
                System.currentTimeMillis(),
                0
        ));
        return result(
                runId,
                task,
                steps,
                failure,
                TerminationReason.FINAL_ANSWER,
                false,
                runStartedAt,
                runStartedNanos,
                plan
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

    private static String verificationObservation(
            VerificationResult result,
            com.agent.environment.verification.VerificationCapability capability
    ) {
        StringBuilder observation = new StringBuilder("POST_EDIT_VERIFICATION: status=")
                .append(result.status()).append(" file=").append(result.file())
                .append(" verifier=").append(result.verifierId())
                .append(" mutationSequence=").append(result.mutationSequence());
        if (!result.diagnosticSummary().isBlank()) {
            observation.append("\ndiagnostic:\n").append(result.diagnosticSummary());
        }
        if (!result.unavailableReason().isBlank()) {
            observation.append("\nunavailableReason: ").append(result.unavailableReason());
        }
        if (capability == null) {
            if (result.status() == VerificationStatus.FAIL) {
                observation.append("\nDo not claim completion; the current verification remains failed.");
            } else if (result.status() == VerificationStatus.UNAVAILABLE) {
                observation.append("\nThis is not a PASS. Do not claim that verification passed.");
            } else if (result.status() == VerificationStatus.PASS) {
                observation.append("\nThis verifies syntax/compilation only, not semantic correctness.");
            }
            return observation.toString();
        }
        if (capability.workspaceKind()
                == com.agent.environment.verification.VerificationCapability.WorkspaceKind.STANDALONE
                && "javac".equals(capability.recommendedVerifier())) {
            observation.append("\nverification capability: workspace_kind=STANDALONE; project verifier=javac; "
                    + "Maven project not detected.");
        } else if (capability.workspaceKind()
                == com.agent.environment.verification.VerificationCapability.WorkspaceKind.MAVEN) {
            observation.append("\nverification capability: workspace_kind=MAVEN; project verifier=Maven.");
        }
        if (result.status() == VerificationStatus.FAIL) {
            observation.append("\nDo not claim successful completion. Inspect this diagnostic and make a bounded repair.");
        } else if (result.status() == VerificationStatus.UNAVAILABLE) {
            observation.append("\nThis is not a PASS. Do not claim that verification passed.");
        } else if (result.status() == VerificationStatus.PASS) {
            observation.append("\nThis verifies syntax/compilation only, not semantic correctness.");
        }
        return observation.toString();
    }

    private static String postEditVerificationFailureFeedback(AgentProgress progress) {
        String failures = progress.currentVerificationResults().stream()
                .filter(result -> result.status() == VerificationStatus.FAIL)
                .map(result -> result.file() + " (" + result.verifierId() + "): "
                        + result.diagnosticSummary())
                .collect(java.util.stream.Collectors.joining("\n"));
        return "POST_EDIT_VERIFICATION_FAILED: Latest verification failed for one or more mutated files. "
                + "Do not claim success. Use the diagnostics to reread and repair the affected file(s), "
                + "then allow verification to run again.\n" + failures;
    }

    private static boolean claimsVerificationPassed(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("(?s).*(tests? passed|all tests pass|build succeeded|compiled successfully|"
                + "compilation passed|syntax check passed|syntax is valid|verification passed|verified successfully|"
                + "测试通过|编译通过|验证通过).*");
    }

    private static String unavailableVerificationFinal(AgentProgress progress) {
        String files = progress.currentUnavailableVerifications().stream()
                .map(result -> result.file() + " (" + result.unavailableReason() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return "The workspace change was made, but post-edit verification was UNAVAILABLE for " + files
                + ". I cannot claim that syntax, compilation, or tests passed.";
    }

    private static String editRecoveryFeedback(
            String toolName,
            String path,
            ToolResult result,
            AgentProgress progress
    ) {
        if (result.success() || result.errorCode() == null) {
            return null;
        }
        String target = path == null || path.isBlank() ? "the target file" : path;
        String rereadRequirement = progress.requiresRereadBeforeEdit(path)
                ? " Two consecutive edits have failed for this file; read_file is required before another edit."
                : "";
        if ("apply_patch".equals(toolName)
                || "replace_lines".equals(toolName)
                || "insert_before".equals(toolName)
                || "insert_after".equals(toolName)) {
            return switch (result.errorCode()) {
                case INVALID_ARGUMENTS -> "EDIT_RECOVERY_INVALID_ARGUMENTS: The edit arguments are invalid. "
                        + "Do not retry them unchanged. Read " + target + " again, then use a real, non-empty, "
                        + "unique oldText anchor. For insertion, replace the anchor with the anchor plus the "
                        + "new content." + rereadRequirement;
                case TEXT_NOT_FOUND -> "EDIT_RECOVERY_TEXT_NOT_FOUND: The requested oldText is not present in "
                        + target + ". Use read_file to obtain the latest content before constructing a new patch; "
                        + "do not repeat the same oldText." + rereadRequirement;
                case AMBIGUOUS_MATCH, MULTIPLE_MATCHES -> "EDIT_RECOVERY_AMBIGUOUS_MATCH: The requested anchor "
                        + "occurs more than once in " + target + ". Do not choose one arbitrarily; read the "
                        + "file and use a more specific unique anchor." + rereadRequirement;
                case NO_EFFECT_CHANGE -> "EDIT_RECOVERY_NO_EFFECT_CHANGE: oldText and newText produced no content "
                        + "change in " + target + ". Do not submit the same patch again; re-check the current file "
                        + "and the requested outcome." + rereadRequirement;
                case STALE_EDIT_CONTEXT, INVALID_LINE_RANGE -> "EDIT_RECOVERY_STALE_CONTEXT: The edit context for "
                        + target + " is stale or invalid. Use read_file before another edit and rebuild the edit "
                        + "from the current content." + rereadRequirement;
                default -> null;
            };
        }
        if ("create_file".equals(toolName)
                && result.errorCode() == ToolErrorCode.FILE_ALREADY_EXISTS) {
            return "CREATE_RECOVERY_FILE_EXISTS: " + target + " already exists. Do not retry create_file. "
                    + "Read the existing file, then use apply_patch only if a change is still required.";
        }
        return null;
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

    private static boolean isSuccessfulWorkspaceMutation(String toolName, ToolResult result) {
        return isWorkspaceMutationTool(toolName)
                && result.success()
                && Boolean.TRUE.equals(result.metadata().get("changed"));
    }

    private static boolean isAutoRereadMutationTool(String toolName) {
        return "apply_patch".equals(toolName)
                || "replace_lines".equals(toolName)
                || "insert_before".equals(toolName)
                || "insert_after".equals(toolName)
                || "create_file".equals(toolName);
    }

    private static boolean isSuccessfulAutoRereadMutation(String toolName, ToolResult result) {
        return isAutoRereadMutationTool(toolName)
                && result.success()
                && Boolean.TRUE.equals(result.metadata().get("changed"));
    }

    private static String serializeArguments(Map<String, Object> arguments) {
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize runtime tool arguments", exception);
        }
    }

    private static boolean isTestInfrastructureFailure(ToolErrorCode errorCode) {
        return errorCode == ToolErrorCode.PROCESS_START_FAILED
                || errorCode == ToolErrorCode.PROCESS_TIMEOUT
                || errorCode == ToolErrorCode.TOOL_EXECUTION_ERROR;
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
