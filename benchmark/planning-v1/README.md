# planning-v1 DEV protocol

## Research Question

Under the same task, model, tools, workspace fixture, evaluator, and provider-request cap, does `PLAN_EXECUTE` produce a measurable difference from `REACTIVE` on multi-step coding tasks? The suite also includes simple, explicit, and ambiguity-sensitive work where planning may add cost without benefit.

## Modes

Each DEV task is run twice through the existing CODE profile: `PlanningMode.REACTIVE` and `PlanningMode.PLAN_EXECUTE`. Each condition gets a new Agent, progress state, provider client wrapper, and isolated copy of the same fixture. This protocol does not exercise InteractiveCli stdin.

## Experimental Controls

The manifest contains eight DEV tasks and no held-out TEST split. Both conditions use the same per-task provider-request cap, allowed tools, fixture, instruction, task-step evaluation ceiling, and evaluator. The runtime reuses `CliAgentFactory`; the harness reuses `ProviderBudget` and `BudgetedLlmClient`. Planning and replan calls consume the same budget as execution calls. The provider is injected explicitly; there is no implicit real-provider selection in this package.

`maxToolSteps` in the manifest is an evaluator ceiling only. It does not configure or raise the runtime's fixed `Agent.MAX_ITERATIONS` (currently 10); tool steps are still constrained by the existing Agent runtime behavior.

## DEV Tasks

1. `planning_dev_01_multistep_java` — locate, inspect, edit, and verify a Java implementation.
2. `planning_dev_02_two_requirements` — satisfy two ordered requirements in one file.
3. `planning_dev_03_wrong_anchor_recovery` — recover from an edit assumption while adding a method.
4. `planning_dev_04_test_failure_recovery` — diagnose a visible Maven test failure, repair, and verify.
5. `planning_dev_05_ambiguous_files` — inspect multiple configuration candidates without mutation.
6. `planning_dev_06_explicit_target` — simple edit with a named target and little need for exploration.
7. `planning_dev_07_simple_edit` — one small text edit to observe planning overhead.
8. `planning_dev_08_cross_file_reasoning` — read source file A, mutate only target file B.

## Metrics

The harness records task outcome and conversational completion separately, plus provider requests, tool steps, successful mutations, target drift, typed failures, repeated identical failures, explicit reads, verification attempts, replans, plan fallback, total plan steps, completed plan steps, and plan outcome. `plan_created`, fallback, replan count, plan steps, and plan outcome are behavioral measures only; they do not decide code-task success.

## Evaluator Contract

Success is derived from the resulting workspace, successful required tool/read evidence, allowed mutation targets, forbidden path absence, unchanged-file checks, tool-step cap, and required successful Maven verification after the latest mutation. Final-answer text such as “done” is never used as proof. Workspace task outcome is scored independently of whether the conversation reached a clean final response; the latter is reported as `conversationalCompletion`.

## Provider Budget Fairness

Each mode receives an equal task cap, and PLAN, REPLAN, and execution calls are all charged through the same `BudgetedLlmClient`/`ProviderBudget` mechanism. Planning therefore consumes part of its own condition's budget rather than receiving extra requests.

## Known Limitations

This is a small DEV protocol for checking the harness and observing paired behavior, not a statistically powered study. Scripted tests verify accounting and evaluator contracts but do not estimate model performance. Fixture-specific outcomes may not generalize. NO live result yet.
