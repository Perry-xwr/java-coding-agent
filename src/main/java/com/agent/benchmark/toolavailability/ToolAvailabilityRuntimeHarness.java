package com.agent.benchmark.toolavailability;

import com.agent.CliAgentFactory;
import com.agent.agent.Agent;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentStep;
import com.agent.agent.PlanningMode;
import com.agent.agent.VerificationRepairPolicy;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Executes one fresh task/mode condition through the production CODE profile. */
public final class ToolAvailabilityRuntimeHarness {
    public static final int PROVIDER_REQUEST_CAP = 12;
    public static final int MAX_AGENT_STEPS = 10;
    private final ToolAvailabilityWorkspace workspaces = new ToolAvailabilityWorkspace();
    private final ToolAvailabilityEvaluator evaluator;
    private final ProcessRunner processRunner;

    public ToolAvailabilityRuntimeHarness() {
        this(new DefaultProcessRunner());
    }

    public ToolAvailabilityRuntimeHarness(ProcessRunner processRunner) {
        this.processRunner = Objects.requireNonNull(processRunner);
        this.evaluator = new ToolAvailabilityEvaluator(processRunner);
    }

    public RunResult run(ToolAvailabilityTask task, ToolAvailabilityMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository,
                         PlanningMode planningMode, VerificationRepairPolicy repairPolicy) throws Exception {
        Objects.requireNonNull(task);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(provider);
        if (task.maxProviderRequests() != PROVIDER_REQUEST_CAP || task.maxSteps() != MAX_AGENT_STEPS
                || Agent.MAX_ITERATIONS != MAX_AGENT_STEPS) {
            throw new IllegalArgumentException("Task budgets must be 12 provider requests and 10 Agent steps");
        }
        Path workspace = workspaces.create(task, fixturesRoot, runsRoot, runId, mode);
        Path fixture = fixturesRoot.toAbsolutePath().normalize().resolve(task.fixture()).normalize();
        MavenCountingProcessRunner countedRunner = new MavenCountingProcessRunner(processRunner);
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, countedRunner,
                localMavenRepository.toAbsolutePath().normalize());
        LocalWorkspaceEnvironment local = new LocalWorkspaceEnvironment(workspace, registry);
        ToolAvailabilityObservations observations = new ToolAvailabilityObservations();
        ToolAvailabilityEnvironment environment = new ToolAvailabilityEnvironment(mode, local, registry,
                observations, countedRunner);
        ProviderBudget runBudget = new ProviderBudget(PROVIDER_REQUEST_CAP);
        RecordingProvider recording = new RecordingProvider(provider, environment, observations);
        BudgetedLlmClient budgeted = new BudgetedLlmClient(recording, runBudget, PROVIDER_REQUEST_CAP);
        Agent agent = CliAgentFactory.createCoding(budgeted, environment, AgentEventListener.NO_OP,
                Objects.requireNonNull(planningMode), Objects.requireNonNull(repairPolicy));

