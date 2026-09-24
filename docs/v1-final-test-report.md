# V1 Final Held-out TEST Report

## 1. V1 Freeze Configuration

| Field | Frozen value |
|---|---|
| Strategy | `REACT_DIAGNOSTIC_RECOVERY` |
| Model / Provider | `glm-4-flash` / GLM |
| Temperature | provider default |
| Benchmark | v0.1 |
| Split | TEST |
| TEST task count | 14 |
| Task budget | Original task-specific `maxSteps` |
| Experiment ID | `20260924-062518-react_diagnostic_recovery` |

This is the first and only held-out TEST run for V1. Before running, `GLM_API_KEY` was confirmed visible to the process without revealing its value. The selected executor was checked to use diagnostic recovery with the action-oriented coding registry; `planning=false`, and no Precise Edit registry or prompt was selected. No Agent behavior, tools, prompts, evaluator, fixtures, hidden tests, task definitions, model, provider setting, or step budget was modified.

## 2. TEST Setup

Expected, executed, and evaluated tasks were all 14:

`bugfix_004`, `bugfix_005`, `logic_002`, `logic_003`, `logic_004`, `testfix_002`, `testfix_003`, `testfix_004`, `refactor_001`, `refactor_002`, `refactor_003`, `multi_002`, `multi_003`, and `multi_004`.

The run created an isolated result directory with all required artifacts: 14 per-task `trajectory.json` and `evaluation.json` files plus `summary.json`, `summary.md`, and `failure_analysis.md`. No DEV split and no alternate strategy was run.

## 3. Overall Results

| Metric | Result |
|---|---:|
| Total TEST Tasks | 14 |
| Successful Tasks | 5 |
| Task Success Rate | 35.71% |
| Completion Rate | 50.00% |
| Average Steps | 8.50 |
| Average Steps per Success | 6.00 |
| Average Tool Calls | 6.21 |
| Required-tool Usage Rate | 85.71% |
| Invalid Tool-call Rate | 5.75% |
| Test Failure Count | 7 |
| Recovery Attempts | 7 |
| Successful Recoveries | 0 |
| Test Recovery Rate | 0.00% |
| Max-step Termination Rate | 50.00% |
| Average Duration | 27,523.21 ms |
| Evaluator Errors | 0 |

The benchmark’s automatic failure classifier reported five `NONE`, seven `MAX_STEPS`, and two `UNKNOWN` outcomes. `UNKNOWN` here denotes incomplete automatic category assignment, not evaluator failure; the underlying trajectories and evaluations were manually reviewed below.

## 4. Task-Level Results

| Task | Category | Difficulty | Success | Completion | Steps | Tool Calls | Test Calls | Failure Category |
|---|---|---|---|---|---:|---:|---:|---|
| bugfix_004 | BUG_FIX | EASY | Yes | Yes | 6 | 4 | 1 | NONE |
| bugfix_005 | BUG_FIX | MEDIUM | No | No | 11 | 8 | 1 | MAX_STEPS |
| logic_002 | LOGIC_FIX | MEDIUM | No | No | 11 | 8 | 2 | MAX_STEPS |
| logic_003 | LOGIC_FIX | EASY | No | No | 9 | 6 | 1 | MAX_STEPS |
| logic_004 | LOGIC_FIX | HARD | Yes | Yes | 6 | 5 | 1 | NONE |
| testfix_002 | TEST_FIX | EASY | Yes | Yes | 6 | 4 | 1 | NONE |
| testfix_003 | TEST_FIX | EASY | Yes | Yes | 6 | 4 | 1 | NONE |
| testfix_004 | TEST_FIX | EASY | No | No | 9 | 6 | 2 | MAX_STEPS |
| refactor_001 | SMALL_REFACTOR | MEDIUM | No | No | 10 | 8 | 0 | MAX_STEPS |
| refactor_002 | SMALL_REFACTOR | MEDIUM | No | No | 11 | 8 | 2 | MAX_STEPS |
| refactor_003 | SMALL_REFACTOR | HARD | No | No | 13 | 9 | 0 | MAX_STEPS |
| multi_002 | MULTI_STEP_DEBUG | HARD | No | Yes | 6 | 5 | 1 | UNKNOWN |
| multi_003 | MULTI_STEP_DEBUG | MEDIUM | Yes | Yes | 6 | 5 | 1 | NONE |
| multi_004 | MULTI_STEP_DEBUG | MEDIUM | No | Yes | 9 | 7 | 1 | UNKNOWN |

## 5. Category Breakdown

| Category | Total | Success | Success Rate | Avg Steps |
|---|---:|---:|---:|---:|
| BUG_FIX | 2 | 1 | 50.00% | 8.50 |
| LOGIC_FIX | 3 | 1 | 33.33% | 8.67 |
| TEST_FIX | 3 | 2 | 66.67% | 7.00 |
| SMALL_REFACTOR | 3 | 0 | 0.00% | 11.33 |
| MULTI_STEP_DEBUG | 3 | 1 | 33.33% | 7.00 |

