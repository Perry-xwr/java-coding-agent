# Java Coding Agent

A Java 17 command-line coding agent built around a tool-calling loop, workspace-scoped file tools, persistent conversations, and long-term memory. The project is an educational prototype; it is not an operating-system sandbox and model behavior is not guaranteed for every request.

## Features

- DeepSeek Chat Completions and tool calling through the Java provider layer.
- Daily terminal UI with streamed responses and conversation management.
- Workspace-relative file discovery, reading, new-file creation, and targeted text edits.
- Fixed-goal Maven test execution with explicit approval in the Daily workflow.
- Saved and resumable sessions, plus opt-in search of prior sessions in the current workspace.
- Agent-managed long-term memory: the model may propose a durable preference; Java binds it to the current user turn and checks authorization, scope, quality, sensitive content, duplication, and conflicts before saving. Relevant effective memories can be retrieved and injected in a later session, with exposure IDs recorded.

## Technology

Java 17, Maven, JLine, Jackson, and an OpenAI-compatible HTTP client implementation. The Daily launcher uses DeepSeek. The provider layer also supports GLM and configured OpenAI-compatible endpoints in the applicable entry points.

## Requirements

- JDK 17
- Maven
- Windows PowerShell and a terminal that supports JLine (Windows Terminal recommended for the Daily UI)
- A DeepSeek API key for live Daily conversations

## Configure and launch (Windows)

The launcher reads `DEEPSEEK_API_KEY` from its process environment. It does not load `.env` automatically. `.env.example` is a placeholder-only reference; do not put a real key in it. Set the key in Windows User Environment Variables, then open a new PowerShell window.

From the project root, first download/prewarm Maven dependencies from your configured Maven repository:

```powershell
mvn -f .\pom.xml "-Dmaven.repo.local=.m2/repository" -DskipTests compile
mvn -f .\pom.xml "-Dmaven.repo.local=.m2/repository" org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath "-DincludeScope=runtime" "-Dmdep.outputFile=target/classpath-preflight.txt"
```

Then launch against an existing project directory:

```powershell
.\scripts\run-agent.ps1 -Workspace "C:\path\to\existing-project"
```

The script builds offline using the project-local `.m2/repository`, then starts `com.agent.tui.prototype.DailyTuiMain`. Omit `-Workspace` to use the current directory. Starting the program does not make a model request; sending a chat message does and may incur provider charges. Do not run two CLI instances against the same workspace at once.

## Example interaction

In Daily, ask a normal coding question, for example:

> In this workspace, create `hello.py` with a `main()` function that prints `Hello, Agent`.

The agent can inspect the workspace and use the registered file tools. Verify the resulting file and any test output directly; a model's statement that an action succeeded is not execution evidence.

## Sessions and memory

- `/new`, `/sessions`, `/switch <session-id>`, `/rename`, and `/history` manage conversations; exact shortcuts are shown in the TUI and `/help`.
- Session history preserves the conversation and can be searched explicitly in the current workspace. It is distinct from long-term memory.
- Memory is workspace-scoped and requires user authorization. When enabled, the agent may call `memory_propose` for a clear durable preference. Java binds the proposal to the current user message and applies validation before persistence. Effective memories are retrieved by relevance for later requests; pending or rejected proposals are not treated as effective memory.
- `/settings memory status`, `/settings memory off`, `/memory list`, and `/memory pending` expose available status and review operations. Turning memory off stops future memory use; it does not itself erase already stored entries.
- Natural-language memory update and forgetting are not claimed as completed capabilities. Memory does not retain every conversation detail; use session history for specific past discussion.

## Build and tests

Run the deterministic test suite with:

```powershell
mvn test
```

The default test suite uses offline/fake providers and should not call a live model API. Live provider experiments are separate and must be explicitly enabled by their documented profile or entry point. The latest recorded full run was performed in an isolated copy on 2026-11-05: 1035 tests, 0 failures, 0 errors, 16 skipped. This is historical isolated-copy evidence, not a claim that this checkout was just tested.

## Security and limitations

File tools are restricted to the configured workspace and validate relative paths. They do not create an OS sandbox: the Java process still runs with the current user's permissions. There is no arbitrary shell tool; Maven test execution uses a fixed goal and requires approval in the Daily workflow. Tool permissions and model intent classification are separate safeguards, not a guarantee against every malformed or ambiguous request. See [SECURITY.md](SECURITY.md) for the implemented boundaries and [docs/architecture.md](docs/architecture.md) for the system overview.

Additional technical notes: [design](docs/DESIGN.md), [session and context memory](docs/memory_m1_sessions.md), [cross-session memory](docs/memory_m3_cross_session.md), [memory management](docs/memory_m4_management.md), and [memory evaluation](docs/evaluation/memory-d3a-v1/report.md).
