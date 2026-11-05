# Architecture

## Runtime Components

The runtime separates model communication, orchestration, workspace tools, process execution, and observability. `LLMClient` is the provider-facing abstraction; `GlmClient`, `OpenAiCompatibleClient`, and the DeepSeek streaming facade provide adapters. `Agent` owns the bounded loop and full in-memory history. `ToolRegistry` exposes model-callable schemas and dispatches typed operations through `AgentEnvironment`.

```text
Task
 ↓
LLM
 ↓
Agent
 ↓
Tool
 ↓
Environment
 ↓
Observation
 └────→ Agent
```

## Agent Loop

The Agent appends a user task, requests an LLM response, executes any tool calls, serializes each `ToolResult` as an observation, and repeats until a final answer or the task-specific step budget is reached. Runtime feedback guards discourage premature completion and repeated failed actions. The runtime stores actions and observations, not hidden chain-of-thought.

## Tool Registry

`ToolRegistry` registers independent tools by stable name and supplies their JSON parameter schemas to the LLM. READ exposes discovery and read-only tools; CODE additionally exposes file mutation and conditionally the constrained Maven test tool. `list_files` and `search_code` produce bounded paged JSON objects; `find_files` has a fixed result cap and a truncation indicator. `read_file` remains unpaged and complete for editing evidence. Registry variants make experimental capabilities explicit rather than silently changing the default strategy.

## ToolResult

Every tool returns a typed `ToolResult`: success state, output, error code, error message, and structured metadata. Expected failures such as `TEXT_NOT_FOUND`, `STALE_EDIT_CONTEXT`, `TEST_FAILED`, or `WORKSPACE_VIOLATION` become observations that the Agent can recover from instead of uncaught exceptions.

## AgentProgress

`AgentProgress` summarizes observable run state: edits, validation state, diagnostics, repeated actions, and completion evidence. It supports guards and recovery prompts without exposing or persisting hidden model reasoning.

## Workspace Safety

`WorkspacePathResolver` requires workspace-relative paths, normalizes traversal, resolves existing targets with `toRealPath`, and rejects symlink or junction escapes. File operations fail closed when a target cannot be resolved safely.

## Editing

`apply_patch` edits existing UTF-8 files only and requires exactly one `oldText` match. `apply_context_patch` accepts a complete expected file snapshot and one or more exact unique SEARCH/REPLACE edits; it rejects stale snapshots and validates the entire edit set before a single atomic write. `insert_before` and `insert_after` are literal insertion tools. These tools do not parse language structure. `replace_lines` remains experimental and is not in the normal CLI profile.

## Maven Execution

`run_maven_test` builds a fixed `ProcessBuilder` argument list. The model cannot provide an arbitrary command or working directory. Only the `test` goal and validated Java test selectors are accepted. Execution has a 120-second timeout, descendant-process termination, and a 100 KiB combined-output cap.

## Diagnostic Recovery

Compiler and test output is parsed into structured diagnostics such as type, summary, file, line, test class, expected value, and actual value. V1 feeds concise failure observations back into the loop and asks the Agent to reread changed files before constructing recovery patches.

## Trajectory

Each run records ordered tool calls, normalized arguments, typed results, runtime feedback, timing, final answer, and termination reason. Trajectories support debugging and evaluation without storing private chain-of-thought.

Provider-returned token usage is associated with each request when available. Request telemetry also records exact serialized payload bytes and message/tool counts; these size metrics are not token estimates. The CLI reports a per-task summary and marks missing provider usage unknown. It does not estimate monetary cost.

The optional request-scoped context selector operates only on a request copy. Its default is off. When enabled, it removes only certain exact duplicate prior internal-context entries and identical, successful, complete read-only discovery exchanges. It leaves the original Agent history and session snapshots unchanged and conservatively retains uncertain groups, file contents, edits, and verification evidence.

The interactive CLI offers manual `/save` and `/load` snapshots for all CHAT/READ/CODE histories and CLI routing context. Snapshots are workspace-local under `.agent-cli/sessions/`; they are not encrypted and may contain conversation/tool text. Loading validates the snapshot before replacing state; historic tool calls are not replayed.

## Evaluation Pipeline

```text
Trajectory
 ↓
Evaluator
 ↓
Metrics
 ↓
Failure Analysis
```

Benchmark tasks are copied into isolated workspaces. A deterministic evaluator injects held-out tests only after the Agent run, applies behavior-first and task-specific content checks, and aggregates task, category, difficulty, tool, recovery, and termination metrics.
