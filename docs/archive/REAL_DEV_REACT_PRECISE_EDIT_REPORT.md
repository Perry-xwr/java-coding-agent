# REAL DEV REACT_PRECISE_EDIT REPORT

## 1. Experiment Setup

| Field | Value |
|---|---|
| Experiment ID | `20260924-054757-react_precise_edit` |
| Model / Provider | `glm-4-flash` / GLM |
| Temperature | provider default |
| Benchmark / Split | v0.1 / DEV |
| Tasks | 6 |
| Strategy | `REACT_PRECISE_EDIT` |
| Execution policy | One run only; no rerun |

The API key was visible to the process, but its value was never printed. The strategy wiring was checked before execution: `REACT_PRECISE_EDIT` uses the diagnostic-recovery behavior and precise-edit registry, while planning is disabled. Task-specific step budgets, benchmark fixtures, hidden tests, evaluator, prompts, tools, and model settings were not changed. No TEST task was run.

The run produced all required artifacts: per-task `trajectory.json` and `evaluation.json`, plus experiment-level `summary.json`, `summary.md`, and `failure_analysis.md`.

## 2. Overall Metrics

| Metric | Result |
|---|---:|
| Task Success Count | 1 / 6 |
| Task Success Rate | 16.67% |
| Completion Rate | 16.67% |
| Average Steps | 9.33 |
| Average Steps per Success | 9.00 |
| Average Tool Calls | 7.17 |
| Required-tool Usage Rate | 33.33% |
| Invalid Tool-call Rate | 0.00% |
| Test Failure Count | 1 |
| Recovery Attempts | 2 |
| Successful Recoveries | 0 |
| Test Recovery Rate | 0.00% |
| Max-step Termination Rate | 83.33% |
| Average Duration | 41,272.83 ms |
| Evaluator Errors | 0 |
| Exact Patch Attempts | 5 |
| Exact Patch Failures | 3 |
| Exact Patch Success Rate | 40.00% |
| Line Edit Attempts | 16 |
| Line Edit Successes | 1 |
| Line Edit Failures | 15 |
| Line Edit Success Rate | 6.25% |
| Stale Edit Failures | 6 |
| Patch Fallback Count | 1 |
| No-effect Patch Count | 1 |
| Budget Warnings | 6 |

## 3. Task Results

| Task | Success | Steps | Tool Calls | apply_patch | apply_patch Fail | replace_lines | replace_lines Success | Fallback | Failure Category |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| bugfix_001 | No | 8 | 6 | 0 | 0 | 2 | 0 | 0 | MAX_STEPS |
| bugfix_002 | No | 8 | 6 | 0 | 0 | 2 | 0 | 0 | MAX_STEPS |
| bugfix_003 | Yes | 9 | 7 | 2 | 2 | 1 | 1 | 1 | NONE |
| logic_001 | No | 10 | 8 | 1 | 0 | 5 | 0 | 0 | MAX_STEPS |
| testfix_001 | No | 8 | 6 | 0 | 0 | 2 | 0 | 0 | MAX_STEPS |
| multi_001 | No | 13 | 10 | 2 | 1 | 4 | 0 | 0 | MAX_STEPS |

All five failures exhausted the task step budget. The only completed task was also the only successful task.

## 4. Edit Tool Usage

`apply_patch` succeeded 2/5 times (40.00%). One successful patch produced malformed `logic_001` source; the other produced malformed `multi_001` source. Thus, transport-level patch success overstates useful edit success.

`replace_lines` succeeded 1/16 times (6.25%). Its 15 rejected calls comprised:

- 9 `INVALID_LINE_RANGE` failures: one in `bugfix_002`, five in `logic_001`, and three in `multi_001`.
- 6 `STALE_EDIT_CONTEXT` failures: two in `bugfix_001`, one in `bugfix_002`, two in `testfix_001`, and one in `multi_001`.

The rejections were valid safety behavior. The model repeatedly treated displayed line-number prefixes as file content, selected ranges outside compact two-to-four-line fixtures, or supplied only a substring where `expectedText` had to match the complete current range. No invalid tool-call schema was recorded; these were semantically invalid edit requests.

