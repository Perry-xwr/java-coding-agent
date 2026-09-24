# Real DEV Diagnostic Recovery Report

## 1. Setup

- Project: `<repo-root>`
- Benchmark version: `v0.1`
- Split: `DEV` (6 tasks)
- Model: `glm-4-flash`
- Strategy: `REACT_DIAGNOSTIC_RECOVERY`
- Provider: project default
- Experiment ID: `20260924-014312-react_diagnostic_recovery`
- Result directory: `benchmark/results/20260924-014312-react_diagnostic_recovery`
- Command: `mvn "-Dmaven.repo.local=<repo-root>\.m2\repository" exec:exec "-Dexec.executable=java" "-Dexec.args=-classpath %classpath com.agent.benchmark.BenchmarkMain --baseline react_diagnostic_recovery --split dev"`
- The task-specific maximum-step settings were left unchanged.
- This was the only benchmark run performed for this acceptance pass. TEST and other baselines were not run.
- `GLM_API_KEY` was visible to the process; its value was never printed or recorded.

## 2. Overall Metrics

| Metric | Value |
|---|---:|
| Total / evaluable tasks | 6 / 6 |
| Successful tasks | 3 |
| Task success rate | 50.00% |
| Completion rate | 83.33% |
| Average steps | 6.17 |
| Average steps per success | 5.67 |
| Average tool calls | 4.50 |
| Required tool usage rate | 100.00% |
| Invalid tool-call rate | 0.00% |
| Test failures observed during agent execution | 2 |
| Diagnostic failures emitted | 2 |
| Recovery attempts | 1 |
| Successful test-failure recoveries | 0 |
| Test-failure recovery rate | 0.00% |
| Max-step termination rate | 16.67% |
| Average duration | 19,091 ms |
| Evaluator errors | 0 |

Maven reported `BUILD SUCCESS` for the benchmark command. The run itself took approximately 2 minutes 12 seconds.

## 3. Task-Level Results

| Task | Success | Steps | Tool calls | Patches | Agent test calls | Diagnostics | Budget warnings | Final category |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `bugfix_001` | Yes | 7 | 5 | 1 | 1 | 0 | 1 | `NONE` |
| `bugfix_002` | Yes | 4 | 3 | 1 | 1 | 0 | 0 | `NONE` |
| `bugfix_003` | No | 5 | 4 | 1 | 1 | 0 | 0 | `UNKNOWN` |
| `logic_001` | No | 11 | 8 | 2 | 2 | 2 | 1 | `MAX_STEPS` |
| `multi_001` | No | 4 | 3 | 1 | 1 | 0 | 0 | `UNKNOWN` |
| `testfix_001` | Yes | 6 | 4 | 1 | 1 | 0 | 1 | `NONE` |

Successful tasks were `bugfix_001`, `bugfix_002`, and `testfix_001`. The only max-step termination was `logic_001`.

## 4. Tool Usage

| Tool | Calls | Successful calls | Failed calls |
|---|---:|---:|---:|
| `apply_patch` | 7 | 7 | 0 |
| `search_code` | 6 | 6 | 0 |
| `read_file` | 5 | 5 | 0 |
| `run_maven_test` | 7 | 5 | 2 |
| `list_files` | 2 | 2 | 0 |

All tool calls were syntactically valid. The two failed tool executions were genuine Maven compilation failures in `logic_001`, not malformed tool calls.

## 5. Diagnostic Recovery Behavior

The run produced two `TEST_FAILED` observations, both in `logic_001`. Both diagnostics were classified as `UNKNOWN`, although the raw output clearly represented a Java compilation error. The diagnostic summary retained the important compilation context, file path, and line number, but the localized compiler message was mojibake. No symbol was extracted.

The first failure correctly caused the agent to continue instead of finalizing. It searched again, reread the changed file, attempted a second patch, and reran Maven. This demonstrates the intended recovery control flow. However, the second patch still left a duplicate local variable declaration, so the second test failed with the same compiler error and the task exhausted its step budget. Therefore, recovery behavior activated, but no recovery succeeded.

`bugfix_003` and `multi_001` produced no in-loop diagnostic because their agent-visible Maven runs passed. Their failures were discovered only by the post-run hidden evaluator, after the agent could no longer recover.

## 6. Patch Freshness

- Patch freshness warnings: 1
- Rereads after test failure: 1
- No-effect patches: 0

The sole freshness warning occurred in `logic_001` immediately after the first failed compile. The agent then searched and reread `MaxFinder.java` before its next patch. Thus the freshness mechanism changed the trajectory in the intended direction, although the resulting repair was still incorrect.

## 7. Budget Warnings

