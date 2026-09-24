# Phase 5A Action-Oriented Completion Report

## 1. Baseline Evidence

The first real GLM DEV ReAct experiment, `20260924-004856-react`, produced:

```text
Success: 0 / 6
Completion rate: 1.0
PREMATURE_FINAL: 5
INVALID_ARGUMENTS: 1
apply_patch calls: 0
run_maven_test calls: 0
```

Several trajectories correctly diagnosed the defect and generated plausible replacement code in the final answer, but never modified or verified the workspace. The evidence supports a narrow action-completion and recovery intervention; it does not yet support complex planning, reflection, Multi-Agent, or RL work.

### Before Prompt Behavior

The original benchmark prompt was:

```text
You are a coding assistant. Inspect, patch, and test the workspace when tools are available.
```

This described tools as optional capabilities but did not define repository state change or verification as completion. The runtime also accepted any tool-free response as final, even when no successful patch existed. A model could therefore behave as a coding advisor and still terminate normally.

## 2. Prompt Changes

The new prompt is used only by `REACT_ACTION_ORIENTED`. It states that the model is a **Coding Agent, not a coding advisor**, and establishes these rules:

- fix/change/repair/refactor/implement requests require modifying the workspace;
- diagnosis, a suggested patch, or a code snippet is not completion;
- inspect relevant code, apply the change, validate it, and use Maven tests when applicable;
- `TEST_FAILED` is a recoverable observation, not a reason to stop;
- invalid arguments should be corrected rather than abandoned;
- zero search matches do not prove code is absent;
- `TEXT_NOT_FOUND` or `MULTIPLE_MATCHES` should lead to rereading and a more precise patch;
- identical failed actions should not be repeated without changing strategy.

The prompt remains behavioral guidance. It does not hard-code tool order or make the runtime choose a patch.

Action-oriented tool definitions add matching contextual guidance:

- `apply_patch` explicitly says that it changes an existing workspace file and should be used instead of merely describing an edit;
- `run_maven_test` explains verification, targeted selectors, and recovery after `TEST_FAILED`;
- `search_code` explains empty-result fallback;
- `list_files` explains that root should be omitted or represented by `.` rather than a blank path.

Original REACT tool definitions remain unchanged.

## 3. Runtime Completion Guard

The guard is enabled only when `TaskMode.CODE_MODIFICATION` is explicitly selected. When the model attempts a final answer with no successful content-changing patch, the runtime records and returns:

```text
PREMATURE_FINAL_GUARD
```

The feedback states that an actual workspace modification is still missing and asks the model to use `apply_patch` and validate the result. The attempted final answer is retained on the feedback trajectory step. The runtime does not invent the edit or select a tool.

Each completion-guard type activates at most once. After that limit, a later final answer is accepted so the guard cannot create an infinite loop.

## 4. TaskMode / requiresModification

`TaskMode` has two explicit values:

```text
READ_ONLY
CODE_MODIFICATION
```

All pre-existing Agent constructors default to `READ_ONLY`, preserving prior behavior. `DefaultBaselineExecutor` selects `CODE_MODIFICATION` only for the new `REACT_ACTION_ORIENTED` baseline and only when `BenchmarkTask.requiresModification()` is true.

For benchmark v0.1, `requiresModification()` is deterministically derived from the existing category. BUG_FIX, LOGIC_FIX, TEST_FIX, SMALL_REFACTOR, and MULTI_STEP_DEBUG are all modification categories. The task JSON, fixture, difficulty, hidden tests, ground truth, and benchmark version were not changed.

## 5. Progress State

`AgentProgress` derives runtime facts solely from executed `ToolResult` values:

```text
hasSuccessfulPatch
hasRunTest
lastTestPassed
lastToolFailed
```

A patch counts only when `apply_patch` succeeds and its existing `changed` metadata is true. Test state changes only after `run_maven_test`. No chain-of-thought or hidden reasoning is stored.

## 6. Recovery Rules

