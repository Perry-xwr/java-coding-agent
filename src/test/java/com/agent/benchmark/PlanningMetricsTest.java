package com.agent.benchmark;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentPlan;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.RequirementStatus;
import com.agent.agent.TaskRequirement;
import com.agent.agent.TerminationReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningMetricsTest {
    @Test
    void calculatesPlanningAndRequirementMetrics() {
        AgentPlan plan = new AgentPlan(
                "complete behavior",
                List.of(
                        new TaskRequirement("R1", "first", RequirementStatus.COMPLETED, "patched"),
                        new TaskRequirement("R2", "second", RequirementStatus.COMPLETED, "tested")
                ),
                "done",
                ""
        );
        List<AgentStep> steps = List.of(
                step(1, AgentActionType.PLAN_CREATED),
                step(2, AgentActionType.PLAN_UPDATED),
                step(3, AgentActionType.PLAN_COMPLETION_FEEDBACK),
                step(4, AgentActionType.FINAL_ANSWER)
        );
        AgentRunResult run = new AgentRunResult("done", new AgentTrajectory(
                "run", "task", steps, "done", TerminationReason.FINAL_ANSWER,
                true, null, 0, 1, plan
        ));
        BenchmarkTask task = new BenchmarkTask(
                "planning", TaskCategory.LOGIC_FIX, TaskDifficulty.MEDIUM,
                "task", "fixture", List.of("App.java"), EvaluationType.COMBINED,
                5, List.of(), null, BenchmarkSplit.DEV, List.of()
        );
        BenchmarkRunRecord record = new BenchmarkRunRecord(
                task,
                BaselineType.REACT_PLANNING,
                run,
                new EvaluationResult("planning", true, true, true, true, null, Map.of()),
                FailureCategory.NONE,
                false
        );

        BenchmarkMetrics metrics = new BenchmarkMetricsCalculator().calculate(List.of(record));

        assertEquals(1, metrics.plansCreated());
        assertEquals(1, metrics.planUpdates());
        assertEquals(2, metrics.requirementsTotal());
        assertEquals(2, metrics.requirementsCompleted());
        assertEquals(0, metrics.requirementsRemainingAtFinal());
        assertEquals(1, metrics.planCompletionWarnings());
    }

    private static AgentStep step(int index, AgentActionType type) {
        return new AgentStep(
                index, type, null, null, null, Map.of(), null,
                type == AgentActionType.FINAL_ANSWER ? "done" : null,
                type == AgentActionType.PLAN_COMPLETION_FEEDBACK ? "pending" : null,
                0, 0
        );
    }
}