## 6. Difficulty Breakdown

| Difficulty | Total | Success | Success Rate | Avg Steps | Avg Tool Calls |
|---|---:|---:|---:|---:|---:|
| EASY | 5 | 3 | 60.00% | 7.20 | 4.80 |
| MEDIUM | 6 | 1 | 16.67% | 9.67 | 7.33 |
| HARD | 3 | 1 | 33.33% | 8.33 | 6.33 |

## 7. Tool Usage

| Tool | Calls | Successful | Failed | Success Rate |
|---|---:|---:|---:|---:|
| `apply_patch` | 34 | 24 | 10 | 70.59% |
| `read_file` | 16 | 16 | 0 | 100.00% |
| `search_code` | 20 | 20 | 0 | 100.00% |
| `list_files` | 2 | 2 | 0 | 100.00% |
| `run_maven_test` | 15 | 8 | 7 | 53.33% |
| `replace_lines` | 0 | 0 | 0 | N/A in frozen V1 strategy |

The ten failed exact-patch calls were five `INVALID_ARGUMENTS` calls in `refactor_003`, three `TEXT_NOT_FOUND` calls in `refactor_001`, one `MULTIPLE_MATCHES` call in `logic_003`, and one `NO_EFFECT_CHANGE` call in `bugfix_005`. The invalid tool-call rate reflects the five empty-`oldText` requests in `refactor_003` over all tool calls.

## 8. Failure Analysis

### bugfix_005

The Agent found and edited the intended parser, and the required colon split text was present. It first made a no-effect patch, then produced invalid Java: `input.split(\":\": 2)`. Maven returned a compilation diagnostic (`KeyValueParser.java:3`, missing `)`); the Agent reread the file but exhausted the budget. Likely bottleneck: patch precision and compilation recovery.

### logic_002

The Agent correctly recognized a collection-order task and attempted multiple edits, but inserted imports inside the class and constructed an invalid `TreeSet`-based implementation. Two in-loop Maven calls exposed compilation failures (`UniqueNames.java:5`, illegal type start), yet recovery did not restore a valid ordered de-duplication implementation. Likely bottleneck: code-generation reasoning plus test recovery.

### logic_003

The Agent identified the boundary expression and attempted a patch, but produced unmatched extra braces. After Maven exposed a compilation error (`DiscountPolicy.java:2`, illegal expression start), it attempted a too-broad patch where `oldText` was `{`, causing `MULTIPLE_MATCHES`, then exhausted the budget. Likely bottleneck: patch precision and max-step exhaustion.

### testfix_004

The Agent correctly changed `assertFalse` to `assertTrue`. The first Maven diagnostic correctly identified the missing static import; the Agent added `assertTrue`, and the second Maven run passed. However, it retained the now-unused `assertFalse` import, which violates the strict deterministic content rule, and it hit the six-step budget before giving a final answer. The evaluator is correct: this is incomplete strict requirement coverage plus step exhaustion, not an evaluator issue.

### refactor_001

The Agent correctly inferred that `applyTax` was needed, but replaced the class body with duplicate private methods and removed public behavior. Subsequent patches used stale original text and failed three times with `TEXT_NOT_FOUND`; no in-loop test was reached. Hidden evaluation reported duplicate `applyTax(double,double)` compilation failure. Likely bottleneck: unsafe broad edit, stale patch context, and budget exhaustion.

### refactor_002

The Agent attempted the requested `normalize` extraction but created a boolean helper incompatible with the required `String` use, duplicated the helper, and duplicated `key`. Two Maven tests correctly exposed duplicate `normalize(String)` compilation errors; rereading did not produce a successful recovery. Likely bottleneck: refactor reasoning and test recovery.

### refactor_003

The Agent correctly located the duplicated validation and proposed `requireItem`, but repeatedly attempted insertion with an empty `oldText`. `apply_patch` safely rejected all five requests as `INVALID_ARGUMENTS`; no source change occurred and no Maven validation was reached. Hidden behavioral tests passed on the untouched implementation, but the strict requirement (`requireItem`) was absent. Likely bottleneck: tool misuse and max-step exhaustion.

### multi_002

The Agent read the parser and performed a valid patch/run sequence, then gave a final answer. The final implementation still looped to `parts.length - 1`, omitting the last column. Hidden evaluation produced a clear assertion diagnostic: expected `[Ada, 42, Paris]`, received `[Ada, 42]`. Likely bottleneck: incomplete requirement coverage and premature final answer after local Maven had no hidden test to catch the case.

### multi_004