## 5. Patch Fallback Analysis

There was exactly one counted fallback, in `bugfix_003`:

1. Two `apply_patch` calls failed with `TEXT_NOT_FOUND` because the model assumed a multiline layout while the current class body was one line.
2. The Agent then reread the current source using `read_file` with line numbers.
3. It selected the correct range, line 2 through line 2.
4. `expectedText` exactly matched the latest line content and did not include the rendered line-number prefix.
5. `replace_lines` succeeded and expanded the one-line class into a valid implementation.
6. `run_maven_test` succeeded.
7. Hidden evaluation also passed, so the task succeeded.

This is strong local evidence for the intended fallback mechanism: the old exact-text path failed, rereading supplied current context, and line replacement recovered the edit. No other task recovered through fallback.

## 6. bugfix_003 Analysis

`bugfix_003` passed. The final implementation handles distinct but equal strings with `left.equals(right)` and handles null safely by returning `left == right` when either input is null. Hidden tests passed for distinct equal strings, two nulls, and one-null input.

The edit mechanism and reasoning both succeeded here. Although the non-strict textual content check did not find `Objects.equals`, behavioral evaluation passed and the resulting implementation is null-safe. This task must not be interpreted merely as “the tool wrote a file”: it compiled, was tested, and passed hidden behavioral evaluation.

## 7. Failure Analysis

### bugfix_001

- Agent reasoning issue: the model copied the rendered `2 |` line-number prefix into both `expectedText` and replacement text, then repeated the same mistake after rereading.
- Edit-tool behavior: two correct `STALE_EDIT_CONTEXT` rejections; no file change.
- Test behavior: only hidden evaluation ran after termination and correctly exposed subtraction behavior.
- Evaluator behavior: correct assertion failure; no evaluator error.
- Likely bottleneck: edit precision followed by step-budget exhaustion.

### bugfix_002

- Agent reasoning issue: first hallucinated line 3 in a two-line file, then supplied a nonmatching fragment for line 2 despite rereading.
- Edit-tool behavior: one `INVALID_LINE_RANGE` and one `STALE_EDIT_CONTEXT`, both valid safeguards.
- Test behavior: hidden evaluation correctly failed the age-18 boundary.
- Evaluator behavior: correctly reported the required change missing; no evaluator error.
- Likely bottleneck: line-number interpretation/edit precision and step budget.

### logic_001

- Agent reasoning issue: repeatedly selected nonexistent lines, then used an exact patch that inserted an unclosed block and retained `int max = 0`.
- Edit-tool behavior: five valid `INVALID_LINE_RANGE` rejections; the exact patch changed content but did not guarantee syntactic validity.
- Test behavior: no in-loop test was completed; hidden evaluation found compilation failure.
- Evaluator behavior: correctly parsed the compiler diagnostic; no evaluator error.
- Likely bottleneck: edit precision, code-generation reasoning, and missing validation before budget exhaustion.

### testfix_001

- Agent reasoning issue: supplied only the assertion substring for a one-line test class range and repeated it after rereading.
- Edit-tool behavior: two valid `STALE_EDIT_CONTEXT` rejections; no file change.
- Test behavior: hidden evaluation ran both tests and correctly retained the original assertion failure.
- Evaluator behavior: strict content and behavioral checks correctly failed; no evaluator error.
- Likely bottleneck: misunderstanding range-level `expectedText`, then step budget.

### multi_001

- Agent reasoning issue: three hallucinated line ranges were followed by an exact patch that moved statements outside the method; after the resulting compilation failure, recovery used stale context and finally attempted a no-effect patch.
- Edit-tool behavior: three `INVALID_LINE_RANGE`, one `STALE_EDIT_CONTEXT`, and one `NO_EFFECT_CHANGE` correctly rejected unsafe/no-op edits; the successful exact patch itself produced invalid Java.
- Test behavior: `run_maven_test` immediately exposed compilation failure; recovery did not succeed.
- Evaluator behavior: correctly reported compilation failure; no evaluator error.
- Likely bottleneck: edit precision and test-recovery reasoning, amplified by the step budget.

