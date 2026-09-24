# Phase 5B Diagnostic Recovery Report

## 1. Evidence

The real `REACT_ACTION_ORIENTED` DEV experiment established that action completion improved success from 0/6 to 3/6, but left a narrower recovery problem:

```text
Success: 3 / 6
Maven TEST_FAILED observations: 3
Recovered failed-test tasks: 0
MAX_STEPS: 2
```

`bugfix_003` made speculative patches after compiler failures, `multi_001` produced malformed structure and then a no-op patch, and `testfix_001` exhausted its budget without applying the evident test correction. Phase 5B therefore improves diagnostic visibility, current-source awareness, patch precision signals, and budget awareness. It does not add automatic repair, planning, reflection, Multi-Agent, or RL behavior.

### Before: Maven Output

`RunMavenTestTool` previously returned the complete captured Maven stream as `ToolResult.output`. The LLM failure observation serialized the raw output alongside an error code and metadata. Compiler or assertion information was present, but usually followed Maven project scanning, lifecycle, resource, compiler, and Surefire boilerplate. The model had to find the relevant line inside a potentially 100 KiB capture.

Raw output remains necessary for humans and benchmark audit, so Phase 5B retains it while adding a concise deterministic summary.

## 2. Diagnostic Summary Design

`MavenDiagnosticParser` performs deliberately small, deterministic pattern extraction. It does not attempt to implement a Java compiler or Surefire parser.

Diagnostic types:

```text
COMPILATION
SYNTAX
ASSERTION
UNKNOWN
```

Failure metadata can contain:

```text
diagnosticType
diagnosticSummary
diagnosticFile
diagnosticLine
diagnosticSymbol
diagnosticTestClass
diagnosticTestMethod
diagnosticExpected
diagnosticActual
diagnosticMessage
```

Compilation parsing recognizes Maven compiler source locations, `cannot find symbol`, and the following `symbol:` line. Syntax classification recognizes concise compiler phrases including `';' expected`, `')' expected`, `'}' expected`, `illegal start of expression`, `reached end of file while parsing`, and `unclosed string literal`. Assertion parsing extracts the Surefire/JUnit test class and method plus `expected: <...> but was: <...>`. Unknown failures retain up to three distinct Maven error lines or a generic failure summary.

`ToolResult.output` still contains the original captured Maven text. The Agent observation now starts with:

```text
TEST FAILED

Key diagnostics:
<diagnosticSummary>

Relevant output:
<raw captured output>
```

Thus the model sees the diagnostic before the boilerplate without losing raw evidence.

## 3. Recovery Prompt

`REACT_DIAGNOSTIC_RECOVERY` includes all Phase 5A instructions plus these focused rules:

1. Identify the exact file, line, symbol, syntax error, or assertion from the diagnostic.
2. Re-read the current modified source before another repair.
3. Prefer the smallest targeted patch grounded in the actual current file.
4. Rerun the narrowest relevant test class or method when possible.
5. Treat missing symbols as likely import, spelling, or availability problems before rewriting logic.
6. Treat syntax failures as a reason to inspect braces, parentheses, duplicated blocks, and malformed replacements.
7. For assertion failures, use task text, production source, and test source to decide whether implementation or expectation is wrong; task category alone is not authoritative.
8. Avoid speculative null logic, unrelated refactors, or structural edits without supporting evidence.

The LLM remains solely responsible for choosing files, patches, and tests.

## 4. Patch Freshness

`AgentProgress` now additionally derives:

```text
lastModifiedFile
lastPatchStep
lastTestFailureStep
hasRereadModifiedFileAfterFailure
latestDiagnosticType
latestDiagnosticSummary
noEffectPatchCount
rereadAfterFailureCount
contextActionsAfterFailure
```

After a successful content-changing patch followed by `TEST_FAILED`, diagnostic mode emits one `PATCH_FRESHNESS_WARNING` before the next model decision if the modified file has not yet been reread. It names the current modified file and asks the model to inspect its latest version before constructing another patch. The runtime does not call `read_file`, generate code, or prevent a model-selected action.

A matching successful `read_file` after the failure marks the current file as reread. This state and the ordered feedback/tool steps remain visible in the trajectory.

## 5. No-op Detection

`ApplyPatchTool` already compared the fully constructed updated content with the original, but previously returned `success=true, changed=false`. It now returns:

```text
success=false
errorCode=NO_EFFECT_CHANGE
metadata.changed=false
```

