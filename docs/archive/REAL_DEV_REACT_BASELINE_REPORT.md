# Real GLM DEV ReAct Baseline Report

## 1. Experiment Setup

| Setting | Value |
| --- | --- |
| Experiment ID | `20260924-004856-react` |
| Benchmark version | `v0.1` |
| Split | `DEV` |
| Model | `glm-4-flash` |
| Provider / base URL | GLM / `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| Baseline | `REACT` |
| Expected / executed tasks | 6 / 6 |
| Evaluable tasks / evaluator errors | 6 / 0 |
| Per-task max steps | 6–10; experiment metadata maximum: 10 |
| Temperature | Provider default; not explicitly sent |

The existing Agent, prompt, tool schemas, fixtures, evaluators, hidden tests, and provider request configuration were not changed for this run. Only the DEV split was executed. No TEST task or other baseline was run.

## 2. Overall Metrics

| Metric | Result |
| --- | ---: |
| Task success | 0 / 6 |
| Task success rate | 0.000 |
| Completion rate | 1.000 |
| Average steps | 2.500 |
| Average steps per success | 0.000 (no successful task) |
| Average tool calls | 1.500 |
| Invalid tool-call rate | 0.111 |
| Test failure count | 0 |
| Recovery attempts | 0 |
| Test failure recovery rate | 0.000 (no observed `TEST_FAILED`) |
| Max-step termination rate | 0.000 |
| Average duration | 11,019.167 ms |
| Required-tool usage rate | 0.000 |

Completion and task success diverged completely: every run produced a normal final answer, but none changed and validated the workspace.

## 3. Task Results

| Task ID | Category | Difficulty | Success | Termination | Steps | Tool Calls | Failure Category |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| bugfix_001 | BUG_FIX | EASY | No | FINAL_ANSWER | 2 | 1 | PREMATURE_FINAL |
| bugfix_002 | BUG_FIX | EASY | No | FINAL_ANSWER | 2 | 1 | PREMATURE_FINAL |
| bugfix_003 | BUG_FIX | MEDIUM | No | FINAL_ANSWER | 2 | 1 | PREMATURE_FINAL |
| logic_001 | LOGIC_FIX | MEDIUM | No | FINAL_ANSWER | 4 | 3 | PREMATURE_FINAL |
| testfix_001 | TEST_FIX | EASY | No | FINAL_ANSWER | 3 | 2 | INVALID_ARGUMENTS |
| multi_001 | MULTI_STEP_DEBUG | HARD | No | FINAL_ANSWER | 2 | 1 | PREMATURE_FINAL |

Every evaluator ran successfully. Every expected file remained readable but unchanged, and every injected hidden-test Maven run exited with code 1.

## 4. Tool Usage

| Tool | Calls | Success | Failure |
| --- | ---: | ---: | ---: |
| list_files | 2 | 1 | 1 |
| read_file | 1 | 1 | 0 |
| search_code | 6 | 6 | 0 |
| apply_patch | 0 | 0 | 0 |
| run_maven_test | 0 | 0 | 0 |

The Agent primarily searched. It never invoked either environment-changing or verification tool. Consequently there was no patch/test loop and no opportunity to observe test-failure recovery.

## 5. Automatic Failure Analysis

The existing deterministic classifier produced:

| Failure Category | Count | Rate | Representative Task IDs |
| --- | ---: | ---: | --- |
| PREMATURE_FINAL | 5 | 0.833 | bugfix_001, bugfix_002, bugfix_003 |
| INVALID_ARGUMENTS | 1 | 0.167 | testfix_001 |

These labels are copied from the generated `failure_analysis.md` without manual relabeling.

One non-scoring evaluator diagnostic issue was observed: `bugfix_001` uses `MAVEN_TEST`, but its `failureReason` reported the evaluator-only content check before reporting the failed hidden Maven test. The task still correctly failed because `testsPassed=false`, the automatic failure category remained `PREMATURE_FINAL`, and no score changed. This run was not modified or repeated; the diagnostic-order issue is recorded for later maintenance.

## 6. Manual Trajectory Review

The following root-cause statements are **manual analysis**, not automatic metrics.

### bugfix_001

- Observed behavior: searched literally for `Calculator.add`, received no matches, then claimed the method already returned a sum.
- First clearly problematic step: final answer at step 2.
- Available observation: an empty search result; no source had been read.
- Next action: no broader search, file listing, patch, or test.
- Likely root cause (manual analysis): unsupported inference after an overly specific unsuccessful search.

### bugfix_002

- Observed behavior: searched for natural-language text `adult boundary`, received no matches, and stopped.
- First clearly problematic step: search strategy at step 1, followed by finalization.
- Available observation: empty search result.
- Next action: stated that AgePolicy could not be located instead of listing files or searching for the class name.
- Likely root cause (manual analysis): poor query selection and no search fallback.

### bugfix_003

- Observed behavior: found the exact `TokenMatcher` source and correctly explained both reference equality and null handling.
- First clearly problematic step: final answer at step 2.
- Available observation: the complete faulty one-line implementation using `left == right`.
- Next action: returned a proposed code block instead of invoking `apply_patch` and `run_maven_test`.
- Likely root cause (manual analysis): the model treated the coding task as an advisory answer rather than an environment-editing task.

### logic_001

- Observed behavior: listed files, read `MaxFinder.java`, searched the relevant return statement, and correctly diagnosed negative-array and empty-input behavior.
- First clearly problematic step: final answer at step 4.
- Available observation: full source content and exact code location.
- Next action: emitted corrected source in Markdown without applying or testing it.
- Likely root cause (manual analysis): failure to transition from correct diagnosis to tool execution.

### testfix_001

- Observed behavior: called `list_files` with an empty `path`, producing `INVALID_ARGUMENTS`; then recovered enough to locate the incorrect assertion with `search_code`.
- First clearly problematic step: invalid empty-path call at step 1; decisive failure was finalization at step 3.
- Available observation: structured invalid-argument error, followed by the exact failing assertion `assertEquals(4, ...)`.
- Next action: described what should change but did not patch or test.
- Likely root cause (manual analysis): weak argument validation before action plus incomplete recovery after locating the fix.

### multi_001

- Observed behavior: found the exact `RangeSum` implementation and correctly described swapping reversed bounds and using an inclusive loop.
- First clearly problematic step: final answer at step 2.
- Available observation: the full buggy implementation.
- Next action: returned a corrected code block without modifying or testing the workspace.
- Likely root cause (manual analysis): correct reasoning was not converted into environment actions.

No repeated-action or max-step pattern occurred. No `TEXT_NOT_FOUND`, `MULTIPLE_MATCHES`, or `TEST_FAILED` observation occurred because `apply_patch` and `run_maven_test` were never called.

## 7. Representative Successful Trajectory

Unavailable: this real run had no successful task. Inventing or substituting a FakeLLM trajectory would misrepresent the baseline.

## 8. Representative Failed Trajectory

`logic_001`:

```text
list_files (success)
→ read_file MaxFinder.java (success)
→ search_code "return max" (success)
→ correct diagnosis and proposed code in final answer
→ no apply_patch
→ no run_maven_test
→ evaluator failure: required file unchanged / hidden tests failed
```

This is representative because the Agent had sufficient evidence and a correct repair concept, yet still stopped before acting.

## 9. Observed Bottlenecks

1. **Reasoning-to-action gap:** in four trajectories the model either found the exact faulty code or read the full source and proposed a plausible fix, but made zero patch calls.
2. **No verification behavior:** zero `run_maven_test` calls across all six tasks, so normal completion was repeatedly mistaken for task completion.
3. **Fragile discovery/recovery:** two tasks stopped after empty literal searches, and one invalid `list_files` argument was followed by partial discovery but not a completed repair.

There is no evidence in this run for excessive step use, repeated actions, ignored test failures, or patch-mismatch recovery problems.

## 10. Phase 5 Candidates

These are candidates only; none is implemented here.

### Candidate A: Action-oriented completion contract

Evidence: five automatic `PREMATURE_FINAL` failures and zero required-tool usage. Investigate a provider-neutral completion policy that requires workspace-changing tasks to demonstrate an actual edit and verification before finalization.

### Candidate B: Progress tracking / lightweight planning

Evidence: the model often diagnosed the bug but lost the remaining execution stages. Explicit state such as inspect → edit → verify may reduce the reasoning-to-action gap without task-specific rules.

### Candidate C: Error-aware search and argument recovery

Evidence: empty literal searches caused two early exits, while an invalid empty path produced the only invalid tool call. Evaluate generic fallback behavior after empty search or typed argument errors.

## 11. Conclusion

The current GLM ReAct baseline completed all six DEV conversations normally but solved none of the deterministic coding tasks. Its strongest observed capability was diagnosis: several trajectories identified the correct defect and even described a plausible repair. Its principal failure was execution discipline—no source patch and no Maven verification occurred in any task.

This is a single six-task DEV run and is useful as an initial engineering baseline, not as a statistically stable model-performance estimate. The fixed 14-task TEST split remains untouched.

## Integrity Checks

- Six expected DEV tasks were executed and evaluated; evaluator errors: 0.
- A minor `MAVEN_TEST` failure-reason ordering issue was observed for `bugfix_001`; it had no scoring effect.
- All per-task `trajectory.json` and `evaluation.json` files and all experiment summary files are readable.
- Each task used its own reset workspace; hidden tests appeared only during post-run evaluation.
- Exact API-key scan hits: 0; `Authorization`, `Bearer`, and `GLM_API_KEY` scan hits: 0.
- Experiment results: `benchmark/results/20260924-004856-react/`.