### Invalid arguments

Typed `INVALID_ARGUMENTS` remains a normal tool observation. Prompt guidance tells the model to read the error, correct arguments, and retry or choose another tool. The runtime does not rewrite arguments.

### Empty search

The prompt and action-oriented `search_code` description state that zero matches do not prove absence. Suggested generic alternatives are `list_files`, `read_file`, another query, or another directory.

### Patch failure

For `TEXT_NOT_FOUND` and `MULTIPLE_MATCHES`, guidance instructs the model to reread the latest file and construct a more precise patch instead of repeating the same failed call.

### Test failure

If the latest test failed and the model attempts to finish, one `TEST_FAILED_GUARD` asks it to inspect the failure, continue editing, and retest.

### Repeated action

The Agent compares consecutive failed actions by exact `toolName + raw arguments`. On the second identical failure, action-oriented mode emits one `REPEATED_ACTION_WARNING`, asking for changed arguments or another strategy. A successful tool call resets the consecutive-failure tracker. The runtime still does not choose the next action.

## 7. Trajectory Changes

`AgentActionType` now includes:

```text
RUNTIME_FEEDBACK
```

Runtime feedback is not disguised as a tool call. Its `AgentStep` stores:

- the attempted final text in `finalAnswer` when applicable;
- the stable feedback prefix and message in `errorMessage`;
- action type `RUNTIME_FEEDBACK`;
- normal ordered step index and timestamp.

Feedback is also appended to model history as a system message before the next decision. Stable prefixes are:

```text
PREMATURE_FINAL_GUARD
VALIDATION_GUARD
TEST_FAILED_GUARD
REPEATED_ACTION_WARNING
```

## 8. Metrics Changes

Phase 4 metrics remain intact. The following fields were added:

```text
prematureFinalAttempts
completionGuardActivations
validationGuardActivations
repeatedActionWarnings
```

`prematureFinalAttempts` and `completionGuardActivations` count guarded final attempts across no-patch, unvalidated-patch, and failed-test states. `validationGuardActivations` exposes the untested-patch subset. Test-failed events remain observable through trajectory feedback and the existing test-failure/recovery metrics.

## 9. Tests

New deterministic `FakeLLMClient` coverage verifies:

1. read → premature final → feedback → patch → test → final;
2. patch → unvalidated final → validation reminder → test → final;
3. patch → failed test → attempted final → recovery feedback → repatch → passing test → final;
4. read-only read → final without guard;
5. invalid `list_files` arguments → corrected arguments → read → patch → test → final;
6. two identical failed patches → one repeated-action warning → changed strategy → successful patch/test/final;
7. action-oriented tool descriptions and unchanged original descriptions;
8. runtime feedback metrics.

Final ordinary offline result:

```text
Tests run: 86, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

No real GLM request or real benchmark run occurred.

## 10. Backward Compatibility

- `REACT` remains available with its original prompt, original tool-definition descriptions, and no completion/repeated-action guard.
- Existing Agent constructors use `TaskMode.READ_ONLY`.
- Read-only tasks can read and return a final answer without a patch.
- Main CLI behavior is unchanged.
- No new tool, shell access, delete capability, unrestricted write, model, provider setting, fixture, hidden test, or evaluator rule was introduced.
- Phase 1 safety, Phase 2 trajectory, Phase 3 coding loop, and Phase 4 benchmark tests all pass.
- `benchmark/results/20260924-004856-react/` and `REAL_DEV_REACT_BASELINE_REPORT.md` were not modified.

## 11. Next Experiment

The next experiment should explicitly run the same six DEV tasks with the same `glm-4-flash` provider configuration using:

```text
REACT_ACTION_ORIENTED
```

Compare it with the preserved `REACT` experiment on success, patch/test use, guarded final attempts, recovery, steps, latency, and failure categories. Do not run TEST until the DEV comparison is reviewed. This report does not execute that experiment or implement Phase 5B.
