package com.agent.benchmark.adaptiveplanning;

import com.agent.CliAgentFactory;
import com.agent.agent.Agent;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.PlanningMode;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.Objects;

/** Provider-injected execution of one task/mode condition through the production CODE profile. */
public final class AdaptivePlanningRuntimeHarness {
    private final AdaptivePlanningWorkspace workspaces = new AdaptivePlanningWorkspace();
    private final AdaptivePlanningEvaluator evaluator = new AdaptivePlanningEvaluator();

    public RunResult run(AdaptivePlanningTask task, PlanningMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
            throws Exception {
        Objects.requireNonNull(task, "task must not be null");
        if (mode != PlanningMode.REACTIVE && mode != PlanningMode.PLAN_EXECUTE && mode != PlanningMode.ADAPTIVE) {
            throw new IllegalArgumentException("unsupported experimental mode: " + mode);
        }
        ProviderBudget budget = new ProviderBudget(task.maxProviderRequests());
        BudgetedLlmClient budgeted = new BudgetedLlmClient(
                Objects.requireNonNull(provider, "provider must not be null"), budget,
                task.maxProviderRequests());
        Path workspace = workspaces.reset(task, fixturesRoot, runsRoot, runId, mode);
        Path fixture = fixturesRoot.toAbsolutePath().normalize().resolve(task.fixture()).normalize();
        Agent agent = CliAgentFactory.createCoding(budgeted, workspace, AgentEventListener.NO_OP,
                localMavenRepository, mode);
        AgentRunResult run = agent.runWithTrajectory(task.instruction());
        AdaptivePlanningMetrics metrics = AdaptivePlanningMetrics.from(
                task, mode, run.trajectory(), budget.used());
        AdaptivePlanningEvaluator.Evaluation evaluation = evaluator.evaluate(
                task, workspace, fixture, run.trajectory(), metrics);
        String infrastructureError = infrastructureError(run);
        return new RunResult(task, mode, workspace, run.trajectory(), metrics, evaluation, infrastructureError);
    }

    private static String infrastructureError(AgentRunResult run) {
        if (run.trajectory().terminationReason().name().equals("LLM_ERROR")) {
            return run.trajectory().steps().stream()
                    .filter(step -> step.actionType() == AgentActionType.ERROR)
                    .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                    .filter(message -> !message.contains("BUDGET_CAP_REACHED"))
                    .findFirst().map(message -> "provider/runtime error: " + safe(message)).orElse(null);
        }
        return null;
    }

    private static String safe(String text) {
        return text.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    public record RunResult(AdaptivePlanningTask task, PlanningMode configuredMode, Path workspace,
                            com.agent.agent.AgentTrajectory trajectory,
                            AdaptivePlanningMetrics metrics,
                            AdaptivePlanningEvaluator.Evaluation evaluation,
                            String infrastructureError) { }
}