The Agent correctly changed `age > 18` to `age >= 18` and added non-empty local/domain checks. It then ran Maven successfully, but the local fixture had no tests. Hidden evaluation found a runtime error because Java `split("@")` drops the trailing empty segment, so `a@` accesses index 1. The diagnostic classifier labeled this `UNKNOWN`; the manual cause is a model reasoning error around Java `split` semantics and hidden-edge-case coverage, not an evaluator issue.

### Consolidated causes

The three dominant failure causes were: (1) malformed or overly broad patch construction, especially in refactors and compact one-line fixtures; (2) max-step exhaustion after failed repairs (7/14 tasks); and (3) incomplete requirement/edge-case coverage when local Maven did not include hidden tests. No premature-final guard failure occurred beyond the one completed-but-unsuccessful `multi_002` answer, and no evaluator error was found.

## 9. Representative Successes

### bugfix_004

Trajectory: `search_code -> read_file -> apply_patch -> run_maven_test -> final answer`. The Agent changed the loop boundary to avoid reading beyond the array, local Maven passed, and hidden evaluation passed. This is a clean single-bug repair trajectory.

### logic_004

Trajectory: `search_code -> read_file -> search_code -> apply_patch -> run_maven_test -> final answer`. The Agent handled both normalization requirements (spaces and case) and the null case; hidden behavior passed. This demonstrates successful read-before-edit and validation for a harder logic task.

### multi_003

Trajectory: `search_code -> apply_patch -> search_code -> apply_patch -> run_maven_test -> final answer`. The Agent repaired both negative-quantity validation and total accumulation. Hidden evaluation passed, showing that the frozen runtime can complete a multi-requirement task when the patch plan is coherent.

## 10. Representative Failures

### refactor_003: safe tool rejection but no recovery

Five empty-`oldText` patch attempts were correctly rejected as `INVALID_ARGUMENTS`. This demonstrates tool safety, but also shows that the Agent did not change editing approach after explicit feedback and exhausted its budget without implementing the required helper.

### multi_004: local validation gap

The Agent made both visible requirement edits and local Maven passed because no fixture tests were present. Hidden evaluation then exposed the trailing-delimiter behavior of `String.split`. This is a generalization/edge-case reasoning gap, not an evaluator regression.

### refactor_001: broad edit corrupted public API

The Agent added the target helper but removed both public methods and duplicated the helper. Later stale patches failed with `TEXT_NOT_FOUND`, and the final isolated workspace did not compile. This highlights that a successful patch operation is not equivalent to a valid refactor.

## 11. DEV vs TEST

| Metric | DEV calibrated | TEST |
|---|---:|---:|
| Tasks | 6 | 14 |
| Success Rate | 66.67% (4/6) | 35.71% (5/14) |
| Avg Steps | 5.67 | 8.50 |
| Avg Tool Calls | 4.33 | 6.21 |
| Max-step Rate | 16.67% | 50.00% |
| Evaluator Errors | 0 | 0 |

The TEST result is descriptively lower than the calibrated DEV result: success rate fell by 30.96 percentage points, while average steps, average tool use, and max-step terminations increased. This is evidence of a held-out generalization drop for this one frozen run, not a claim of statistical significance.

## 12. Limitations

- The benchmark is small: six DEV and fourteen held-out TEST tasks.
- Evaluation uses one model (`glm-4-flash`) and one stochastic TEST run.
- Scope is Java/Maven maintenance tasks, not arbitrary software-engineering work.
- V1 permits no arbitrary shell access; it uses controlled Maven testing only.
- V1 edits existing files and does not provide general project-generation workflows.
- V1 has no Multi-Agent coordination, persistent Memory, or Agentic RL.
- Local fixture tests can be absent, so hidden evaluation remains essential for behavioral edge cases.

## 13. V1 Conclusion

V1 includes a Java Coding Agent runtime, workspace safety, function calling, safe code editing, controlled Maven testing, failure recovery, structured trajectories, a benchmark, hidden deterministic evaluation, failure analysis, and multiple Agent-strategy experiments.

The final default strategy remains `REACT_DIAGNOSTIC_RECOVERY`, selected before TEST because it was the calibrated DEV best. The held-out result is 5/14 (35.71%), with zero evaluator errors and clear trajectory-level explanations for all nine failures. The result is credible as a frozen V1 measurement, even though it identifies meaningful limitations in refactoring, repair recovery, and edge-case reasoning.

The negative DEV experiments remain part of the project record: `REACT_PLANNING` scored 3/6 and `REACT_PRECISE_EDIT` scored 1/6, compared with Diagnostic Recovery at 4/6. They are retained to show that added mechanisms did not automatically improve DEV performance.

No TEST tuning, rerun, or V2 work was performed. A V1 feature freeze is appropriate: preserve this exact held-out measurement, document the limitations, and treat any future changes as a separately versioned V2 evaluation rather than retroactively changing V1.
