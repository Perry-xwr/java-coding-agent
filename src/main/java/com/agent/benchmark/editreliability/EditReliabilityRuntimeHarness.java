package com.agent.benchmark.editreliability;

import com.agent.CliAgentFactory;
import com.agent.agent.Agent;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.PlanningMode;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.CodeVerifier;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.llm.LLMClient;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Runs exactly one isolated task/condition using the production CODE Agent and injected provider. */
public final class EditReliabilityRuntimeHarness {
    private final EditReliabilityWorkspace workspaces = new EditReliabilityWorkspace();
    private final EditReliabilityEvaluator evaluator = new EditReliabilityEvaluator();

    public RunResult run(EditReliabilityTask task, EditReliabilityMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository) throws Exception {
        VerifierRegistry verifiers = mode == EditReliabilityMode.REREAD_ONLY
                ? new VerifierRegistry(List.of(new RereadOnlyVerifier()))
                : BuiltInCodeVerifiers.registry(new DefaultProcessRunner());
        return run(task, mode, provider, fixturesRoot, runsRoot, runId, localMavenRepository,
                verifiers);
    }

    public RunResult run(EditReliabilityTask task, EditReliabilityMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository,
                         VerifierRegistry verifierRegistry) throws Exception {
        Objects.requireNonNull(task);
        Objects.requireNonNull(mode);
        ProviderBudget budget = new ProviderBudget(task.maxProviderRequests());
        LLMClient conditionedProvider = mode == EditReliabilityMode.REREAD_ONLY
                ? new VerificationObservationFilterClient(provider) : Objects.requireNonNull(provider);
        BudgetedLlmClient budgeted = new BudgetedLlmClient(conditionedProvider, budget,
                task.maxProviderRequests());
        Path workspace = workspaces.reset(task, fixturesRoot, runsRoot, runId, mode);
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                localMavenRepository.toAbsolutePath().normalize());
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools, verifierRegistry);
        Agent agent = CliAgentFactory.createCoding(budgeted, environment, AgentEventListener.NO_OP,
                PlanningMode.REACTIVE);
        AgentRunResult run = agent.runWithTrajectory(task.instruction());
        EditReliabilityEvaluator.Evaluation evaluation = evaluator.evaluate(task, workspace, run.trajectory());
        EditReliabilityMetrics metrics = EditReliabilityMetrics.from(task, mode, run.trajectory(),
                budget.used(), evaluation);
        String providerFailure = run.trajectory().terminationReason().name().equals("LLM_ERROR")
                ? run.trajectory().steps().stream().filter(step -> step.actionType() == AgentActionType.ERROR)
                .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                .filter(message -> !message.contains("BUDGET_CAP_REACHED")).findFirst().orElse(null) : null;
        return new RunResult(task, mode, workspace, run, evaluation, metrics, budget.used(), providerFailure);
    }

    public record RunResult(EditReliabilityTask task, EditReliabilityMode mode, Path workspace,
                            AgentRunResult agentResult, EditReliabilityEvaluator.Evaluation evaluation,
                            EditReliabilityMetrics metrics, int providerRequests, String providerFailure) { }
}
