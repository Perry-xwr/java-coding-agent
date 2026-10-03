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

This is a small DEV protocol for checking the harness and observing paired behavior, not a statistically powered study. Scripted tests verify accounting and evaluator contracts but do not estimate model performance. Fixture-specific outcomes may not generalize.

## Live DEV Evaluation

### Experimental Commit

- Benchmark commit: `f2a453fb3c0ef2b0107a7c60f7bcb6663a37c0c3`
- Runtime commit: `7cbe65d8ed554092be7ec7e572866d089bdde695`
- Both rounds used the same checked-out code and the same eight manifest tasks.

### Provider

GLM / `glm-4-flash`, using the configured HTTP proxy. One minimal credential/response smoke returned `READY`; no task workspace or tool was involved in that request. The benchmark then used 213 provider requests across both rounds. API token usage and cost were unavailable from the captured provider/runtime data.

### Round 1

Order: manifest order, `REACTIVE` then `PLAN_EXECUTE` per task. All 16 conditions completed; there were no infrastructure errors and no per-condition request caps were reached.

| Metric | REACTIVE | PLAN_EXECUTE |
|---|---:|---:|
| Evaluator workspace outcome | 2/8 | 3/8 |
| Conversational completion | 2/8 | 3/8 |
| Provider requests (average/run) | 41 (5.13) | 56 (7.00) |
| Tool steps (average/run) | 29 (3.63) | 33 (4.13) |
| Successful mutations | 5 | 7 |
| Target drift | 2 | 0 |
| Typed tool failures | 8 | 4 |
| Repeated identical failures | 1 | 0 |
| Explicit reads | 15 | 13 |
| Verification attempts | 1 | 2 |
| Replans / plan fallbacks | 0 / 0 | 1 / 1 |
| Plan steps total / completed | 0 / 0 | 72 / 10 |

Seven PLAN_EXECUTE runs ended with a partial plan outcome; one fell back to REACTIVE after a replan validation error (`IllegalArgumentException`).

### Round 2

Order: manifest order, `PLAN_EXECUTE` then `REACTIVE` per task. All 16 conditions completed; there were no infrastructure errors and no per-condition request caps were reached.

| Metric | REACTIVE | PLAN_EXECUTE |
|---|---:|---:|
| Evaluator workspace outcome | 5/8 | 2/8 |
| Conversational completion | 2/8 | 3/8 |
| Provider requests (average/run) | 53 (6.63) | 63 (7.88) |
| Tool steps (average/run) | 40 (5.00) | 39 (4.88) |
| Successful mutations | 8 | 7 |
| Target drift | 0 | 1 |
| Typed tool failures | 7 | 7 |
| Repeated identical failures | 0 | 0 |
| Explicit reads | 19 | 14 |
| Verification attempts | 3 | 4 |
| Replans / plan fallbacks | 0 / 0 | 4 / 0 |
| Plan steps total / completed | 0 / 0 | 90 / 9 |

All eight PLAN_EXECUTE runs had a partial plan outcome. Replanning was observed, but did not produce a repeatable evaluator success advantage.

### Descriptive Aggregate

This is the same eight DEV tasks repeated twice: **16 task-runs per mode across two repeated rounds**, not 16 independent tasks.

| Metric | REACTIVE | PLAN_EXECUTE |
|---|---:|---:|
| Evaluator outcomes | 7/16 | 5/16 |
| Conversational completion | 4/16 | 6/16 |
| Provider requests (average/run) | 94 (5.88) | 119 (7.44) |
| Tool steps (average/run) | 69 (4.31) | 72 (4.50) |
| Successful mutations | 13 | 14 |
| Target drift | 2 | 1 |
| Typed tool failures | 15 | 11 |
| Repeated identical failures | 1 | 0 |
| Explicit reads | 34 | 27 |
| Verification attempts | 4 | 6 |
| Replans / plan fallbacks | 0 / 0 | 5 / 1 |
| Plan steps total / completed | 0 / 0 | 162 / 19 |

### Per-task Findings

`Δ requests` is PLAN_EXECUTE minus REACTIVE within that round. `Pass` means the deterministic workspace evaluator passed; it is distinct from conversational completion.