        long started = System.nanoTime();
        AgentRunResult agentResult = agent.runWithTrajectory(task.instruction());
        long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        observations.reconcileResults(agentResult.trajectory().steps());
        observations.observeFinal(environment.availabilityDecision("run_maven_test"));
        ToolAvailabilityEvaluation evaluation = evaluator.evaluate(task, workspace, fixture,
                agentResult.trajectory().completed(), localMavenRepository.toAbsolutePath().normalize());
        ToolAvailabilityObservations.Snapshot observation = observations.result();
        List<AgentStep> toolSteps = agentResult.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL).toList();
        int reads = (int) toolSteps.stream().filter(step -> "read_file".equals(step.toolName())).count();
        int mutations = (int) toolSteps.stream().filter(step -> isMutation(step.toolName()))
                .filter(step -> step.toolResult() != null && step.toolResult().success()).count();
        int typedFailures = (int) toolSteps.stream().filter(step -> step.toolResult() != null
                && !step.toolResult().success() && step.toolResult().errorCode() != null).count();
        int repeatedFailures = repeatedFailures(toolSteps);
        boolean capHit = agentResult.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.ERROR)
                .anyMatch(step -> step.errorMessage() != null && step.errorMessage().contains("BUDGET_CAP_REACHED"));
        String providerFailure = providerFailure(agentResult);
        boolean infrastructure = evaluation.infrastructureError() != null || providerFailure != null;
        String infrastructureCategory = evaluation.infrastructureErrorCategory();
        if (providerFailure != null) infrastructureCategory = "PROVIDER_OR_RUNTIME_FAILURE";
        ToolAvailabilityMetrics metrics = new ToolAvailabilityMetrics(task.id(), task.workspaceClass().name(),
                task.language(), task.taskCategory(), mode.name(), evaluation.success(), evaluation.workspaceOutcome(),
                evaluation.conversationalCompletion(), evaluation.finalValidationStatus(), recording.requests(),
                toolSteps.size(), reads, mutations, observation.initialPom(), observation.finalPom(),
                observation.pomCreatedDuringRun(), observation.availabilityTransitions(), observation.turns(),
                observation.advertisedTurns(), observation.mavenAttempts(), observation.mavenExecutions(),
                observation.mavenSuccesses(), observation.mavenFailures(), observation.mismatchAttempts(),
                observation.mismatchExecutions(), observation.mismatchRejections(), observation.appropriateAttempts(),
                observation.missingProjectFailures(), typedFailures, repeatedFailures, evaluation.targetDrift(), capHit,
                infrastructure, infrastructureCategory, observation.snapshots());
        return new RunResult(task, mode, workspace, agentResult, evaluation, metrics, durationMs, providerFailure);
    }

    private static boolean isMutation(String name) {
        return List.of("apply_patch", "replace_lines", "insert_before", "insert_after", "create_file")
                .contains(name);
    }

    private static int repeatedFailures(List<AgentStep> steps) {
        int repeats = 0;
        String previous = null;
        for (AgentStep step : steps) {
            String key = step.toolResult() == null || step.toolResult().success() ? null
                    : step.toolName() + "|" + step.rawArguments() + "|" + step.toolResult().errorCode();
            if (key != null && key.equals(previous)) repeats++;
            previous = key;
        }
        return repeats;
    }

    private static String providerFailure(AgentRunResult result) {
        if (!"LLM_ERROR".equals(result.trajectory().terminationReason().name())) return null;
        return result.trajectory().steps().stream().filter(step -> step.actionType() == AgentActionType.ERROR)
                .map(AgentStep::errorMessage).filter(Objects::nonNull)
                .filter(message -> !message.contains("BUDGET_CAP_REACHED"))
                .findFirst().map(ToolAvailabilityRuntimeHarness::redact).orElse(null);
    }

    private static String redact(String value) {
        return value.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    private static final class RecordingProvider implements LLMClient {
        private final LLMClient delegate;
        private final ToolAvailabilityEnvironment environment;
        private final ToolAvailabilityObservations observations;
        private int requests;

        private RecordingProvider(LLMClient delegate, ToolAvailabilityEnvironment environment,
                                  ToolAvailabilityObservations observations) {
            this.delegate = delegate;
            this.environment = environment;
            this.observations = observations;
        }

        @Override public LLMResponse chat(List<Message> messages) throws IOException {
            return chat(messages, List.of());
        }

        @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            requests++;
            environment.providerTurn(tools);
            return delegate.chat(messages, tools);
        }

        int requests() { return requests; }
    }

    private static final class MavenCountingProcessRunner implements ProcessRunner,
            ToolAvailabilityEnvironment.MavenProcessCounter {
        private final ProcessRunner delegate;
        private int mavenCalls;

        private MavenCountingProcessRunner(ProcessRunner delegate) { this.delegate = delegate; }

        @Override public synchronized ProcessExecutionResult run(List<String> command, Path cwd,
                                                                 java.time.Duration timeout, int limit)
                throws IOException, InterruptedException {
            if (!command.isEmpty() && isMaven(command.get(0))) mavenCalls++;
            return delegate.run(command, cwd, timeout, limit);
        }

        @Override public synchronized int count() { return mavenCalls; }

        private static boolean isMaven(String executable) {
            String name = Path.of(executable).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.equals("mvn") || name.equals("mvn.cmd") || name.equals("mvn.bat");
        }
    }

    public record RunResult(ToolAvailabilityTask task, ToolAvailabilityMode mode, Path workspace,
                            AgentRunResult agentResult, ToolAvailabilityEvaluation evaluation,
                            ToolAvailabilityMetrics metrics, long durationMs, String providerFailure) { }
}
