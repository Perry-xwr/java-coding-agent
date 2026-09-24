# Phase 6 Planning and Progress Report

## 1. Motivation

The calibrated `REACT_DIAGNOSTIC_RECOVERY` DEV measurement scored 4/6. `bugfix_003` understood the intended repair but repeatedly failed exact-text patch application. `logic_001` fixed negative-only arrays but omitted the independent empty-input exception requirement, then treated an Agent-visible Maven pass as completion.

Phase 6 addresses requirement completeness through lightweight public planning and progress tracking. It does not alter the exact-text patch algorithm, benchmark, evaluator, diagnostic parser, model, or step budgets.

## 2. Planning Design

The independent strategy is `REACT_PLANNING`. It includes the Phase 5A action-completion and Phase 5B diagnostic-recovery behavior, then enables planning only for `TaskMode.CODE_MODIFICATION`.

Planning remains inside the existing single-Agent ReAct decision. The model can include a compact plan update alongside tool calls:

```text
<plan_update>{...public task plan JSON...}</plan_update>
```

This is structured assistant output, not an environment tool and not private chain-of-thought. It creates no separate planner request. Plan events are recorded as trajectory steps in the same decision and do not consume an additional max-iteration slot.

## 3. Requirement Schema

`AgentPlan` contains `goal`, `requirements`, `currentFocus`, and `notes`. Plans must contain 1–8 requirements with unique stable IDs.

`TaskRequirement` contains `id`, `description`, `status`, and `evidence`. Statuses are `PENDING`, `IN_PROGRESS`, `COMPLETED`, and `BLOCKED`. A requirement cannot be accepted as `COMPLETED` without non-empty evidence. Plan text is a public execution checklist and never stores model chain-of-thought.

## 4. Progress Tracking

`AgentProgress` now stores the current plan and exposes completed count, remaining count, and current focus. Before each planning-strategy decision, the Agent constructs one transient compact context message:

```text
Current Progress
Goal: ...
Focus: ...
Completed: ...
Remaining: ...
```

The compact message is passed to that model decision without being appended to persistent history, preventing cumulative context duplication. The model explicitly updates the complete plan snapshot after inspection, patching, tests, failures, or newly discovered requirements. Runtime observations never automatically claim that a behavioral requirement is complete.

## 5. Final Completion Check

For a planning-enabled code-modification task, an attempted final answer receives `PLAN_COMPLETION_FEEDBACK` when no plan exists or the plan has unfinished requirements. The feedback lists pending IDs and descriptions and states that a passing visible test is not proof of overall task completion.

This warning is bounded to once per run. After the bound is reached, the existing finite ReAct loop proceeds normally, preventing an infinite feedback cycle. Existing no-patch and failed-test guards retain priority; planning completion is checked before the validation guard so independent pending behavior is not hidden by the absence of a test call.

## 6. Prompt Changes

Only the new `REACT_PLANNING` prompt adds planning instructions. It asks the model to identify independent requirements before editing, keep 2–8 concise requirements with stable IDs, update status using observable evidence, avoid treating one fix or visible test as full completion, and review remaining requirements before finalizing.

`REACT`, `REACT_ACTION_ORIENTED`, and `REACT_DIAGNOSTIC_RECOVERY` continue selecting their previous prompts and runtime flags.

## 7. Trajectory Changes

New action types are `PLAN_CREATED`, `PLAN_UPDATED`, and `PLAN_COMPLETION_FEEDBACK`. The final `AgentTrajectory` optionally stores the latest `AgentPlan`; existing trajectory construction remains source-compatible through the prior constructor.

A typical sequence is visible as:

```text
PLAN_CREATED → TOOL_CALL → PLAN_UPDATED → TOOL_CALL
→ PLAN_COMPLETION_FEEDBACK → PLAN_UPDATED → FINAL_ANSWER
```

Plan actions add audit events but do not add model decisions or change task-specific `maxSteps`.

## 8. Metrics

All existing metrics remain. New metrics are `plansCreated`, `planUpdates`, `requirementsTotal`, `requirementsCompleted`, `requirementsRemainingAtFinal`, and `planCompletionWarnings`. Counts come from structured trajectory events and the final plan, not console text.

## 9. Deterministic Tests

`PlanningProgressTest` covers six behaviors:

1. a multi-requirement task fixes only the first requirement, attempts final, receives one warning, then completes the second requirement and validation;
2. all requirements complete before final and no warning is emitted;
3. Maven passes while a requirement remains pending, and final is still interrupted;
4. a failed test causes a new R3 requirement to be added and later completed;
5. a READ_ONLY task completes without planning;
6. repeated final attempts with pending requirements produce at most one warning.

The tests assert `PLAN_CREATED` precedes the first patch and compact progress reaches later decisions. `PlanningMetricsTest` verifies all six planning metrics.

Final offline suite:

```text
Tests run: 110, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

No real GLM request or benchmark run occurred.

## 10. Backward Compatibility

- Phase 1 workspace safety tests pass.
- Phase 2 typed trajectory tests pass.
- Phase 3 coding-loop tests pass.
- Phase 4 benchmark and evaluator tests pass.
- Phase 5A completion-guard tests pass.
- Phase 5B diagnostic-recovery tests pass.
- Evaluator calibration and encoding tests pass.
- READ_ONLY tasks do not require or receive planning state.
- The three prior REACT strategies remain independently selectable and do not enable planning.
- Benchmark tasks, fixtures, hidden tests, evaluation checks, evaluator semantics, model settings, and task-specific maximum steps were not changed.

## 11. Limitations

- Model-generated requirements may themselves be incomplete.
- Planning improves observability and completion discipline but does not guarantee correctness.
- Explicit model evidence can still be mistaken; runtime does not semantically prove each claim.
- The one-warning bound deliberately permits a later final answer with pending requirements rather than creating an infinite loop.
- There is no separate planner, reviewer, critic, Multi-Agent system, tree search, or additional LLM call.
- The exact-text patch tool and its failure modes are unchanged.
- Planning events increase trajectory step counts even though they do not consume decision iterations.

## 12. Next Experiment

After review, run one DEV-6 measurement with `glm-4-flash` and `REACT_PLANNING`, keeping provider defaults and task-specific maximum steps unchanged. Compare it against the calibrated `REACT_DIAGNOSTIC_RECOVERY` baseline of 4/6, especially requirement coverage, plan warnings, patch/test behavior, and latency. Do not run TEST until the Agent feature set is substantially frozen. This report does not run that experiment.
