# Calibrated DEV Diagnostic Recovery Report

## 1. Experiment Setup

- Experiment ID: `20260924-022022-react_diagnostic_recovery`
- Model: `glm-4-flash`
- Provider: GLM
- Provider settings: default temperature
- Benchmark: `v0.1`
- Split: DEV
- Expected / executed / evaluated tasks: `6 / 6 / 6`
- Strategy: `REACT_DIAGNOSTIC_RECOVERY`
- Task-specific maximum steps: unchanged
- Result directory: `benchmark/results/20260924-022022-react_diagnostic_recovery`
- Maven result: `BUILD SUCCESS`
- Wall-clock benchmark duration: approximately 2 minutes 34 seconds

`GLM_API_KEY` was visible before the run. Its value was not printed, persisted, or included in this report. This was the only calibrated measurement run. No REACT, REACT_ACTION_ORIENTED, TEST-split, alternate-model, or retry run was performed.

## 2. Overall Metrics

| Metric | Result |
|---|---:|
| Task success count | 4 / 6 |
| Task success rate | 66.67% |
| Completion rate | 83.33% |
| Average steps | 5.67 |
| Average steps per success | 4.75 |
| Average tool calls | 4.33 |
| Required-tool usage rate | 83.33% |
| Invalid tool-call rate | 0.00% |
| Test failure count during Agent execution | 0 |
| Recovery attempts | 0 |
| Successful recoveries | 0 |
| Test recovery rate | 0.00% |
| Max-step termination rate | 16.67% |
| Average duration | 22,475.83 ms |
| Evaluator errors | 0 |
| Premature final attempts | 0 |
| Completion guard activations | 0 |
| Validation guard activations | 0 |
| Repeated action warnings | 0 |
| Test diagnostic failures | 0 |
| Test failure recovery successes | 0 |
| No-effect patch count | 0 |
| Rereads after test failure | 0 |
| Budget warnings | 2 |

The zero recovery rate is not evidence of failed recovery in this run: no Agent-visible Maven test failed, so no recovery opportunity occurred.

## 3. Task Results

| Task | Success | Category | Difficulty | Steps | Tool Calls | Patch Calls | Test Calls | Diagnostic Type | Final Failure Category |
|---|---|---|---|---:|---:|---:|---:|---|---|
| `bugfix_001` | Yes | BUG_FIX | EASY | 4 | 3 | 1 | 1 | — | NONE |
| `bugfix_002` | Yes | BUG_FIX | EASY | 4 | 3 | 1 | 1 | — | NONE |
| `bugfix_003` | No | BUG_FIX | MEDIUM | 10 | 8 | 4 | 0 | ASSERTION (evaluator) | MAX_STEPS |
| `logic_001` | No | LOGIC_FIX | MEDIUM | 5 | 4 | 1 | 1 | UNKNOWN assertion form (evaluator) | UNKNOWN |
| `testfix_001` | Yes | TEST_FIX | EASY | 6 | 4 | 1 | 1 | — | NONE |
| `multi_001` | Yes | MULTI_STEP_DEBUG | HARD | 5 | 4 | 1 | 1 | — | NONE |

Diagnostic distribution among post-run evaluator failures was `ASSERTION=1`, `UNKNOWN=1`, `COMPILATION=0`, and `SYNTAX=0`. Agent-visible test diagnostics were zero because all five Agent-invoked Maven runs passed.

## 4. Tool Usage

| Tool | Calls | Successful | Failed | Success rate |
|---|---:|---:|---:|---:|
| `list_files` | 2 | 2 | 0 | 100.00% |
| `search_code` | 6 | 6 | 0 | 100.00% |
| `read_file` | 4 | 4 | 0 | 100.00% |
| `apply_patch` | 9 | 5 | 4 | 55.56% |
| `run_maven_test` | 5 | 5 | 0 | 100.00% |

All four failed patch calls belonged to `bugfix_003`; they were typed `TEXT_NOT_FOUND` or `FILE_NOT_FOUND`, not invalid tool-call syntax.

## 5. Evaluator Calibration Validation

The calibrated evaluator processed all six tasks with zero infrastructure errors. Every evaluation persisted `behavioralPassed`, `contentCheckPassed`, `strictContentRequired`, and `finalSuccess`.

For `multi_001`:

```text
behavioralPassed=true
contentCheckPassed=true
strictContentRequired=false
finalSuccess=true
```

The generated implementation used local `start` and `end` values, supported reversed bounds, and included both endpoints. Hidden tests passed. This confirms correct evaluation, but this particular success cannot be attributed solely to calibration: unlike the pre-calibration trajectory, the new stochastic trajectory also satisfied the old source-shape substrings. The earlier offline re-evaluation remains the direct proof that behavior-pass/content-fail/non-strict now produces `finalSuccess=true`.

The only new `contentCheckPassed=false` results were `bugfix_003` and `logic_001`. Both also had `behavioralPassed=false`, so neither was incorrectly rescued or rejected by source-shape semantics. No new evaluator false negative was found.

