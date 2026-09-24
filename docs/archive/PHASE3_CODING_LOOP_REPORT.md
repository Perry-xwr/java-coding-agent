# Phase 3 Safe Coding Loop Report

## 1. Summary

Phase 3 adds two narrowly scoped capabilities to the existing ReAct Agent: safe existing-file patching and controlled Maven test execution without a generic shell. Both return typed `ToolResult` values and enter Phase 2 trajectories as normal `TOOL_CALL` steps. Patch mismatch and test failure are recoverable observations, allowing inspect → edit → test → edit → retest → final behavior.

`MAX_ITERATIONS` is now 10 to accommodate a bounded recovery loop. Workspace confinement, offline unit tests, structured trajectories, optional persistence, CLI compatibility, and the provider protocol remain intact. No arbitrary write, delete, shell, Git-write, benchmark, planning, or RL capability was added.

## 2. New Tools

| Tool | Input | Result |
| --- | --- | --- |
| `apply_patch` | `path`, `oldText`, `newText` | Replaces one unique occurrence in an existing UTF-8 workspace file. |
| `run_maven_test` | Optional `testClass` and `testMethod` | Runs only Maven's fixed `test` goal in the workspace. |

`ToolRegistry.withCodingTools` registers these tools alongside `list_files`, `read_file`, and `search_code`. `Main` now uses this five-tool registry. `withFileTools` remains available for read-only callers. Optional Git tools were not added because they are unnecessary for the Phase 3 acceptance path.

## 3. Editing Safety

Input format:

```json
{
  "path": "src/main/java/com/example/App.java",
  "oldText": "return a - b;",
  "newText": "return a + b;"
}
```

Rules:

1. `path` must be nonblank and workspace-relative.
2. `WorkspacePathResolver.resolveExisting` applies absolute normalization, lexical confinement, `toRealPath`, and final real-root confinement.
3. The target must already exist and be a regular file.
4. Absolute paths, traversal, and detectable symlink/reparse escapes are rejected.
5. The target is decoded and encoded as UTF-8.
6. `oldText` must be nonempty and occur exactly once; overlapping occurrences are counted.
7. Missing and multiple matches return typed errors without writing.
8. No rename, move, delete, target-file creation, or write outside the resolved workspace is exposed.

Successful metadata contains `path`, `changed`, `oldLength`, `newLength`, and `matchCount`.

Atomicity: the tool reads the original, constructs the complete replacement in memory, writes and closes a temporary file in the target directory, then uses `ATOMIC_MOVE + REPLACE_EXISTING`. If atomic move is unsupported, it falls back to same-filesystem replacement. The temporary file is deleted in `finally`. Validation failures happen before temporary-file creation, so mismatches leave the original unchanged.

Patch-related error codes are:

- `WORKSPACE_VIOLATION`
- `FILE_NOT_FOUND`
- `ACCESS_DENIED`
- `INVALID_ARGUMENTS`
- `MULTIPLE_MATCHES`
- `TEXT_NOT_FOUND`
- `NOT_A_REGULAR_FILE`
- `WRITE_FAILED`
- `TOOL_EXECUTION_ERROR`

## 4. Process Safety

`run_maven_test` is controlled process execution, not arbitrary shell access.

- It uses `ProcessBuilder(List<String>)`; no `cmd /c`, `powershell -Command`, shell interpolation, or model-provided command is used.
- The executable is fixed to `mvn.cmd` on Windows or `mvn` elsewhere.
- The Maven goal is always `test`.
- Supported modes are all tests, one validated class, or one validated class method.
- Class names accept Java identifiers plus package separators; method names accept one Java identifier. Control characters are rejected before process start.
- Working directory is fixed to the real configured workspace root.
- The host selects the fixed Maven local repository; the model cannot override it.
- Timeout is 120 seconds; timeout destroys the process and descendants.
- Combined stdout/stderr capture is capped at 100 KiB and then drained without further retention.
- Exit code 0 is success. Nonzero exit is recoverable `TEST_FAILED` with captured output.
- Start failure is `PROCESS_START_FAILED`; timeout is `PROCESS_TIMEOUT`.

