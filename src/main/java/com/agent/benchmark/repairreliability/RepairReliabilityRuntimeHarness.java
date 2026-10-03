package com.agent.benchmark.repairreliability;

import com.agent.CliAgentFactory;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.PlanningMode;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.llm.LLMClient;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Executes one task/mode with fresh workspace, Agent, history and provider budget. */
public final class RepairReliabilityRuntimeHarness {
    private final RepairReliabilityWorkspace workspaces = new RepairReliabilityWorkspace();
    private final ProcessRunner processRunner;
    private final VerifierRegistry verifiers;

    public RepairReliabilityRuntimeHarness() {
        this(new DefaultProcessRunner(), null);
    }

    /** Injection point for deterministic offline tests; live runs use the default verifier registry. */
    public RepairReliabilityRuntimeHarness(ProcessRunner processRunner, VerifierRegistry verifiers) {
        this.processRunner = Objects.requireNonNull(processRunner);
        this.verifiers = verifiers == null ? BuiltInCodeVerifiers.registry(processRunner) : verifiers;
    }

    public RunResult run(RepairReliabilityTask task, RepairReliabilityMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
            throws Exception {
        Objects.requireNonNull(task); Objects.requireNonNull(mode); Objects.requireNonNull(provider);
        if (task.maxSteps() != com.agent.agent.Agent.MAX_ITERATIONS) {
            throw new IllegalArgumentException("V1.9 repair tasks must use the shared Agent iteration limit");
        }
        ProviderBudget budget = new ProviderBudget(task.maxProviderRequests());
        BudgetedLlmClient budgeted = new BudgetedLlmClient(provider, budget, task.maxProviderRequests());
        Path workspace = workspaces.create(task, mode, fixturesRoot, runsRoot, runId);
        Path repository = localMavenRepository.toAbsolutePath().normalize();
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, processRunner, repository);
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, registry,
                verifiers);
        var agent = CliAgentFactory.createCoding(budgeted, environment, AgentEventListener.NO_OP,
                PlanningMode.REACTIVE, mode.runtimePolicy());
        AgentRunResult agentResult = agent.runWithTrajectory(task.instruction());
        RepairReliabilityEvaluator.Evaluation evaluation = new RepairReliabilityEvaluator(verifiers)
                .evaluate(task, workspace, agentResult.trajectory());
        boolean mavenWorkspace = Files.isRegularFile(workspace.resolve("pom.xml"));
        String providerFailure = agentResult.trajectory().terminationReason().name().equals("LLM_ERROR")
                ? agentResult.trajectory().steps().stream().filter(step -> step.actionType() == AgentActionType.ERROR)
                .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                .filter(message -> !message.contains("BUDGET_CAP_REACHED")).findFirst().orElse(null) : null;
        RepairReliabilityMetrics metrics = RepairReliabilityMetrics.from(task, mode,
                agentResult.trajectory(), budget.used(), evaluation, mavenWorkspace, providerFailure != null);
        return new RunResult(task, mode, workspace, agentResult, evaluation, metrics, budget.used(), providerFailure);
    }

    public record RunResult(RepairReliabilityTask task, RepairReliabilityMode mode, Path workspace,
                            AgentRunResult agentResult, RepairReliabilityEvaluator.Evaluation evaluation,
                            RepairReliabilityMetrics metrics, int providerRequests, String providerFailure) { }
}
