# Phase 1 Safety and Deterministic Test Report

## 1. Summary

Phase 1 fixed the two identified P0 issues without adding Coding Agent permissions:

1. All three existing read-only file tools now share one workspace path resolver that rejects absolute paths, normalized traversal, and detectable symbolic-link/reparse-point escapes.
2. The live GLM test no longer runs during ordinary `mvn test`. Agent behavior is covered with `FakeLLMClient`, while the real provider smoke test requires an explicit Maven profile.

No write, edit, patch, delete, shell, Maven-execution, Git, planning, benchmark, trajectory-persistence, or RL capability was added.

## 2. Modified Files

| File | Purpose |
| --- | --- |
| `pom.xml` | Configures deterministic test temp paths and the opt-in `glm-integration` profile flag. |
| `src/main/java/com/agent/tool/ToolRegistry.java` | Replaces adapter-local path resolution with the shared resolver. |
| `src/test/java/com/agent/agent/AgentTest.java` | Removes real GLM access; adds deterministic one-tool and max-iteration tests. |
| `src/test/java/com/agent/tool/ToolRegistryTest.java` | Adds tool-boundary validation for a valid read and rejected traversal. |

New files:

| File | Purpose |
| --- | --- |
| `src/main/java/com/agent/tool/WorkspacePathResolver.java` | Centralized workspace root canonicalization and existing-target confinement. |
| `src/main/java/com/agent/tool/WorkspaceViolationException.java` | Distinguishes workspace-policy violations from not-found and other I/O failures. |
| `src/test/java/com/agent/tool/WorkspacePathResolverTest.java` | Temp-directory adversarial path test suite. |
| `src/test/java/com/agent/agent/GlmIntegrationTest.java` | Explicitly gated live GLM smoke test. |
| `SECURITY.md` | Records workspace and test policies. |
| `PHASE1_SAFETY_TEST_REPORT.md` | Records this implementation and its verification. |

The pre-existing untracked `PROJECT_AUDIT.md` was not overwritten as part of the implementation.

## 3. Workspace Confinement Design

### Root

`WorkspacePathResolver` converts the configured root to an absolute normalized `Path`. When the root exists, it calls `toRealPath()` and stores the canonical/real root. Failure to resolve an existing root is fail-closed.

### Normalization and traversal

Tool input is parsed with `Path.of`, resolved against the stored root, normalized, and compared using `Path.startsWith(Path)`, not string prefixes. A normalized target outside the root throws `WorkspaceViolationException` before filesystem access.

Inputs such as `./README.md` and `src/../pom.xml` remain valid because normalization keeps their final paths inside the workspace. Inputs such as `../outside.txt`, `../../outside.txt`, and `src/../../../outside.txt` are rejected.

### Absolute path policy

All tool-supplied absolute paths are rejected, including absolute paths that happen to point inside the workspace. This keeps the external tool contract uniformly workspace-relative and prevents drive/UNC path ambiguity.

### Existing targets and real paths

After lexical confinement, `resolveExisting` calls `toRealPath()` on the target and again checks the resulting `Path` against the real workspace root. Missing paths preserve `NoSuchFileException`; access failures remain I/O failures; real targets outside the workspace produce `WorkspaceViolationException`.

### Symbolic-link and reparse-point policy

Java NIO-detectable symbolic links, junctions, and reparse redirects are followed by `toRealPath()`. If their final target is outside the real workspace, access is rejected. If real-path resolution cannot confirm a target, access fails rather than silently allowing it.

The current resolver is intentionally limited to existing read-only targets. Future creation/write operations need a separate parent-real-path and race-resistant creation policy.

## 4. Security Tests

All security fixtures use JUnit `@TempDir`; no real user or system file is read.

| Category | Cases | Result |
| --- | ---: | --- |
| Valid root file | `README.md` | Passed |
| Valid nested file | `src/main/java/App.java` | Passed |
| Valid dot-relative file | `./README.md` | Passed |
| Valid normalization | `src/../pom.xml` | Passed |
| Traversal | `../outside.txt` | Rejected, passed |
| Traversal | `../../outside.txt` | Rejected, passed |
| Mixed traversal | `src/../../../outside.txt` | Rejected, passed |
| Outside absolute path | Temp file outside workspace | Rejected, passed |
| Inside absolute path | Temp file inside workspace | Rejected by policy, passed |
| Symlink escape | Workspace link to outside temp file | Conditionally skipped: current Windows environment did not permit symbolic-link creation |
| Tool-boundary manual case | `read_file("../outside.txt")` | Rejected with `WorkspaceViolationException` as the cause |
| Tool-boundary valid case | `read_file("README.md")` | Returned expected content |

The three required traversal inputs all passed their rejection assertions. The symlink test remains present and will execute automatically on an environment that permits link creation.

Existing tests for `list_files`, `read_file`, and `search_code` all continue to pass.

## 5. Test Separation

Ordinary execution:

```text
mvn test
```

is now offline and deterministic with respect to the LLM provider. It does not construct `GlmClient` or access GLM, even when `GLM_API_KEY` exists. Agent unit tests use `FakeLLMClient` for final-answer, one-tool, multi-step, tool-error, history-clear, and maximum-iteration behavior.

The real smoke test requires both explicit profile activation and an API key:

```text
mvn test -Pglm-integration
```

If `GLM_API_KEY` is absent, JUnit skips `GlmIntegrationTest`. The key is never printed by the test.

No MockWebServer dependency was added in this phase. Deterministic `GlmClient` protocol tests remain a focused P1 protocol-test refactor because the current client does not expose its request construction/parsing seams without widening this phase.

## 6. Test Results

### Baseline before modification

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Because the audit environment contained `GLM_API_KEY`, that baseline ordinary run invoked the real provider.

### Final ordinary suite

```text
Tests run: 37, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

- Passed: 35
- Failed: 0
- Errors: 0
- Skipped: 2
- Skip reasons: one deliberately disabled live GLM integration test; one unsupported symbolic-link creation test on the current Windows environment.
- Real GLM/network calls during the ordinary suite: 0 by test gating and code path.

### Targeted path suite

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

The single skip is the platform-capability symlink case.

## 7. Remaining Risks

- Java NIO covers symbolic links and commonly exposed reparse behavior, but unusual Windows reparse-point edge cases may require additional platform-specific validation.
- Real-path validation and subsequent file opening are separate operations, so a hostile concurrent filesystem actor could attempt a time-of-check/time-of-use race. Current tools are read-only, but this must be reconsidered before write access.
- Future non-existing targets cannot use `toRealPath()` directly. Write/create tools need a separate policy that canonicalizes the existing parent and safely handles creation races.
- Shell safety, command allowlists, output/time limits, and dangerous-command protection are not implemented because no shell capability exists.
- Deterministic mock-HTTP coverage for `GlmClient` serialization and response/error parsing is still missing.

## 8. Next Recommended Phase

Phase 2: Structured Runtime Events / Typed Tool Results

After that foundation is complete and tested, add Safe Editing as a separate phase.
