package com.agent.benchmark.v12;

import com.agent.CliAgentFactory;
import com.agent.CliIntentRouter;
import com.agent.CliMode;
import com.agent.CliSessions;
import com.agent.CliWorkingContext;
import com.agent.RoutingDecision;
import com.agent.RoutingConfidence;
import com.agent.RoutingReason;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-interactive bridge for a later V1.2 benchmark executor. It deliberately
 * reuses the CLI router and the three existing CLI profiles; it does not mimic stdin.
 */
public final class V12RuntimeHarness {
    private final CliIntentRouter router = new CliIntentRouter();
    private final CliSessions sessions;
    private final CliWorkingContext workingContext = new CliWorkingContext();
    private CliMode lastAutoRoute;

    public V12RuntimeHarness(LLMClient client, Path workspace) {
        Objects.requireNonNull(client, "client must not be null");
        this.sessions = new CliSessions(
                CliAgentFactory.createChat(client, workspace, AgentEventListener.NO_OP),
                CliAgentFactory.createReadOnly(client, workspace, AgentEventListener.NO_OP),
                CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP,
                        Path.of(".m2/repository"))
        );
    }

    public RoutingDecision route(String instruction) {
        return router.route(instruction);
    }

    public CliSessions sessions() {
        return sessions;
    }

    public com.agent.agent.Agent sessionFor(RoutingDecision decision) {
        return sessions.session(Objects.requireNonNull(decision, "decision must not be null").mode());
    }

    public com.agent.agent.Agent explicitSession(CliMode mode) {
        return sessions.session(mode);
    }

    /** Executes real profile sessions turn-by-turn without InteractiveCli/stdin. */
    public List<V12TurnResult> run(V12Task task) {
        List<V12TurnResult> results = new ArrayList<>();
        for (int index = 0; index < task.userInstructions().size(); index++) {
            String input = task.userInstructions().get(index);
            CliMode requested = task.requestedModes().get(index);
            RoutingDecision decision;
            if (requested == CliMode.AUTO) {
                decision = router.route(input);
                if (decision.confidence() == RoutingConfidence.LOW && lastAutoRoute != null) {
                    decision = new RoutingDecision(lastAutoRoute, RoutingConfidence.LOW,
                            RoutingReason.INCOMPLETE_CONTEXTUAL_REQUEST);
                }
            } else {
                decision = new RoutingDecision(requested, RoutingConfidence.HIGH,
                        requested == CliMode.CODE ? RoutingReason.EXPLICIT_MUTATION_TARGET
                                : RoutingReason.WORKSPACE_READ_REQUEST);
            }
            String prompt = contextualPrompt(input, requested);
            AgentRunResult run = sessions.session(decision.mode()).runWithTrajectory(prompt);
            workingContext.observe(run.trajectory());
            if (requested == CliMode.AUTO) lastAutoRoute = decision.mode();
            results.add(new V12TurnResult(index + 1, input, requested, decision.mode(),
                    decision.confidence(), decision.reason(), run.trajectory()));
        }
        return List.copyOf(results);
    }

    private String contextualPrompt(String input, CliMode requested) {
        if (requested != CliMode.AUTO || !workingContext.hasContextualFileReference(input)
                || workingContext.lastResolvedFiles().isEmpty()) return input;
        String context = workingContext.contextualPrompt();
        return context.isBlank() ? input : context + "\n\nUser request:\n" + input;
    }
}
