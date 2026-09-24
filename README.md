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

## Key Features

- ReAct-style multi-step Agent loop and function calling
- Workspace confinement with traversal and symlink-escape rejection
- Repository inspection, exact code editing, and controlled Maven testing
- Compiler/test diagnostic parsing and bounded recovery behavior
- Typed `ToolResult` observations and error codes
- Structured Agent trajectories for reproducible analysis
- Isolated benchmark fixtures with hidden deterministic evaluation
- Failure analysis and multiple Agent-strategy experiments

## Tools

| Tool | Purpose |
|---|---|
| `list_files` | Inspect repository files recursively |
| `read_file` | Read UTF-8 source files |
| `search_code` | Search code with relative paths and line numbers |
| `apply_patch` | Apply an exact, single-match edit to an existing file |
| `run_maven_test` | Run controlled Maven validation with optional test selection |
| `replace_lines` | Experimental guarded line-based editing; not part of the V1 default strategy |

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

## Quick Start

Requirements: Java 17, Maven 3.9+, a GLM API key, and the currently configured HTTP proxy at `127.0.0.1:7897`.

```powershell
$env:GLM_API_KEY="YOUR_KEY"
mvn test
mvn exec:java '-Dexec.mainClass=com.agent.Main'
```

The CLI accepts normal questions and coding/repository tasks. Enter `clear` to reset conversation history while retaining the system message. `GLM_DEBUG=true` enables HTTP status logging; it is off by default.

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

## Limitations

- Small benchmark and one primary model/provider
- Stochastic LLM behavior and only one frozen TEST run
- Java/Maven task scope rather than arbitrary repositories
- No arbitrary shell or unrestricted file write/delete
- Refactoring and recovery after compiler/test failures remain weak
- No Multi-Agent system, persistent Memory, or Agentic RL in V1

## Roadmap

V2 exploration: Agentic RL with verifiable coding rewards.

Possible future exploration, without commitment: Multi-Agent coordination and persistent Memory.
