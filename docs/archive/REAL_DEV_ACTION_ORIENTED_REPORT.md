# Real GLM DEV Action-Oriented Report

## 1. Experiment Setup

| Setting | Value |
| --- | --- |
| Experiment ID | `20260924-011046-react_action_oriented` |
| Benchmark version | `v0.1` |
| Split | `DEV` |
| Model | `glm-4-flash` |
| Provider / temperature | GLM / provider default |
| Strategy | `REACT_ACTION_ORIENTED` |
| Expected / executed / evaluated tasks | 6 / 6 / 6 |
| Evaluator errors | 0 |
| Per-task max steps | Existing task-specific values, 6–10 |

The existing Agent, action-oriented prompt, guards, tool descriptions, fixtures, hidden tests, evaluator, task definitions, model, provider configuration, and step limits were used without modification. TEST and all other baselines were not run. The preserved comparison experiment is `20260924-004856-react`.

## 2. Overall Metrics

| Metric | Result |
| --- | ---: |
| Task success | 3 / 6 |
| Task success rate | 0.500 |
| Completion rate | 0.667 |
| Average steps | 7.333 |
| Average steps per success | 6.000 |
| Average tool calls | 5.833 |
| Invalid tool-call rate | 0.000 |
| Test failure count | 3 |
| Recovery attempts | 3 |
| Test recovery rate | 0.000 |
| Max-step termination rate | 0.333 |
| Average duration | 24,214.167 ms |
| Required-tool usage rate | 0.833 |
| Premature final attempts | 3 |
| Completion guard activations | 3 |
| Validation guard activations | 0 |
| Repeated-action warnings | 0 |

## 3. Task-level Results

| Task | Category | Difficulty | Success | Steps | Tool Calls | Patch Calls | Test Calls | Guard Activations | Failure Category |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| bugfix_001 | BUG_FIX | EASY | Yes | 5 | 4 | 1 | 1 | 0 | NONE |
| bugfix_002 | BUG_FIX | EASY | Yes | 5 | 4 | 1 | 1 | 0 | NONE |
| bugfix_003 | BUG_FIX | MEDIUM | No | 9 | 8 | 3 | 2 | 0 | MAX_STEPS |
| logic_001 | LOGIC_FIX | MEDIUM | Yes | 8 | 6 | 2 | 1 | 1 | NONE |
| testfix_001 | TEST_FIX | EASY | No | 7 | 5 | 0 | 0 | 1 | MAX_STEPS |
| multi_001 | MULTI_STEP_DEBUG | HARD | No | 10 | 8 | 3 | 1 | 1 | TEST_FAILED_UNRECOVERED |

## 4. Tool Usage

| Tool | Calls | Success | Failure |
| --- | ---: | ---: | ---: |
| list_files | 0 | 0 | 0 |
| read_file | 7 | 7 | 0 |
| search_code | 12 | 12 | 0 |
| apply_patch | 10 | 10 | 0 |
| run_maven_test | 6 | 3 | 3 |

Compared with the old REACT run, the Agent moved from zero patch/test calls to frequent environment action. All ten patches were accepted by the exact-text patch tool, although a successful patch operation did not necessarily produce correct or compiling Java. Three Maven calls returned `TEST_FAILED`.

## 5. Completion Guard Behavior

Three final attempts were intercepted:

- `logic_001`: `PREMATURE_FINAL_GUARD` changed behavior. The model moved from a descriptive final answer to two patches, a Maven test, and a successful final answer.
- `testfix_001`: `PREMATURE_FINAL_GUARD` did not produce an edit. The model searched once more and exhausted its task-specific step budget.
- `multi_001`: `TEST_FAILED_GUARD` did not produce further recovery. The model immediately returned another final answer acknowledging the compilation failure.

No validation-only guard was needed because every final attempt after a successful patch either followed a test or followed a failed-test state. No identical failed tool call occurred, so repeated-action warnings remained zero.

## 6. Failure Analysis

The unchanged automatic classifier produced:

| Failure Category | Count | Rate | Task IDs |
| --- | ---: | ---: | --- |
| NONE | 3 | 0.500 | bugfix_001, bugfix_002, logic_001 |
| MAX_STEPS | 2 | 0.333 | bugfix_003, testfix_001 |
| TEST_FAILED_UNRECOVERED | 1 | 0.167 | multi_001 |

Evaluator infrastructure completed for all tasks. One evaluator-design limitation was observed in `multi_001`: its deterministic content check requires `Math.min`, `Math.max`, and a particular loop form, although swapping reversed bounds can also be behaviorally correct. This did not change the outcome of this run because the Agent-produced source did not compile and the hidden Maven test also failed. The result was not changed or rerun.

## 7. Manual Trajectory Review

The following likely root causes are manual analysis, not automatic failure labels.

### bugfix_003