| Task | Round 1 R / P | Δ requests | Round 2 R / P | Δ requests | Observation |
|---|---|---:|---|---:|---|
| `planning_dev_01_multistep_java` | Fail / Fail | +2 | Fail / Fail | +1 | Several conditions did not read the test source required by the evaluator; Round 2 REACTIVE also left the expected implementation/verification unsatisfied. |
| `planning_dev_02_two_requirements` | Fail / Pass | +5 | Pass / Fail | +1 | Outcome reversed between rounds. Failed conditions either targeted a root-level `Settings.java` instead of the fixture source path or did not satisfy the expected target state. |
| `planning_dev_03_wrong_anchor_recovery` | Fail / Fail | +2 | Fail / Fail | +2 | The requested method behavior was not consistently present; one condition created a wrong-path file and others left the null/blank behavior incomplete. |
| `planning_dev_04_test_failure_recovery` | Fail / Fail | +3 | Pass / Fail | +1 | Round 2 REACTIVE repaired and verified normalization behavior; other failures lacked required test-source read evidence or did not make the required edit. |
| `planning_dev_05_ambiguous_files` | Fail / Fail | +1 | Fail / Fail | +1 | Both modes in both rounds used content search or stopped early instead of discovering and reading both configuration candidates. |
| `planning_dev_06_explicit_target` | Pass / Pass | +1 | Pass / Pass | +1 | Both modes passed in both rounds; planning consistently added one provider request. |
| `planning_dev_07_simple_edit` | Pass / Pass | +1 | Pass / Pass | +1 | Both modes passed in both rounds; planning consistently added one provider request. |
| `planning_dev_08_cross_file_reasoning` | Fail / Fail | 0 | Pass / Fail | +2 | Only Round 2 REACTIVE completed the constrained source-read/target-edit task; other runs had path/anchor failures or exhausted the runtime step limit. |

Task 2 is sampling-sensitive: the successful mode switched between rounds. Tasks 6 and 7 passed equally in both modes, while PLAN_EXECUTE used one extra request each time. No task showed a repeatable PLAN_EXECUTE-only success across both rounds.

### Planning Overhead

PLAN_EXECUTE used 15 more requests than REACTIVE in Round 1 and 10 more in Round 2. Across the repeated task-runs it used 25 more requests total, or 1.56 additional requests per run on average. For the explicit-target and simple-edit tasks, the overhead was exactly +1 request in each round with no outcome difference. Tool-step differences were mixed rather than consistently lower or higher.

### Replanning Observations

Five replans were recorded: one in Round 1 and four in Round 2. Some replans changed the next actions (for example, returning to source reads or changing discovery strategy), but none established a repeatable task-success improvement. A replan event is not itself evidence of successful recovery.

### Plan Fallback

One fallback occurred in Round 1 after a replan failed validation with `IllegalArgumentException`; the agent continued in REACTIVE mode. No fallback occurred in Round 2. These are observations of this run, not estimates of general plan-format reliability.

### Infrastructure Failures

No provider, proxy, runner, or task-condition infrastructure errors were recorded in either benchmark round. All 32 task-condition runs completed, with no request cap reached. An initial attempted Maven `exec:java` invocation selected the POM's hard-coded `com.agent.Main` and did not start the benchmark or issue a provider request; the verified benchmark entry point was then launched directly as `PlanningBenchmarkMain`. The credential smoke and benchmark outputs were not added to Git.

### Protocol Limitations

- The two rounds reuse the same eight DEV tasks; the aggregate is repeated-run description, not an independent sample. GLM behavior is stochastic, and the results do not establish statistical significance.
- Task 1's evaluator requires reading the test source even though the user instruction does not explicitly require that read. Three conditions produced the requested source edit/Maven behavior but still failed the evaluator for missing this evidence. This contract limitation remains known: the protocol was not modified after observing results, and the results were not rescored.
- Evaluator success and conversational completion are separate: some runs reached a final answer without meeting workspace/evidence requirements, and some workspace conditions passed despite incomplete conversation termination.
- Provider token usage and monetary cost were unavailable and are not estimated.

### Conclusion

Structured planning did not show a repeatable success advantage on this small DEV set and introduced consistent provider-request overhead on simple tasks. The aggregate evaluator outcome was 5/16 for PLAN_EXECUTE and 7/16 for REACTIVE, with 119 versus 94 requests. Task-level outcomes varied across rounds, so these observations are preliminary and descriptive only; they do not establish that either mode is generally better.

Raw run data (JSONL results and sanitized trajectories) is stored under the ignored path `benchmark-runs/planning-v1/f2a453f/round-1/` and `benchmark-runs/planning-v1/f2a453f/round-2/`. A compact machine-readable aggregate is stored under `benchmark-runs/planning-v1/f2a453f/summary/`.
