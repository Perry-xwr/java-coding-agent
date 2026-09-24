# Architecture

## Runtime Components

The runtime separates model communication, orchestration, repository tools, process execution, and observability. `LLMClient` is the provider-facing abstraction; `GlmClient` implements it. `Agent` owns the bounded loop and history. `ToolRegistry` exposes model-callable schemas and dispatches typed operations.

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

`ToolRegistry` registers independent tools by stable name and supplies their JSON parameter schemas to the LLM. V1 coding runs expose repository inspection, exact patching, and controlled Maven testing. Registry variants make experimental capabilities explicit rather than silently changing the default strategy.

## ToolResult

Every tool returns a typed `ToolResult`: success state, output, error code, error message, and structured metadata. Expected failures such as `TEXT_NOT_FOUND`, `MULTIPLE_MATCHES`, `TEST_FAILED`, or `WORKSPACE_VIOLATION` become observations that the Agent can recover from instead of uncaught exceptions.

## AgentProgress

`AgentProgress` summarizes observable run state: edits, validation state, diagnostics, repeated actions, and completion evidence. It supports guards and recovery prompts without exposing or persisting hidden model reasoning.

## Workspace Safety

`WorkspacePathResolver` requires workspace-relative paths, normalizes traversal, resolves existing targets with `toRealPath`, and rejects symlink or junction escapes. File operations fail closed when a target cannot be resolved safely.

## Editing

`apply_patch` edits existing UTF-8 files only and requires exactly one `oldText` match. Writes use a temporary peer file and atomic replacement when the filesystem supports it. `replace_lines` adds guarded range and stale-context checks, but remains experimental and is not enabled in the V1 default strategy.

## Maven Execution

`run_maven_test` builds a fixed `ProcessBuilder` argument list. The model cannot provide an arbitrary command or working directory. Only the `test` goal and validated Java test selectors are accepted. Execution has a 120-second timeout, descendant-process termination, and a 100 KiB combined-output cap.

## Diagnostic Recovery

Compiler and test output is parsed into structured diagnostics such as type, summary, file, line, test class, expected value, and actual value. V1 feeds concise failure observations back into the loop and asks the Agent to reread changed files before constructing recovery patches.

## Trajectory

Each run records ordered tool calls, normalized arguments, typed results, runtime feedback, timing, final answer, and termination reason. Trajectories support debugging and evaluation without storing private chain-of-thought.

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
