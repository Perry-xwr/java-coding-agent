# Java Coding Agent

A Java-based coding agent runtime with safe repository tools, code editing, controlled Maven testing, diagnostic recovery, structured trajectories, and benchmark-driven evaluation.

## Overview

The project began as a Java course project and evolved into a coding-agent runtime plus an evaluation framework. The V1 runtime uses GLM function calling to inspect a repository, apply constrained edits, run Maven tests, observe typed failures, and continue a bounded ReAct-style loop.

```text
User Task
   ↓
LLM
   ↓
Agent Loop
   ↓
Tool Call
   ↓
Repository / Maven Environment
   ↓
Observation
   ↓
Recovery / Next Action
   ↓
Final Answer
```

Evaluation is kept separate from runtime execution:

```text
Agent Run → Trajectory → Hidden Evaluator → Metrics → Failure Analysis
```

Working memory is session-level, bounded, non-persistent, and grounded in user requests and tool observations. It is not long-term memory, semantic retrieval, or RAG.

## Key Features

- ReAct-style multi-step Agent loop and function calling
- Interactive `CHAT` / `READ` / `CODE` modes plus deterministic `AUTO` routing, with streamed model output
- Workspace confinement with traversal and symlink-escape rejection
- Repository inspection and discovery, safe new-file creation, exact anchored editing, and controlled Maven testing
- Compiler/test diagnostic parsing and bounded recovery behavior
- Post-edit evidence gates: reread changed files and require Maven evidence for Java changes
- Typed `ToolResult` observations and error codes
- Bounded structured session working memory for active tasks, explicit targets, discovered files, verified tool facts, recent typed failures, and the last mutation
- Structured Agent trajectories for reproducible analysis
- Isolated benchmark fixtures with hidden deterministic evaluation
- Failure analysis and multiple Agent-strategy experiments

## Tools

| Tool | Purpose |
|---|---|
| `list_files` | Inspect repository files recursively |
| `find_files` | Discover workspace files by safe glob pattern without reading file contents |
| `read_file` | Read UTF-8 source files |
| `search_code` | Search code with relative paths and line numbers |
| `apply_patch` | Apply an exact, single-match edit to an existing file |
| `insert_before` | Insert text before one exact, unique anchor in an existing file |
| `insert_after` | Insert text after one exact, unique anchor in an existing file |
| `create_file` | Create one new UTF-8 text file without overwriting an existing path |
| `run_maven_test` | Run controlled Maven validation with optional test selection |
| `replace_lines` | Experimental guarded line-based editing; not part of the V1 default strategy |

## Planning

The CODE profile defaults to `REACTIVE`. Set `PLANNING_MODE=plan-execute` to opt into the
experimental `PLAN_EXECUTE` strategy: each task run performs an independent PLAN → EXECUTE flow
with at most one bounded REPLAN. The plan guides execution; it is not a verified workspace fact.
This is optional experimental support, not multi-agent orchestration.

Set `PLANNING_MODE=adaptive` to enable experimental zero-LLM planning routing: simple tasks stay
reactive, while tasks with clear multi-step, multi-requirement, cross-file, or verification signals
may use `PLAN_EXECUTE`. The deterministic heuristic runs before the first provider request and is
not yet evaluated by an independent adaptive benchmark.

### Planning Ablation

In two paired live rounds over the same eight `planning-v1` DEV tasks, `REACTIVE` scored 2/8 then
5/8, and `PLAN_EXECUTE` scored 3/8 then 2/8. Across the same eight tasks repeated twice (16 task-runs
per mode, not independent tasks), the descriptive totals were 7/16 with 94 requests for REACTIVE
and 5/16 with 119 requests for PLAN_EXECUTE. No repeatable success advantage was observed; this is
a small, stochastic DEV experiment, not a statistical result. See [planning-v1 results](benchmark/planning-v1/README.md).

## Safety Model

V1 accepts workspace-relative paths only and rejects traversal, absolute paths, and Java NIO-detectable symlink escapes. It exposes no unrestricted write/delete operation and no arbitrary shell. Maven execution uses an allowlisted goal, a fixed working directory, validated test selectors, a timeout, and bounded output capture. Text replacement uses temporary files and atomic replacement when supported.

These controls reduce risk but do not make execution completely secure. See [SECURITY.md](SECURITY.md) for the exact policy.

## Benchmark

Benchmark v0.1 contains 20 Java/Maven tasks: 6 DEV and 14 TEST. Categories are `BUG_FIX`, `LOGIC_FIX`, `TEST_FIX`, `SMALL_REFACTOR`, and `MULTI_STEP_DEBUG`, with EASY, MEDIUM, and HARD difficulty labels.

Every task runs in an isolated fixture workspace. Evaluation combines hidden deterministic tests with behavior-first checks; strict source-content constraints are used only when the task explicitly requires them. See [docs/benchmark.md](docs/benchmark.md).

## Experiments

| Strategy | DEV Success |
|---|---:|
| REACT | 0/6 |
| REACT_ACTION_ORIENTED | 3/6 |
| **REACT_DIAGNOSTIC_RECOVERY** | **4/6** |
| REACT_PLANNING | 3/6 |
| REACT_PRECISE_EDIT | 1/6 |

`REACT_DIAGNOSTIC_RECOVERY` was selected as the V1 default before held-out evaluation. Planning and precise editing are retained as negative experiments rather than hidden or discarded.

The first and only frozen held-out TEST run scored **5/14 (35.71%)**. There was no tuning on TEST, no rerun, and no selection of a favorable random run. Detailed results are in [docs/v1-final-test-report.md](docs/v1-final-test-report.md).

## Key Findings

