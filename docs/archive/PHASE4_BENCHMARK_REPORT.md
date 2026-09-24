# Phase 4 Benchmark Report

## 1. Summary

Phase 4 adds a fixed, versioned, deterministic Java Coding Agent benchmark and the infrastructure needed to reset fixtures, run an Agent baseline, persist its structured trajectory, evaluate the resulting workspace, calculate metrics, and classify failures. The benchmark is `v0.1` and contains 20 isolated Maven tasks. Ordinary `mvn test` remains offline and never calls GLM or runs the real benchmark command.

## 2. Benchmark Architecture

```text
Versioned Tasks
      ↓
Pristine Common + Task Fixture Reset
      ↓
Baseline Agent Runner
      ↓
Structured Trajectory
      ↓
Post-run Hidden-Test / Content Evaluator
      ↓
Metrics + Failure Classification
      ↓
JSON and Markdown Results
```

`BenchmarkRunner` coordinates these components but contains no model reasoning. `BenchmarkAgentExecutor` owns the baseline policy, while `TaskEvaluator` owns ground-truth evaluation.

## 3. Task Schema

`BenchmarkTask` contains:

```text
id
category
difficulty
description
fixture
expectedFiles
evaluationType
maxSteps
tags
targetTest
split
requiredTools
```

Task definitions live in `benchmark/tasks/v0.1/tasks.json`. They contain no ground-truth patch or hidden expected values. Evaluator-only textual checks live separately in `evaluation-checks.json`, and hidden JUnit tests live outside the fixture tree.

## 4. Benchmark Composition

Total tasks: **20**

| Category | Tasks |
| --- | ---: |
| BUG_FIX | 5 |
| LOGIC_FIX | 4 |
| TEST_FIX | 4 |
| SMALL_REFACTOR | 3 |
| MULTI_STEP_DEBUG | 4 |

| Difficulty | Tasks |
| --- | ---: |
| EASY | 8 |
| MEDIUM | 8 |
| HARD | 4 |

| Split | Tasks |
| --- | ---: |
| DEV | 6 |
| TEST | 14 |

The tasks cover arithmetic, boundaries, strings, null handling, loops, arrays, collections, parsing, exceptions, JUnit expectation repair, refactoring, validation, and multi-bug debugging. The TEST split is fixed and must not drive task-specific prompt rules.

## 5. Evaluators

`DeterministicTaskEvaluator` supports:

- `MAVEN_TEST`: inject evaluator-only hidden tests after the Agent run, then execute the controlled Maven `test` goal.
- `FILE_CONTENT`: require readable expected files, actual modification, and deterministic required/forbidden content.
- `COMBINED`: require hidden Maven tests, valid files, a real change, and content checks.

Hidden tests are copied only after Agent execution, so they are absent from the Agent workspace during reasoning. Success is computed from workspace state and tests; the Agent's final claim is never accepted as evidence. Maven start, timeout, or evaluator runtime failures become `EVALUATOR_ERROR` and are excluded from the Agent success-rate denominator.

## 6. Metrics

The framework calculates:

- task success rate;
- completion rate;
- average steps;
- average steps per successful task;
- average tool calls;
- invalid tool-call rate;
- test-failure count;
- recovery-attempt count;
- test-failure recovery rate;
- max-step termination rate;
- average duration;
- required-tool usage rate;
- per-tool calls, successes, failures, and success rate for `read_file`, `list_files`, `search_code`, `apply_patch`, and `run_maven_test`;
- category and difficulty breakdowns;
- failure-category counts;
- evaluator-error count and evaluable-task count.

All action statistics come from Phase 2/3 structured trajectories, not console parsing.

## 7. Failure Taxonomy

The deterministic categories are:

```text
NONE
NO_REQUIRED_CHANGE
PATCH_FAILED
TEST_FAILED_UNRECOVERED
TOOL_NOT_FOUND
INVALID_ARGUMENTS
WORKSPACE_VIOLATION
PREMATURE_FINAL
MAX_STEPS
LLM_ERROR
LOOP_OR_REPEATED_ACTION
EVALUATOR_ERROR
UNKNOWN
```

Categories that cannot be reliably inferred, such as subjective wrong reasoning or ignored observations, are not presented as precise automatic labels. Such cases fall back to `UNKNOWN` for later human sample review. `failure_analysis.md` reports count, rate, and at most three representative task IDs per observed category.

## 8. Baselines

Three policies use the same task set and evaluator:

1. `SINGLE_SHOT_NO_TOOL`: passes no definitions, exposes no registry tools, and strips any returned tool calls.
2. `SINGLE_TOOL_ROUND`: permits at most the first tool call from the first model decision, then forces a tool-free final decision.
3. `REACT`: uses the complete bounded multi-step coding loop and the five confined tools.

`maxSteps` is task-defined and supplied through the existing Agent constructor; the default production bound remains unchanged. Provider/model randomness metadata is recorded, but provider parameters are not changed by Phase 4.

## 9. FakeLLM Validation

The ordinary test suite executes a deterministic three-task smoke benchmark using FakeLLM clients. It verifies the complete path:

```text
task load → pristine reset → fake Agent patch → trajectory → hidden evaluator
→ metrics → trajectory.json/evaluation.json/summary files
```

Smoke result: **3/3 deterministic tasks succeeded**. Tests also cover patch-failure classification, failed-test recovery metrics, max-step classification, evaluator-error separation, pristine reset, hidden-test isolation, and result persistence. Persisted trajectories use the existing redaction/truncation writer; `evaluation.json` deliberately excludes the unredacted in-memory run object.

## 10. Real Provider Results

Not executed in Phase 4 implementation.

An explicit, potentially billable DEV command is available:

```text
mvn exec:java -Dexec.mainClass=com.agent.benchmark.BenchmarkMain -Dexec.args="--baseline react --split dev --limit 3"
```

The command requires the existing `GLM_API_KEY`. It is never invoked by `mvn test`.

## 11. Remaining Limitations

- The benchmark is intentionally small and repository-local.
- Fixtures are minimal Maven projects rather than large open-source repositories.
- Editing remains exact existing-file replacement; there is no arbitrary file creation.
- There is no arbitrary shell, delete tool, unrestricted write tool, network-search tool, or Git-write tool.
- There is no planning baseline or no-recovery baseline.
- Real stochastic provider runs have not been repeated or cost-normalized.
- Human success/failure trajectory sampling is a future experiment, separate from automatic metrics.
- No reward API, trainer, GPU workflow, Multi-Agent system, or Agentic RL is implemented.

## 12. Next Recommended Phase

After the user explicitly runs DEV and then fixed TEST experiments, use their real trajectories and failure distribution to choose one evidence-driven next step: Agent improvement experiments (planning, retry, or context handling) or a later environment API that maps `task + trajectory + taskSuccess` to rollout data. This report does not implement Phase 5.

## Verification

Final ordinary offline suite:

```text
Tests run: 78, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

The four skips remain intentional environment-gated/live or unavailable Windows symlink cases. No real GLM request and no real full benchmark run occurred.