Process metadata contains `exitCode`, `durationMs`, `timedOut`, `outputTruncated`, and optional `testClass` / `testMethod`.

## 5. Coding Loop

No fixed workflow was added to `Agent`. The existing function-calling loop naturally supports:

```text
Inspect
→ read_file
→ apply_patch
→ run_maven_test
→ Observation
   ├─ passed → final answer
   └─ failed → apply_patch → run_maven_test → final answer
```

Test failure is a failed `ToolResult`, not fatal Agent termination. Its error code, output, metadata, and duration enter the LLM observation and trajectory. Only LLM transport failure and the bounded maximum-iteration condition terminate abnormally.

The deterministic recovery fixture performs:

```text
read_file
→ apply_patch (a - b → a * b)
→ run_maven_test (TEST_FAILED: expected 5 but was 6)
→ apply_patch (a * b → a + b)
→ run_maven_test (success)
→ FINAL_ANSWER
```

## 6. Trajectory Example

The deterministic recovery test creates and persists this real structured sequence:

```text
task: Fix add() and make the test pass.
step 1: TOOL_CALL read_file          success=true
step 2: TOOL_CALL apply_patch        success=true, changed=true
step 3: TOOL_CALL run_maven_test     success=false, errorCode=TEST_FAILED,
                                     output="expected 5 but was 6", exitCode=1
step 4: TOOL_CALL apply_patch        success=true, changed=true
step 5: TOOL_CALL run_maven_test     success=true, exitCode=0
step 6: FINAL_ANSWER                 "Corrected after the failed test."
terminationReason: FINAL_ANSWER
completed: true
taskSuccess: null
```

`taskSuccess` remains unknown because no evaluator exists. Optional persistence still uses `TrajectoryJsonWriter`; process output receives the existing persistence-only truncation and secret redaction.

## 7. Tests

Baseline before Phase 3:

```text
Tests run: 50, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

Final ordinary offline suite:

```text
Tests run: 72, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

- Passed: 68
- Failed: 0
- Errors: 0
- Skipped: 4
- Skips: live GLM smoke test, two unavailable Windows symlink-creation cases, and the opt-in real Maven fixture test.
- Real GLM calls in ordinary suite: 0.
- Real Maven subprocesses in ordinary unit tests: 0; unit tests inject `ProcessRunner`.

The explicit real fixture verification command was:

```text
mvn -Dphase3.integration=true -Dtest=com.agent.agent.CodingLoopIntegrationTest test
```

It patched a temporary Calculator fixture and ran its real `CalculatorTest` through `run_maven_test`:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Coverage includes unique replacement, missing text, multiple matches, traversal, absolute path, missing target, directory target, symlink escape capability, unchanged replacement, failure preservation, three Maven selection modes, selector injection rejection, process start failure, timeout, output-cap metadata, successful coding loop, failure/repatch/retest recovery, and trajectory persistence of both tools.

## 8. Security Regression

Phase 1 workspace tests continue to pass. All traversal forms, absolute-path rejection, valid normalization, and tool-boundary confinement remain intact. The original and patch symlink tests remain present but skip on this Windows host because symbolic-link creation is unavailable.

`apply_patch("../outside.txt", ...)` returns `WORKSPACE_VIOLATION`. Patch tests use only JUnit temporary directories and never access real user/system files.

## 9. Remaining Limitations

- No arbitrary shell or arbitrary Maven goal.
- No arbitrary file creation or `write_file`.
- No file deletion, rename, or move.
- No Git write operation and no Git tool.
- Patch mode is exact text replacement, not unified diff or AST-aware editing.
- Atomic-move fallback depends on filesystem replacement semantics; concurrent hostile filesystem races remain a broader concern.
- Maven execution is local and single-process; there is no container isolation or OS resource sandbox beyond timeout/output limits.
- No benchmark or evaluator; `taskSuccess` remains null.
- No planning comparison and no Agentic RL/reward implementation.

## 10. Next Phase

Phase 4: Benchmark + Evaluator + Failure Analysis + Baselines

This report does not implement Phase 4.