Three step-budget warnings were emitted: one each in `bugfix_001`, `logic_001`, and `testfix_001`. The warning did not prevent `bugfix_001` or `testfix_001` from completing successfully. In `logic_001`, it made the remaining budget explicit before the final repair and retest, but the task still reached `MAX_STEPS` because the repair did not remove the duplicate declaration.

## 8. Failure Analysis

### `bugfix_003`

The agent changed reference identity comparison to `left.equals(right)`. This fixed value equality for non-null strings and compiled cleanly, but it was not null-safe. The hidden test `TokenMatcherHiddenTest.comparesValuesAndNulls` errored. The failure was categorized as `UNKNOWN`, and the deterministic content check also failed. Compared with the action-oriented run, this trajectory was shorter and compiled, but it did not improve the scored outcome.

### `logic_001`

The first patch added an empty-input guard and initialized `max` from the first array element, but retained the old `int max = 0`, creating a duplicate declaration. After the first compilation failure, the agent reread the file and repatched it, but again retained two declarations. The localized compiler text was corrupted in the captured diagnostic and classified as `UNKNOWN`; nevertheless, file and line information were available. This is both a model patch-precision failure and a diagnostic parsing/encoding quality issue.

### `multi_001`

The agent changed the range summation to iterate inclusively from `Math.min(first, second)` through `Math.max(first, second)`. Hidden Maven tests passed (`exitCode: 0`, `testsPassed: true`), but the task was marked unsuccessful because the deterministic content check expected a particular textual form, including `<= end`. The implementation used `<= Math.max(first, second)` directly. This appears to be an evaluator false negative: behavior passed, while an over-specific source-shape constraint rejected the solution. The recorded benchmark score is retained unchanged.

## 9. Manual Review

- `bugfix_003`: partial behavioral improvement, but still incorrect for null input; no genuine task success improvement.
- `multi_001`: clear behavioral improvement over the earlier malformed/compilation-failing attempt; hidden tests passed, but the deterministic content validator rejected an equivalent implementation.
- `testfix_001`: genuine improvement. The agent correctly changed the expected value from 4 to 5, ran the test, and completed successfully.
- Sensitive-data scan of the new result directory found zero matches for API-key, Authorization, Bearer-token, and key-assignment patterns.
- No code, prompt, parser, guard, fixture, hidden test, evaluator, task, model, or max-step setting was modified during this acceptance run.

## 10. Three-Strategy Comparison

| Strategy | Experiment | Success | Completion | Avg steps | Avg tool calls | Invalid tool-call rate | Recovery rate | Max-step rate |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `REACT` | `20260924-004856-react` | 0/6 (0.00%) | 100.00% | 2.50 | 1.50 | 11.11% | 0.00% | 0.00% |
| `REACT_ACTION_ORIENTED` | `20260924-011046-react_action_oriented` | 3/6 (50.00%) | 66.67% | 7.33 | 5.83 | 0.00% | 0.00% | 33.33% |
| `REACT_DIAGNOSTIC_RECOVERY` | `20260924-014312-react_diagnostic_recovery` | 3/6 (50.00%) | 83.33% | 6.17 | 4.50 | 0.00% | 0.00% | 16.67% |

Relative to plain REACT, both later strategies substantially improved success and eliminated invalid tool calls. Relative to ACTION_ORIENTED, DIAGNOSTIC_RECOVERY kept the same scored success count while improving completion, reducing average steps and tool calls, and halving max-step terminations. It also caused a failed-test trajectory to reread, repatch, and retest. However, it produced no successful test-failure recovery, so the measured recovery objective remains unmet.

## 11. Evidence-Based Conclusion

`REACT_DIAGNOSTIC_RECOVERY` provides a real control-flow benefit: the agent no longer treats a test failure as the end of the trajectory, and its freshness and budget signals are observable in the expected places. It is also more efficient and completes more often than `REACT_ACTION_ORIENTED` on this DEV set. The benefit did not translate into a higher scored success rate or a non-zero recovery rate in this run. The remaining failures are not primarily evidence that more runtime guards are needed: one is a null-safety reasoning error, one is an imprecise repair after a compiler diagnostic, and one is a likely evaluator false negative despite passing hidden tests. Diagnostic quality is also weakened by locale/encoding corruption and `UNKNOWN` classification.

## 12. Recommended Next Step

Freeze the runtime strategy and perform a focused DEV benchmark/evaluator quality audit before any TEST run. The audit should validate behavior-based acceptance for semantically equivalent patches and reliable decoding/classification of localized compiler diagnostics. After those measurements are trustworthy, further improvement should target model repair reasoning and patch precision rather than adding more guards.