1. Action-oriented completion substantially improved actual tool execution on the DEV set.
2. Diagnostic recovery achieved the strongest calibrated DEV result.
3. Planning did not activate reliably under the tested protocol and model.
4. Precise line editing recovered one exact-patch failure but introduced substantial interaction overhead.
5. Held-out TEST performance was materially lower than DEV performance, revealing generalization limits, especially for refactoring and recovery.

The samples are small; these are descriptive findings, not claims of statistical significance.

## Evaluation

The repository contains three evaluation tracks:

- V0.1 historical benchmark for early Java/Maven agent behavior
- V1.2 coding-agent benchmark for the current interactive runtime
- `memory-v1` paired ablation benchmark comparing legacy file-reference context with structured session working memory

In two observed paired DEV rounds over the same eight `memory-v1` tasks, Legacy Context scored 6/8 in both rounds and Structured Memory scored 7/8 in both rounds. This is a small descriptive result, not a statistically significant estimate: the task set is small, model behavior is stochastic, and one task has a known completion-contract limitation. The cleanest repeated signal was lower cross-turn overhead when continuing from the last mutation. See [the memory-v1 protocol](benchmark/memory-v1/README.md).

### Adaptive Planning Evaluation

The `adaptive-planning-v1` protocol compares REACTIVE, PLAN_EXECUTE, and a zero-LLM heuristic ADAPTIVE router on nine DEV tasks. Its first live round was interrupted after repeated local proxy connection failures; the protocol correctly stopped before Round 2. The available single-round observations are incomplete and descriptive only, with no paired-repeatability or superiority claim. See [the adaptive-planning protocol and run note](benchmark/adaptive-planning-v1/README.md).

## Quick Start

Requirements: Java 17, Maven 3.9+, and a GLM API key by default. The default GLM backend continues to use the configured HTTP proxy at `127.0.0.1:7897`.

```powershell
$env:GLM_API_KEY="YOUR_KEY"
mvn test
mvn exec:java '-Dexec.mainClass=com.agent.Main'
```

The CLI starts in `AUTO` mode and deterministically routes clear workspace reads to `READ`, explicit workspace changes to `CODE`, and general questions to `CHAT`. Use `/chat`, `/read`, `/code`, or `/auto` to override the active mode. Output is streamed as it arrives. In `CODE`, successful writes must be reread before completion, and Java changes require a successful Maven test when the tool is available. Enter `clear` in `AUTO` mode to reset all profile histories and the short-lived workspace reference context. `GLM_DEBUG=true` enables HTTP status logging; it is off by default.

The default model backend is GLM (`MODEL_PROVIDER=glm`, the default) and reads `GLM_API_KEY`. An optional non-streaming OpenAI-compatible backend can be selected with `MODEL_PROVIDER=openai-compatible`, `MODEL_BASE_URL`, and `MODEL_NAME`; `MODEL_API_KEY` is optional for local or otherwise unauthenticated endpoints. This backend supports chat completions and function/tool calling, but streaming remains GLM-only. Backend-specific settings are read from environment variables; no key is stored in the repository.

For pasted multi-line prompts, normal paste capture is supported. `/begin` followed by `/end` remains the reliable explicit fallback when terminal input timing is ambiguous.

Ordinary `mvn test` is deterministic and does not call GLM. The live smoke test is opt-in through `mvn test -Pglm-integration`.

## Benchmark Usage

The benchmark requires `GLM_API_KEY`. The exact Maven invocation used by this repository is:

```powershell
mvn exec:exec '-Dexec.executable=java' '-Dexec.args=-classpath %classpath com.agent.benchmark.BenchmarkMain --baseline react_diagnostic_recovery --split dev'
```

Supported baseline values include `react`, `react_action_oriented`, `react_diagnostic_recovery`, `react_planning`, and `react_precise_edit`. Filters also include `--category`, `--difficulty`, `--limit`, and `--output`.

> TEST is intended as held-out evaluation and should not be used for prompt or strategy tuning. The frozen V1 TEST run has already been completed and must not be rerun to select a better outcome.

## Documentation

- [Architecture](docs/architecture.md)
- [Benchmark design](docs/benchmark.md)
- [Experiment record](docs/experiments.md)
- [Failure analysis](docs/failure-analysis.md)
- [Representative demo](examples/demo.md)
- [V1 held-out TEST report](docs/v1-final-test-report.md)

## Known Limitations

- Small benchmark and one primary model/provider
- Stochastic LLM behavior and only one frozen TEST run
- Java/Maven task scope rather than arbitrary repositories
- No arbitrary shell or unrestricted file write/delete
- Refactoring and recovery after compiler/test failures remain weak
- Post-edit evidence does not guarantee semantic correctness of an edit
- Streamed intermediate model text can appear before a final structured failure state
- Automatic multi-line paste capture is timing-sensitive; `/begin` and `/end` are the reliable fallback
- AUTO routing is deterministic and intentionally lightweight; ambiguous workspace requests default to safer read-only handling
- Tool selection still depends on the LLM after routing
- Structurally sensitive edits may still select a suboptimal insertion tool or anchor; the runtime has no AST parser
- Verification for non-Java projects is limited compared with the Maven-based Java verification path
- Working memory is session-only and is not persisted across CLI restarts
- There is no persistent or semantic long-term memory and no RAG subsystem
- Benchmark sets are small; reported results are descriptive rather than statistically significant
- The `memory-v1` benchmark contains a known clarification/completion-contract limitation
- This is a research/educational coding-agent runtime, not a production IDE replacement
- No Multi-Agent system or Agentic RL in the current runtime

## Roadmap

V2 exploration: Agentic RL with verifiable coding rewards.

Possible future exploration, without commitment: Multi-Agent coordination and persistent Memory.