Failure distribution at task level: five `MAX_STEPS`; operationally, all five involved edit precision, two also produced malformed code through exact patch (`logic_001`, `multi_001`), one included failed test recovery (`multi_001`), and none were evaluator failures.

## 8. Edit Precision / Safety Side Effects

- Line-range errors were substantial: 9/16 line edits used invalid ranges.
- Stale-context protection fired 6 times and prevented mismatched replacement; these are safety rejections, not tool defects.
- MAX_STEPS increased to 83.33%, with a budget warning on every task.
- Average duration increased markedly to 41.27 seconds.
- Two exact patches produced malformed full-class edits. They were isolated to benchmark workspaces; no project source or fixture was changed.
- The successful `bugfix_003` edit was limited to its target source. The two malformed edits also affected only their expected target files; no unrelated source edits were found.
- No CRLF conversion occurred: changed files remained LF-only.
- No mojibake was detected.
- The new result directory contains no matches for API key, `GLM_API_KEY`, Authorization, or Bearer patterns.

## 9. Strategy Comparison

### Old best versus Precise Edit

| Metric | DIAGNOSTIC_RECOVERY | PRECISE_EDIT |
|---|---:|---:|
| Success | 4/6 | 1/6 |
| Success Rate | 66.67% | 16.67% |
| Completion Rate | 83.33% | 16.67% |
| Avg Steps | 5.67 | 9.33 |
| Avg Steps per Success | 4.75 | 9.00 |
| Avg Tool Calls | 4.33 | 7.17 |
| Max-step Rate | 16.67% | 83.33% |
| Avg Duration | 22,475.83 ms | 41,272.83 ms |
| Exact Patch Attempts / Failures | N/A | 5 / 3 |
| Line Edit Attempts / Successes / Failures | N/A | 16 / 1 / 15 |
| Stale Edit Failures | N/A | 6 |
| Patch Fallback Count | N/A | 1 |

Relative to the calibrated diagnostic-recovery result, Precise Edit lost three successes, added 3.67 average steps (+64.71%), added 2.83 average tool calls (+65.38%), increased average latency by 18,797.00 ms (+83.63%), and increased max-step termination by 66.67 percentage points.

### Complete DEV route

| Strategy | Success |
|---|---:|
| REACT | 0/6 |
| REACT_ACTION_ORIENTED | 3/6 |
| REACT_DIAGNOSTIC_RECOVERY | 4/6 |
| REACT_PLANNING | 3/6 |
| REACT_PRECISE_EDIT | 1/6 |

This is a small six-task DEV sample and does not establish statistical significance. It does, however, provide direct trajectory evidence about failure modes in this single frozen run.

## 10. Evidence-Based Conclusion

Phase 7 produced one genuine mechanism-level success: `bugfix_003` followed the intended `TEXT_NOT_FOUND -> reread -> replace_lines -> test -> success` path. That is real evidence that line-based fallback can recover a specific exact-patch mismatch.

The overall strategy did not produce positive DEV evidence. Fifteen of sixteen line edits failed safely, five of six tasks ended at MAX_STEPS, and aggregate success fell from 4/6 to 1/6 while steps, tool calls, and latency rose sharply. The dominant issue was model interaction with compact source layout and line-numbered reads, not an evaluator error. Because only one task recovered and the strategy introduced broad overhead/new edit failures, the observed regression cannot reasonably be attributed merely to stochastic movement between otherwise equivalent runs.

Under the requested decision rules, this is Situation D (`<= 3/6` with line-edit errors, overhead, and new failures): record the result and do not repair or rerun in place.

## 11. V1 Freeze Recommendation

Do not freeze `REACT_PRECISE_EDIT` as the V1 default and do not replace the calibrated `REACT_DIAGNOSTIC_RECOVERY` baseline. Preserve this experiment as negative evidence and keep the existing diagnostic-recovery configuration as the current credible best DEV baseline.

Do not enter final TEST evaluation yet with Precise Edit. If a V1 freeze means freezing the already calibrated diagnostic-recovery agent rather than adopting Phase 7, that remains defensible, but the next TEST decision should be made explicitly by the user. No TEST run was performed as part of this work.
