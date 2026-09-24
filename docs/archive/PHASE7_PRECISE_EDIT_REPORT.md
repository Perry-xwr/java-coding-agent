# Phase 7 Precise Edit Report

## 1. Motivation

Real DEV trajectories showed that correct edit intent can still fail because exact multiline text, formatting, or current-source assumptions do not match. `bugfix_003` previously exhausted its budget after repeated `TEXT_NOT_FOUND`, while other trajectories produced incomplete or structurally fragile repairs. Phase 7 isolates edit precision as the next variable without changing planning, benchmark tasks, evaluators, hidden tests, model settings, or step budgets.

## 2. Existing apply_patch

`apply_patch` remains available and unchanged at the interface level. It is deterministic, auditable, and appropriate when a short `oldText` occurs exactly once. Its UTF-8 safe-write implementation now shares the same atomic text writer used by the new line editor.

## 3. New Tool

The new tool is `replace_lines`:

```json
{
  "path": "src/main/java/com/example/App.java",
  "startLine": 10,
  "endLine": 12,
  "expectedText": "exact current text for lines 10-12",
  "newText": "replacement text"
}
```

`startLine` and `endLine` are 1-based and inclusive. A successful result records `path`, `startLine`, `endLine`, `changed`, `oldLength`, `newLength`, and `lineDelta`.

`read_file` gained an optional `includeLineNumbers=true` observation mode. Its default remains false, so previous strategies and callers receive the original unnumbered content. Line numbers are observation-only and are never written into source files.

## 4. Safety

`replace_lines` reuses `WorkspacePathResolver`; it does not duplicate confinement logic. It therefore rejects absolute paths, traversal, and symlink escapes. The target must already exist and be a regular file. The tool cannot create, delete, or rename files. Input and output use explicit UTF-8, and NUL-containing or invalid UTF-8 content is rejected as `BINARY_FILE`.

## 5. Stale Context Protection

Before constructing a write, the tool extracts the current `startLine..endLine` text and compares it with `expectedText` after newline normalization. Any mismatch returns `STALE_EDIT_CONTEXT` and leaves the file unchanged. Precise-edit guidance tells the Agent to reread current line-numbered source and construct fresh arguments rather than repeating the stale edit.

## 6. Atomicity

Both editors use `AtomicTextFileWriter`:

```text
construct complete content in memory
→ create sibling temporary file
→ UTF-8 write, flush, and close
→ atomic move with replacement when supported
→ safe replacement fallback when atomic move is unavailable
→ delete remaining temporary file
```

No target write occurs until the replacement content is complete. A simulated writer failure test verifies the original file remains unchanged.

## 7. Line Ending Preservation

The editor detects CRLF versus LF from the original file, performs range operations on a normalized in-memory representation, and reconstructs the file with its original dominant line ending and trailing-newline state. A CRLF regression test confirms a one-line edit does not convert the rest of the file to LF.

## 8. Recovery Behavior

The precise strategy instructs the Agent to use `replace_lines` for clear local ranges grounded in the latest line-numbered read. After `apply_patch` returns `TEXT_NOT_FOUND` or `MULTIPLE_MATCHES`, it should reread and fall back to `replace_lines`. After `STALE_EDIT_CONTEXT`, it should reread again rather than reuse stale arguments.

`AgentProgress`, failure classification, recovery metrics, required-tool equivalence, and reread-after-failure tracking recognize both `apply_patch` and `replace_lines` as editing actions. A successful line edit therefore satisfies existing action-completion semantics without weakening the no-op or validation guards.

## 9. New Strategy

`REACT_PRECISE_EDIT` is independent and consists of:

```text
REACT_DIAGNOSTIC_RECOVERY
+ replace_lines
+ optional line-numbered read_file
+ exact-patch fallback and stale-edit recovery guidance
```

It does not include Phase 6 Planning. `REACT`, `REACT_ACTION_ORIENTED`, `REACT_DIAGNOSTIC_RECOVERY`, and `REACT_PLANNING` remain independently selectable. Existing registries do not expose `replace_lines`.

## 10. Metrics

The benchmark metrics now include:

```text
exactPatchAttempts
exactPatchFailures
lineEditAttempts
lineEditSuccesses
lineEditFailures
staleEditFailures
patchFallbackCount
```

`patchFallbackCount` counts a later `replace_lines` call after an exact patch fails with `TEXT_NOT_FOUND` or `MULTIPLE_MATCHES`. Tool usage also reports `replace_lines` independently.

## 11. Tests

`ReplaceLinesToolTest` covers:

- localized range replacement despite surrounding formatting differences;
- stale expected text with no mutation;
- reversed and out-of-bounds ranges;
- simulated atomic write failure with original preservation;
- CRLF preservation;
- no-op rejection;
- traversal rejection;
- symlink escape rejection when supported;
- NUL/binary rejection.

`ToolRegistryTest` verifies line-number observations and confirms only the precise-edit registry exposes the new tool.

`PreciseEditRecoveryTest` deterministically executes:

```text
read_file
→ apply_patch / TEXT_NOT_FOUND
→ read_file with current line numbers
→ replace_lines / success
→ run_maven_test / success
→ final
```

It verifies `exactPatchAttempts=1`, `exactPatchFailures=1`, `lineEditAttempts=1`, `lineEditSuccesses=1`, and `patchFallbackCount=1`.

Final offline suite:

```text
Tests run: 121, Failures: 0, Errors: 0, Skipped: 5
BUILD SUCCESS
```

The additional skip is the environment-dependent replace-lines symlink escape test on Windows.

## 12. Backward Compatibility

- Phase 1 confinement is reused and all safety tests pass.
- Phase 2 trajectory and typed runtime tests pass.
- Phase 3 coding-loop and exact patch tests pass.
- Phase 4 benchmark and evaluator tests pass.
- Phase 5 completion and diagnostic recovery tests pass.
- Benchmark calibration and UTF-8 diagnostic tests pass.
- Phase 6 planning tests and negative-result artifacts remain intact.
- Benchmark tasks, fixtures, hidden tests, evaluator, model, provider, and maxSteps were not changed.
- No real GLM call, DEV run, or TEST run occurred.

## 13. Limitations

- Line editing still depends on selecting the correct current line range.
- It is not AST-aware and cannot prove Java structure or behavior.
- Large concurrent changes can cause a safe stale-context rejection and require rereading.
- It cannot create, delete, or rename files.
- It does not expose arbitrary shell execution.
- The model can still make a logically incorrect edit even when the edit applies cleanly.

## 14. Next Experiment

After review, run one `glm-4-flash`, benchmark `v0.1`, DEV-6 experiment with `REACT_PRECISE_EDIT`, provider defaults, and unchanged task-specific maximum steps. Compare it with the calibrated `REACT_DIAGNOSTIC_RECOVERY` baseline of 4/6, focusing on patch failures, fallback use, line-edit success, task success, and step cost. This report does not run the experiment and does not enter TEST.