The file is not rewritten. `AgentProgress.hasSuccessfulPatch` continues to require both successful execution and `changed=true`, so a no-op cannot satisfy the completion contract or masquerade as recovery.

## 6. Budget Awareness

Two lightweight, bounded feedback mechanisms are enabled only for `REACT_DIAGNOSTIC_RECOVERY` modification tasks:

- `SEARCH_CHURN_WARNING`: once per run after three read/search context actions following the same unresolved test failure without a repair attempt.
- `STEP_BUDGET_WARNING`: once per run immediately before the decision made with two decision slots remaining.

The budget warning asks the model to prioritize the current concrete failure and validation. It does not add decisions, alter task-specific maxSteps, create a plan, or select a tool.

Phase 5A completion guards remain bounded to one activation per type. `TEST_FAILED_GUARD` now includes the latest `diagnosticSummary` and explicitly asks for current-source reread, targeted repair, and retest.

## 7. Metrics

All prior Phase 4/5A metrics remain. New fields are:

```text
testDiagnosticFailures
testFailureRecoverySuccesses
noEffectPatchCount
rereadAfterTestFailureCount
budgetWarnings
```

Diagnostic failures count TEST_FAILED steps carrying structured diagnostic metadata. Recovery successes count tasks that observed TEST_FAILED and ultimately passed evaluation. No-effect patches use the typed error code. Rereads are derived from ordered patch/test/read trajectory facts. Budget warnings count `STEP_BUDGET_WARNING` runtime-feedback steps.

Diagnostic type/summary are stored on the Maven `ToolResult` metadata. Patch-freshness, search-churn, budget, and completion feedback remain separate `RUNTIME_FEEDBACK` steps rather than fake tool calls.

## 8. New Strategy

The independent strategy is:

```text
REACT_DIAGNOSTIC_RECOVERY
```

Strategy hierarchy:

```text
REACT
REACT_ACTION_ORIENTED
REACT_DIAGNOSTIC_RECOVERY
```

The third strategy uses the Phase 5A completion contract and action-oriented tool descriptions, then enables Phase 5B diagnostic prompt guidance and runtime feedback. Existing REACT and REACT_ACTION_ORIENTED construction paths do not enable diagnostic-recovery feedback.

## 9. Tests

Deterministic coverage includes:

- missing-import compiler output → COMPILATION summary → diagnostic-first observation → reread → import patch → retest success;
- malformed-brace syntax output → SYNTAX summary → current-file reread → exact repair → retest success;
- immediate repair attempt after failed test → one patch-freshness warning;
- oldText equal to newText → `NO_EFFECT_CHANGE`, no successful-patch progress;
- JUnit assertion output → test class, test method, expected, and actual extraction;
- approaching maxSteps → exactly one budget warning without changing maxSteps;
- three context actions after a failed test → exactly one search-churn warning;
- READ_ONLY task → no coding recovery, freshness, or budget feedback;
- new diagnostic/recovery metrics;
- all existing Phase 5A guard tests.

Final ordinary offline suite:

```text
Tests run: 96, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

No real GLM request and no real benchmark run occurred.

## 10. Backward Compatibility

- Phase 1 workspace confinement and offline provider separation pass.
- Phase 2 typed results and trajectory persistence pass.
- Phase 3 patch/test loop and recovery tests pass.
- Phase 4 benchmark/evaluator and FakeLLM pipeline pass.
- Phase 5A completion guards, read-only behavior, and metrics pass.
- Original `REACT` and `REACT_ACTION_ORIENTED` strategies remain independently selectable.
- No model, provider setting, fixture, hidden test, evaluator, benchmark task, or maxSteps value changed.
- No arbitrary shell, automatic code repair, new code-manipulation tool, planner, reflection agent, Multi-Agent, or RL behavior was added.
- Previous real result directories and reports were not modified.

## 11. Next Experiment

After review, the next explicit experiment should use the same fixed comparison conditions:

```text
Model: glm-4-flash
Benchmark: v0.1
Split: DEV
Tasks: 6
Strategy: REACT_DIAGNOSTIC_RECOVERY
Temperature: provider default
Task-specific maxSteps: unchanged
```

Compare it against both preserved real baselines on success, TEST_FAILED recovery, MAX_STEPS, patch/test counts, reread behavior, no-effect patches, diagnostics, and latency. Do not run TEST before reviewing the DEV result. This report does not run that experiment or begin Phase 5C.
