package com.agent.benchmark.planning;

import com.agent.CliAgentFactory;
import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentRunResult;
import com.agent.agent.PlanningMode;
import com.agent.agent.TerminationReason;
import com.agent.benchmark.v12.BudgetedLlmClient;
import com.agent.benchmark.v12.ProviderBudget;
import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Runs the same task in isolated REACTIVE and PLAN_EXECUTE CODE profiles. */
public final class PlanningRuntimeHarness {
    private final PlanningFixtureWorkspace workspaces = new PlanningFixtureWorkspace();
    private final PlanningBenchmarkEvaluator evaluator = new PlanningBenchmarkEvaluator();

    public PairResult runPair(PlanningBenchmarkTask task, Supplier<LLMClient> providers,
                              Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
            throws Exception {
        Objects.requireNonNull(providers, "providers must not be null");
        RunResult reactive = run(task, PlanningMode.REACTIVE, providers.get(),
                fixturesRoot, runsRoot, runId, localMavenRepository);
        RunResult planned = run(task, PlanningMode.PLAN_EXECUTE, providers.get(),
                fixturesRoot, runsRoot, runId, localMavenRepository);
        return new PairResult(task.id(), task.maxProviderRequests(), reactive, planned);
    }

    public RunResult run(PlanningBenchmarkTask task, PlanningMode mode, LLMClient provider,
                         Path fixturesRoot, Path runsRoot, String runId, Path localMavenRepository)
            throws Exception {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        ProviderBudget budget = new ProviderBudget(task.maxProviderRequests());
        BudgetedLlmClient budgeted = new BudgetedLlmClient(
                Objects.requireNonNull(provider, "provider must not be null"), budget,
                task.maxProviderRequests());
        Path workspace = workspaces.reset(task, fixturesRoot, runsRoot, runId, mode);
        Path fixture = fixturesRoot.toAbsolutePath().normalize().resolve(task.fixture()).normalize();
        Agent agent = CliAgentFactory.createCoding(budgeted, workspace, AgentEventListener.NO_OP,
                localMavenRepository, mode);
        AgentRunResult run = agent.runWithTrajectory(task.instruction());
        PlanningBenchmarkMetrics metrics = PlanningBenchmarkMetrics.from(
                run.trajectory(), budgeted.used(), task.allowedMutationTargets());
        PlanningBenchmarkEvaluator.Evaluation evaluation = evaluator.evaluate(
                task, workspace, fixture, run.trajectory(), metrics);
        String infrastructureError = infrastructureError(run.trajectory());
        return new RunResult(task.id(), mode, workspace, run.trajectory(), metrics, evaluation,
                infrastructureError);
    }

    private static String infrastructureError(com.agent.agent.AgentTrajectory trajectory) {
        StringBuilder errors = new StringBuilder();
        if (trajectory.terminationReason() == TerminationReason.LLM_ERROR) {
            trajectory.steps().stream().filter(step -> step.actionType() == AgentActionType.ERROR)
                    .map(com.agent.agent.AgentStep::errorMessage).filter(Objects::nonNull)
                    .filter(message -> !message.contains("BUDGET_CAP_REACHED"))
                    .findFirst().ifPresent(message -> errors.append("execution provider error: ").append(message));
        }
        trajectory.steps().stream().filter(step -> step.actionType() == AgentActionType.PLAN_FALLBACK)
                .filter(step -> "IOException".equals(step.arguments().get("failureType")))
                .findFirst().ifPresent(step -> {
                    if (!errors.isEmpty()) errors.append("; ");
                    errors.append("planning provider I/O failure; reactive fallback used");
                });
        return errors.isEmpty() ? null : errors.toString();
    }

    public record RunResult(String taskId, PlanningMode mode, Path workspace,
                            com.agent.agent.AgentTrajectory trajectory,
                            PlanningBenchmarkMetrics metrics,
                            PlanningBenchmarkEvaluator.Evaluation evaluation,
                            String infrastructureError) { }

    public record PairResult(String taskId, int sharedRequestCap, RunResult reactive, RunResult planExecute) {
        public PairResult {
            if (reactive.metrics().providerRequests() > sharedRequestCap
                    || planExecute.metrics().providerRequests() > sharedRequestCap) {
                throw new IllegalArgumentException("a condition exceeded the shared per-task request cap");
            }
            if (reactive.mode() != PlanningMode.REACTIVE || planExecute.mode() != PlanningMode.PLAN_EXECUTE) {
                throw new IllegalArgumentException("pair must contain both planning modes");
            }
        }
    }
}
