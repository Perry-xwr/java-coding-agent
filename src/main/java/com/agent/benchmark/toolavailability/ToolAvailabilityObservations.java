package com.agent.benchmark.toolavailability;

import com.agent.environment.ToolAvailabilityDecision;
import com.agent.llm.ToolDefinition;
import com.agent.agent.AgentStep;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** Condition-local invocation-time observations; never reconstructed from final workspace state. */
final class ToolAvailabilityObservations {
    private final List<ToolAvailabilitySnapshot> snapshots = new ArrayList<>();
    private final List<Boolean> mavenInvocationPomSnapshots = new ArrayList<>();
    private boolean initialized;
    private boolean initialPom;
    private boolean lastPom;
    private boolean finalPom;
    private Boolean lastAvailability;
    private int availabilityTransitions;
    private int turns;
    private int advertisedTurns;
    private int mavenAttempts;
    private int mavenExecutions;
    private int mavenSuccesses;
    private int mavenFailures;
    private int mismatchAttempts;
    private int mismatchExecutions;
    private int mismatchRejections;
    private int appropriateAttempts;
    private int missingProjectFailures;

    ToolAvailabilityObservations() { }

    synchronized void providerTurn(List<ToolDefinition> definitions, ToolAvailabilityDecision decision) {
        snapshot("PROVIDER_ADVERTISEMENT", decision);
        turns++;
        if (definitions.stream().anyMatch(tool -> "run_maven_test".equals(tool.name()))) advertisedTurns++;
    }

    synchronized void observe(String event, ToolAvailabilityDecision decision) {
        snapshot(event, decision);
    }

    synchronized void beforeInvocation(ToolAvailabilityDecision decision) {
        snapshot("TOOL_INVOCATION", decision);
        mavenAttempts++;
        mavenInvocationPomSnapshots.add(lastPom);
        if (lastPom) appropriateAttempts++;
        else mismatchAttempts++;
    }

    synchronized void afterExecution(boolean safePomAtInvocation, int processRunsBefore, int processRunsAfter) {
        if (processRunsAfter > processRunsBefore) {
            mavenExecutions++;
            if (!safePomAtInvocation) mismatchExecutions++;
        }
    }

    synchronized void reconcileResults(List<AgentStep> steps) {
        int index = 0;
        for (AgentStep step : steps) {
            if (!"run_maven_test".equals(step.toolName()) || step.toolResult() == null) continue;
            ToolResult result = step.toolResult();
            boolean safePom = index < mavenInvocationPomSnapshots.size() && mavenInvocationPomSnapshots.get(index);
            index++;
            if (result.success()) mavenSuccesses++;
            else mavenFailures++;
            if (!safePom && result.errorCode() == ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT) {
                mismatchRejections++;
            }
            String diagnostic = (result.errorMessage() + " " + result.output() + " " + result.metadata())
                    .toLowerCase(java.util.Locale.ROOT);
            if (!safePom && (diagnostic.contains("missingprojectexception")
                    || diagnostic.contains("no pom")
                    || diagnostic.contains("there is no pom"))) missingProjectFailures++;
        }
    }

    synchronized void observeFinal(ToolAvailabilityDecision decision) {
        snapshot("FINAL", decision);
        finalPom = lastPom;
    }

    private void snapshot(String event, ToolAvailabilityDecision decision) {
        boolean safePom = "MAVEN".equals(decision.capabilitySnapshot());
        if (!initialized) {
            initialized = true;
            initialPom = safePom;
            lastPom = safePom;
            lastAvailability = decision.available();
        } else {
            if (lastAvailability != decision.available()) availabilityTransitions++;
            lastAvailability = decision.available();
            lastPom = safePom;
        }
        finalPom = safePom;
        snapshots.add(new ToolAvailabilitySnapshot(snapshots.size() + 1, event, safePom,
                decision.available()));
    }

    synchronized Snapshot result() {
        return new Snapshot(initialized && initialPom, initialized && finalPom,
                initialized && !initialPom && finalPom, availabilityTransitions,
                turns, advertisedTurns, mavenAttempts, mavenExecutions, mavenSuccesses, mavenFailures,
                mismatchAttempts, mismatchExecutions, mismatchRejections, appropriateAttempts,
                missingProjectFailures, List.copyOf(snapshots));
    }

    record Snapshot(boolean initialPom, boolean finalPom, boolean pomCreatedDuringRun,
                    int availabilityTransitions, int turns, int advertisedTurns,
                    int mavenAttempts, int mavenExecutions, int mavenSuccesses, int mavenFailures,
                    int mismatchAttempts, int mismatchExecutions, int mismatchRejections,
                    int appropriateAttempts, int missingProjectFailures,
                    List<ToolAvailabilitySnapshot> snapshots) { }
}
