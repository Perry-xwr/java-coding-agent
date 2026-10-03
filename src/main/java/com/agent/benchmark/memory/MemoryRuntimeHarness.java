package com.agent.benchmark.memory;

import com.agent.CliAgentFactory;
import com.agent.CliIntentRouter;
import com.agent.CliMode;
import com.agent.CliSessions;
import com.agent.CliWorkingContext;
import com.agent.RoutingConfidence;
import com.agent.RoutingDecision;
import com.agent.RoutingReason;
import com.agent.WorkingMemoryMode;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentTrajectory;
import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Non-interactive multi-turn runtime used only by the memory-v1 protocol. */
public final class MemoryRuntimeHarness {
    private final CliIntentRouter router = new CliIntentRouter();
    private final CliSessions sessions;
    private final CliWorkingContext memory;
    private CliMode lastAutoRoute;

    public MemoryRuntimeHarness(LLMClient client, Path workspace, WorkingMemoryMode mode) {
        Objects.requireNonNull(client, "client must not be null");
        Path root = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath().normalize();
        this.sessions = new CliSessions(
                CliAgentFactory.createChat(client, root, AgentEventListener.NO_OP),
                CliAgentFactory.createReadOnly(client, root, AgentEventListener.NO_OP),
                CliAgentFactory.createCoding(client, root, AgentEventListener.NO_OP,
                        root.resolve(".m2/repository"))
        );
        this.memory = new CliWorkingContext(mode);
    }

    public List<TurnResult> run(MemoryBenchmarkTask task) {
        List<TurnResult> turns = new ArrayList<>();
        for (int index = 0; index < task.turns().size(); index++) {
            String input = task.turns().get(index);
            memory.observeUserTask(input);
            RoutingDecision decision = resolve(input);
            String prompt = prompt(input, decision.mode());
            AgentRunResult run = sessions.session(decision.mode()).runWithTrajectory(prompt);
            memory.observe(run.trajectory());
            lastAutoRoute = decision.mode();
            turns.add(new TurnResult(index + 1, input, prompt, decision, run.trajectory()));
        }
        return List.copyOf(turns);
    }

    public CliWorkingContext memory() {
        return memory;
    }

    private RoutingDecision resolve(String input) {
        if (memory.hasContextualFileReference(input)
                && !router.hasExplicitWorkspaceTarget(input)
                && !memory.lastResolvedFiles().isEmpty()) {
            if (router.hasClearMutationIntent(input)) {
                return new RoutingDecision(CliMode.CODE, RoutingConfidence.HIGH,
                        RoutingReason.EXPLICIT_MUTATION_TARGET);
            }
            if (router.hasReadRequest(input)) {
                return new RoutingDecision(CliMode.READ, RoutingConfidence.HIGH,
                        RoutingReason.WORKSPACE_READ_REQUEST);
            }
        }
        RoutingDecision decision = router.route(input);
        if (decision.confidence() == RoutingConfidence.LOW && lastAutoRoute != null) {
            return new RoutingDecision(lastAutoRoute, RoutingConfidence.LOW,
                    RoutingReason.INCOMPLETE_CONTEXTUAL_REQUEST);
        }
        return decision;
    }

    private String prompt(String input, CliMode selectedMode) {
        if (selectedMode != CliMode.READ && selectedMode != CliMode.CODE) {
            return input;
        }
        boolean shouldInject = memory.mode() == WorkingMemoryMode.STRUCTURED_MEMORY
                ? memory.hasWorkspaceFacts()
                : memory.hasContextualFileReference(input) && !memory.lastResolvedFiles().isEmpty();
        if (!shouldInject) {
            return input;
        }
        String snapshot = memory.compactSnapshot();
        return snapshot.isBlank() ? input : snapshot + "\n\nUser request:\n" + input;
    }

    public record TurnResult(
            int turn,
            String userInput,
            String effectivePrompt,
            RoutingDecision routing,
            AgentTrajectory trajectory
    ) {
    }
}