## 6. Diagnostic Calibration Validation

No Java compiler failure occurred during this measurement. Therefore, the live run did not directly exercise the calibrated COMPILATION fallback or file/line extraction. There was no `UNKNOWN` compiler failure.

`bugfix_003` was correctly recognized as a JUnit expected/actual assertion failure, including test class, test method, expected `true`, and actual `false`.

`logic_001` failed because an expected `IllegalArgumentException` was not thrown. This Surefire/JUnit message does not use the parser's expected-vs-actual form, so it remained `UNKNOWN`. It is an assertion-classification coverage gap, not a compiler-classification regression. The evaluator still preserved the hidden test class and concise failure evidence.

The existing offline regression fixture remains the available evidence that `logic_001`-style localized compiler output is now classified as `COMPILATION` with file and line. No parser or evaluator changes were made during this measurement.

## 7. Encoding Validation

Mojibake observed: **NO**.

All new trajectories, evaluations, summaries, and Maven diagnostic fragments were scanned for `ä¸`, `ï¿½`, `锟斤拷`, replacement-character sequences, and the previously observed corrupted form. There were zero matches. Maven output also recorded the explicit UTF-8 Java output options.

A sensitive-data scan of the new result directory found zero matches for API-key assignments, `GLM_API_KEY`, Authorization headers, or Bearer tokens.

## 8. Failure Analysis

### `bugfix_003`

- Actual Agent mistake: the proposed null-safe equality logic was reasonable, but every patch failed because the model repeatedly supplied multiline `oldText` that did not exactly match the single-line source. It also temporarily confused a search-relative path with a workspace-relative path.
- Evaluator behavior: correct. The production file was unchanged, hidden behavior failed, and final success was false.
- Diagnostic quality: the post-run evaluator produced a precise ASSERTION diagnostic. No Agent test was run because the patch never succeeded.
- Likely bottleneck: patch precision, current-source formatting fidelity, and step-budget use—not evaluator semantics.

### `logic_001`

- Actual Agent mistake: replacing `0` with `Integer.MIN_VALUE` fixed negative-only arrays but ignored the explicit requirement to reject empty input.
- Evaluator behavior: correct. Hidden tests caught the missing exception; non-strict content failure did not override behavior.
- Diagnostic quality: useful raw JUnit evidence was retained, but the assertThrows message was classified UNKNOWN rather than ASSERTION. This does not affect success scoring.
- Likely bottleneck: task-completeness reasoning and validation coverage. Agent-visible fixture tests were absent, so its Maven run could not expose the omitted empty-input behavior.

No failure was caused by evaluator infrastructure, source-shape rejection, compiler diagnostic classification, or encoding corruption.

## 9. Pre-calibration vs Calibrated Comparison

| Metric | Pre-calibration | Calibrated |
|---|---:|---:|
| Success | 3/6 | 4/6 |
| Success rate | 50.00% | 66.67% |
| Completion rate | 83.33% | 83.33% |
| Avg steps | 6.17 | 5.67 |
| Avg tool calls | 4.50 | 4.33 |
| Max-step rate | 16.67% | 16.67% |
| Test recovery rate | 0.00% | 0.00% |
| Evaluator errors | 0 | 0 |

The increase from 3/6 to 4/6 must not be assigned entirely to evaluator calibration. `multi_001` changed trajectory and this time satisfied both behavior and the previous content form. `testfix_001` remained successful. `logic_001` changed from a compiler-failing max-step trajectory to a short, compiling but incomplete semantic patch. `bugfix_003` changed from an applied-but-null-unsafe patch to repeated patch-application failures. These are stochastic trajectory differences.

The attributable calibration result is narrower: the evaluator now records behavior and content independently, content mismatches are non-gating on ordinary behavioral tasks, no source-shape false negative occurred, and the previously preserved `multi_001` workspace was accepted by offline calibrated re-evaluation.

## 10. Remaining Bottlenecks

1. Exact replacement patching is brittle when the model reconstructs formatting instead of copying the current source verbatim.
2. The model can solve only part of a multi-requirement task and finalize after an Agent-visible Maven pass with no visible tests.
3. DEV fixtures intentionally hide evaluator tests, so task-text coverage and reasoning remain important.
4. The assertion parser does not yet classify assertThrows “expected exception but nothing was thrown” messages, although this did not affect evaluation correctness.
5. This single stochastic run does not measure variance and should remain an immutable calibrated baseline rather than be optimized through retries.

## 11. Recommendation

The calibrated result is 4/6, evaluator behavior is credible, no evaluator false negative or infrastructure error was observed, and encoding is clean. Compiler classification was not exercised live but remains covered by the calibration regression fixture. Freeze DEV tuning and, after user confirmation, proceed to Phase 6 functionality focused on Planning and Progress Tracking. Do not run TEST until Agent functionality is substantially frozen.