- What the Agent correctly did: located `TokenMatcher`, replaced reference equality with `Objects.equals`, and ran Maven twice.
- First problematic step: the first patch used `Objects.equals` without importing `java.util.Objects`, producing a compilation failure.
- Runtime feedback received: ordinary typed `TEST_FAILED` observations; no completion guard because the model had not attempted Final.
- Reaction: searched the changed code, added redundant null logic without fixing the import, retested, then replaced the expression with `left.equals(...)`, which is unsafe for null input.
- Why evaluation failed: task reached its eight-decision limit; final source failed deterministic content and hidden tests.
- Likely root cause: weak reading of compiler diagnostics and increasingly speculative patching under the step budget.

### testfix_001

- What the Agent correctly did: found and read both `CalculatorTest` and the correct production `Calculator` implementation.
- First problematic step: concluded that `2 + 3` should equal 4 and attempted to finish without modifying the incorrect expectation.
- Runtime feedback received: `PREMATURE_FINAL_GUARD` explicitly required an actual patch and validation.
- Reaction: searched for `CalculatorTest` again but did not patch or test.
- Why evaluation failed: the expected test file remained unchanged and the six-decision budget ended.
- Likely root cause: basic arithmetic/reasoning error combined with limited response to the guard.

### multi_001

- What the Agent correctly did: recovered from an empty search, found and read `RangeSum`, identified reversed-bound and inclusive-endpoint requirements, applied changes, and ran Maven.
- First problematic step: the method-signature patch inserted a new block without removing the existing opening block, causing malformed Java.
- Runtime feedback received: typed `TEST_FAILED`, followed later by `TEST_FAILED_GUARD` when it attempted Final.
- Reaction: reread the malformed file, applied a no-op patch, then attempted Final; after the guard it immediately finalized again.
- Why evaluation failed: compilation remained broken, no passing retest occurred, hidden tests failed, and the content check also did not accept the alternative swap-based implementation.
- Likely root cause: imprecise structural patching and ineffective compiler-error recovery; the guard influenced one turn but did not force meaningful repair.

## 8. REACT vs REACT_ACTION_ORIENTED

| Metric | REACT | REACT_ACTION_ORIENTED |
| --- | ---: | ---: |
| Success | 0/6 | 3/6 |
| Success rate | 0.000 | 0.500 |
| Completion rate | 1.000 | 0.667 |
| Average steps | 2.500 | 7.333 |
| Average tool calls | 1.500 | 5.833 |
| Invalid tool-call rate | 0.111 | 0.000 |
| Test recovery rate | 0.000 | 0.000 |
| Max-step rate | 0.000 | 0.333 |
| apply_patch calls | 0 | 10 |
| run_maven_test calls | 0 | 6 |
| Automatic PREMATURE_FINAL failures | 5 | 0 |
| Premature final attempts metric | Not available in old schema | 3 |

The lower completion rate is explained by two MAX_STEPS terminations. Unlike task success, conversation completion alone is not a quality measure.

## 9. Observed Improvement

Action Completion was a real bottleneck in this DEV sample:

- success increased from 0/6 to 3/6;
- actual patch calls increased from 0 to 10;
- Maven verification calls increased from 0 to 6;
- required-tool usage increased from 0.000 to 0.833;
- invalid-call rate fell from 0.111 to 0.000;
- one guard directly converted a descriptive final into a successful patch/test path.

The result is meaningful engineering evidence for this fixed six-task DEV set, but it is not a statistically stable general model estimate.

## 10. Remaining Bottlenecks

1. **Test-aware recovery:** three Maven failures occurred, three recovery actions were attempted, but no failed-test task ultimately recovered.
2. **Code-edit precision and diagnostic use:** failures included a missing import, null-unsafe replacement, malformed brace structure, and a no-op patch.
3. **Step-budget efficiency:** two tasks reached MAX_STEPS after repeated search/patch activity, increasing average steps and latency without completion.

There is no evidence in this run for invalid arguments or repeated identical failed actions under the new strategy.

## 11. Phase 5B Recommendation

Do not implement these automatically. At most three evidence-based candidates are:

1. **Test-diagnostic recovery:** make compiler/test output easier to focus on and require a meaningful changed repair before another test/final attempt. Evidence: 3 test failures and 0 recovered tasks.
2. **Patch-context discipline:** encourage rereading the complete current file and constructing structurally valid exact replacements after compilation failure. Evidence: malformed blocks and speculative follow-up patches.
3. **Lightweight progress/step-budget control:** track whether the latest action reduced the current failure and avoid low-value search/patch churn. Evidence: two MAX_STEPS terminations and a 7.333-step average.

## Integrity

- All six DEV tasks produced readable `trajectory.json` and `evaluation.json` files.
- Experiment-level `summary.json`, `summary.md`, and `failure_analysis.md` are readable.
- Evaluator errors: 0.
- Exact API-key, `Authorization`, `Bearer`, and `GLM_API_KEY` scan hits: 0.
- Old experiment `20260924-004856-react` remains present with its original 0/6 summary.
- TEST and other baselines were not run.
- Results: `benchmark/results/20260924-011046-react_action_oriented/`.
