# Java Agent CLI — Current-State Audit

> Audit date: 2026-09-23
> Repository: `<repo-root>`
> Scope: read-only inspection of source code, tests, Maven configuration, and Git state. This report does not treat planned capabilities as implemented.

## 1. Executive Summary

The repository is a small Java 17 command-line, read-only coding assistant. It has a genuine multi-step, ReAct-style function-calling loop: a user request is sent to GLM, model-produced tool calls are dispatched through a registry, tool observations are appended to conversation history, and the model is called again until it returns a final answer or the five-iteration limit is reached.

The current implementation is beyond a simple single-call LLM demo. It has an injectable `LLMClient`, GLM function calling, three file-inspection tools, stable JSON tool schemas, error observations, conversation clearing, deterministic fake-LLM tests, and one environment-gated live GLM test. It is not yet a full Coding Agent: it cannot edit files, run commands or tests, inspect Git, apply patches, or iteratively debug its own changes. It also lacks workspace path confinement, persistent trajectory logging, evaluation metrics, context management, transport retry, and a benchmark.

The most urgent issue is safety. File paths received through tool arguments are normalized but are not constrained to the configured workspace root. Absolute paths and `..` traversal can therefore escape the intended root. This must be addressed before adding write or shell capabilities.

### Basic project facts

| Item | Actual value |
| --- | --- |
| Java runtime | OpenJDK Temurin 17.0.20.1 |
| Maven | Apache Maven 3.9.16 |
| Maven coordinates | `com.agent:agent-cli:0.1.0` |
| Compiler target | `maven.compiler.release=17` |
| Main class | `com.agent.Main` |
| Packaging | Default Maven `jar` |
| Main Java files | 15 |
| Test Java files | 6 |
| Current Git branch | `resume-upgrade`, tracking `origin/resume-upgrade` |
| Working tree at audit start | One untracked file: `start-agent.bat`; no tracked modification |

### Maven configuration

`pom.xml` uses Java release 17 and UTF-8 source encoding. Its direct dependencies are OkHttp 4.12.0, Jackson Databind 2.17.2, and JUnit Jupiter 5.10.2 with `test` scope. `exec-maven-plugin` 3.5.0 identifies `com.agent.Main` as the entry point. No application framework such as Spring is present.

### Packages and directory structure

```text
src/
├─ main/java/com/agent/
│  ├─ Main.java
│  ├─ Agent.java                  # legacy hello/echo class
│  ├─ agent/
│  │  └─ Agent.java              # current ReAct agent
│  ├─ llm/
│  │  ├─ LLMClient.java
│  │  ├─ GlmClient.java
│  │  ├─ Message.java
│  │  ├─ LLMResponse.java
│  │  ├─ ToolCall.java
│  │  └─ ToolDefinition.java
│  └─ tool/
│     ├─ Tool.java
│     ├─ ToolRegistry.java
│     ├─ ListFilesTool.java
│     ├─ ReadFileTool.java
│     ├─ SearchCodeTool.java
│     └─ FileToolsDemo.java
└─ test/java/com/agent/
   ├─ AgentTest.java              # legacy class tests
   ├─ agent/AgentTest.java
   └─ tool/
      ├─ ListFilesToolTest.java
      ├─ ReadFileToolTest.java
      ├─ SearchCodeToolTest.java
      └─ ToolRegistryTest.java
```

The principal packages are `com.agent`, `com.agent.agent`, `com.agent.llm`, and `com.agent.tool`.

### Recent five commits

```text
9183be8 docs: improve project documentation and runtime experience
c066a5b lab03: implement react agent with tool calling
360650c lab03: add glm client
8d59cc9 lab02: file tools
20b1974 bootstrap project
```

Both `master` and `resume-upgrade`, plus the annotated `lab03-complete` tag, point to `9183be8` at audit time.

## 2. Architecture

### Actual runtime flow

```text
User / stdin
    ↓
com.agent.Main
    ↓
com.agent.agent.Agent.run(input)
    ↓                                    ↑
LLMClient.chat(history, tool definitions)│
    ↓                                    │
GlmClient → GLM chat-completions API      │
    ↓                                    │
LLMResponse(content, toolCalls)           │
    ├─ no tool calls → final text         │
    └─ tool calls                         │
           ↓                              │
       ToolRegistry                       │
           ↓                              │
       private Tool adapters              │
           ↓                              │
       ListFilesTool / ReadFileTool / SearchCodeTool
           ↓
       String observation → Message.tool(...)
           └──────────────────────────────┘
```

There is no `ToolResult` type. Tool execution results and tool errors are plain strings stored as `tool` messages.

### Core class responsibilities

| Type | Actual responsibility |
| --- | --- |
| `com.agent.Main` | Constructs the current agent, GLM client, and file-tool registry; reads stdin; recognizes `clear`; prints final answers. |
| `com.agent.agent.Agent` | Owns message history and the five-step ReAct-style loop; calls the LLM, dispatches tool calls, records observations, and returns a final answer. |
| `com.agent.Agent` | Legacy Lab01 hello/echo business class. It remains tested but is not used by the current `Main`. |
| `LLMClient` | Abstraction consumed by the agent. Defines `chat(messages)` and a tools-aware overload whose default delegates to the original method. |
| `GlmClient` | OkHttp/Jackson implementation of GLM chat completions, including tool serialization and tool-call parsing. |
| `Message` | Immutable transport/history record for `system`, `user`, `assistant`, and `tool` messages, with JSON names for `tool_calls` and `tool_call_id`. |
| `LLMResponse` | Immutable normalized response holding nullable content and an immutable list of parsed `ToolCall` values. |
| `ToolCall` | Immutable `id`, `name`, and JSON arguments string returned by the model. |
| `ToolDefinition` | Name, description, and JSON-schema parameter map supplied to the model. |
| `Tool` | Unified interface exposing name, description, parameter schema, and `execute(String arguments)`. |
| `ToolRegistry` | Ordered registration, lookup, schema export, and execution; also supplies private adapters around the three Lab02 tools. |
| `ListFilesTool` | Recursively lists regular files as sorted paths relative to a root. |
| `ReadFileTool` | Reads one UTF-8 regular file with Java NIO. |
| `SearchCodeTool` | Recursively searches text lines, returns relative file, 1-based line, and trimmed content; skips selected binary extensions. |
| `FileToolsDemo` | Standalone direct demonstration of the three raw file tools; it is separate from the LLM agent runtime. |

## 3. Current Agent Loop

The implemented loop is in `com.agent.agent.Agent.run(String)`.

1. **User input:** `Main` reads one line with `BufferedReader`. Except for `clear`, it passes that string to `Agent.run`.
2. **History:** `Agent.run` appends `Message.user(input)` to a mutable in-memory `List<Message>`. The constructor has already inserted one system message.
3. **LLM request:** each iteration calls `llmClient.chat(List.copyOf(history), toolRegistry.definitions())`. The copy prevents the client from mutating the list itself, although individual records are already immutable.
4. **Tool schemas:** `ToolRegistry.definitions()` maps registered tools to `ToolDefinition`. `GlmClient.buildRequestBody` serializes each as GLM/OpenAI-style `{ "type": "function", "function": { "name", "description", "parameters" } }` and includes the array under `tools`.
5. **Model response:** `GlmClient.parseResponse` reads `choices[0].message.content` and `choices[0].message.tool_calls`.
6. **Tool-call parsing:** for each returned call, it reads `id`, `function.name`, and `function.arguments` into a `ToolCall`.
7. **Assistant history:** the response content and all calls are appended through `Message.assistant(content, toolCalls)`.
8. **Tool execution:** if calls exist, the agent executes each call in response order using `ToolRegistry.execute(call.name(), call.arguments())`.
9. **Observation:** success output or a generated error string is appended as `Message.tool(call.id(), result)`.
10. **Next turn:** the loop calls the LLM again with the expanded history and the same tool definitions.
11. **Final answer:** when a response has no tool calls, its content is returned. Null content is normalized to an empty string.
12. **Loop bound:** `MAX_ITERATIONS` is 5. After five tool-bearing iterations without a final answer, `run` throws `IllegalStateException`.
13. **Tool error:** `executeTool` catches any `RuntimeException` and converts it to an observation string beginning with an error description. This permits the model to continue.
14. **Invalid tool call:** an unknown name, malformed JSON arguments, missing required argument detected by an adapter, or wrapped file I/O error becomes a runtime exception in the registry and is therefore converted to an observation. There is no separate validation/retry policy or typed invalid-call state.

This is a real multi-step loop, but “ReAct” is used in the architectural sense of repeated model action and environment observation. The program does not require or store a distinct natural-language `Thought` field.

## 4. Tool Inventory

| Tool | Class / adapter | Input | Output | Function | Tested |
| --- | --- | --- | --- | --- | --- |
| `list_files` | `ListFilesTool` via `ToolRegistry.ListFilesAdapter` | JSON object; optional string `path`, default `.` | JSON array of relative path strings | Recursively list sorted regular files | Yes |
| `read_file` | `ReadFileTool` via `ToolRegistry.ReadFileAdapter` | JSON object; required string `path` | Raw UTF-8 file content | Read one regular text file | Yes |
| `search_code` | `SearchCodeTool` via `ToolRegistry.SearchCodeAdapter` | JSON object; required `keyword`, optional `path` | JSON array of match objects | Search all regular, non-excluded files by line | Yes |

Raw Lab02 tool classes retain their original typed Java APIs (`Path`, `List`, records). Private registry adapters translate LLM JSON strings into those APIs without modifying the raw tools.

`SearchCodeTool` skips `.class`, `.png`, `.jpg`, `.jar`, and `.DS_Store`. Its binary detection is filename-based rather than content-based. Per-file `IOException` is silently skipped so other files remain searchable.

## 5. Capability Matrix

| Capability | Status | Implementation / evidence |
| --- | --- | --- |
| List files | Implemented | `ListFilesTool.listFiles`; registered as `list_files`. |
| Read file | Implemented | `ReadFileTool.readFile`; registered as `read_file`. |
| Search code | Implemented | `SearchCodeTool.searchCode`; registered as `search_code`. |
| Write file | Missing | No tool or API writes file content. |
| Edit file | Missing | No edit or replacement tool exists. |
| Create file | Missing | No file-creation tool exists. |
| Delete file | Missing | No deletion tool exists. |
| Run shell command | Missing | No process/shell tool exists. |
| Run Maven test | Missing | Maven is run externally by developers/tests, not by the agent. |
| Run arbitrary test | Missing | No test-execution abstraction or tool. |
| Git diff | Missing | No Git integration. |
| Git status | Missing | No Git integration. |
| Apply patch | Missing | No patch representation or application tool. |
| Multi-step tool use | Implemented | `Agent.run` loops; `AgentTest` verifies `search_code` followed by `read_file`. |
| Retry | Partial | The model can react in another iteration after a tool observation; no HTTP retry/backoff or explicit retry policy exists. |
| Error recovery | Partial | Runtime tool failures become observations; LLM/network/parsing failures and max-step exhaustion terminate the run. |
| Planning | Missing | No plan data model, plan step, planner, or plan executor exists. |
| Reflection | Missing | No critic/reflection pass or self-review state exists. |
| Trajectory logging | Partial | In-memory history captures messages, calls, and observations; there is no dedicated persisted trajectory, step metadata, timing, or event log. |
| Structured evaluation | Missing | No benchmark, evaluator, metrics, scoring, or result dataset exists. |

## 6. Function Calling

### Definition and request format

Each `Tool` supplies `name()`, `description()`, and `parameters()`. The default parameter schema is an object with no properties and `additionalProperties: false`; each file-tool adapter overrides it with its concrete JSON schema. `ToolRegistry.definitions()` produces `ToolDefinition` records.

`GlmClient` sends these definitions in the request body:

```json
{
  "model": "glm-4-flash",
  "messages": ["...serialized Message objects..."],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "...",
        "parameters": {
          "type": "object",
          "properties": { "path": { "type": "string" } },
          "required": ["path"],
          "additionalProperties": false
        }
      }
    }
  ]
}
```

The API key is read from `GLM_API_KEY`, not embedded in source. The endpoint, model, HTTP proxy (`127.0.0.1:7897`), and timeouts are currently code constants/configuration in `GlmClient`.

### Response and argument handling

`GlmClient` expects `choices[0].message`. Calls are parsed from `tool_calls[*].id`, `tool_calls[*].function.name`, and `tool_calls[*].function.arguments`. The arguments remain a JSON string until an adapter calls `ToolRegistry.parseArguments`, which uses Jackson `readTree`, rejects non-object roots, and reports malformed JSON with `IllegalArgumentException`.

Unknown tool names cause `ToolRegistry.getTool` to throw `IllegalArgumentException`. Adapter-level required fields are checked and also produce `IllegalArgumentException`. Raw `IOException` from a file tool is wrapped in `IllegalStateException`. In an agent run all of these runtime failures are converted into tool observations.

The advertised JSON schema is not a general runtime schema validator. Extra properties, JSON value types, and all schema constraints are not systematically validated; adapters retrieve selected fields with Jackson conversion methods. Also, absent response fields may be converted by Jackson’s `asText()` to empty strings rather than rejected early. Both are technical debt.

## 7. Context / Memory

| Concern | Current status |
| --- | --- |
| Conversation history | Present as an in-memory `List<Message>` per `Agent` instance. |
| User messages in history | Yes. |
| Assistant content in history | Yes. |
| Assistant tool calls in history | Yes, serialized under `tool_calls`. |
| Tool observations in history | Yes, as `role=tool` with `tool_call_id`. |
| Clear operation | `Agent.clearHistory()` clears all entries and restores only the configured system message; `Main` exposes this through the exact command `clear`. |
| Context-length control | Missing. The full history is sent every time. |
| History compression/summarization | Missing. |
| Session abstraction | Missing. An `Agent` instance is effectively one volatile session, but there is no named/session data model or persistence. |
| Long-term memory | Missing. Ordinary process-local message history is not long-term memory. |

Because file content can be returned as a tool observation and then resent on every later turn, long sessions or large files can grow prompt size without a bound.

## 8. ReAct / Planning Status

The current pattern is a **ReAct-style Agent Loop using function calling**:

- The model selects an action in the form of a tool call.
- The runtime executes it against the environment.
- The result is added as an observation.
- The model reasons again over the updated state.
- Multiple tool calls and multiple LLM rounds are supported.

It is not Plan-and-Execute: there is no explicit plan, plan schema, planner phase, step state, or executor working through a plan. It is not a fixed workflow because the model dynamically decides whether and which registered tool to invoke.

| Feature | Status | Evidence |
| --- | --- | --- |
| Function-calling loop | Implemented | `Agent.run` + `LLMClient.chat(..., tools)`. |
| Explicit plan | Missing | No plan type or planning phase. |
| Dynamic replanning | Missing | Later tool choices are dynamic, but no explicit plan exists to revise. |
| Reflection | Missing | No critic/self-review step. |
| Retry policy | Missing | No declared conditions, attempts, delay, or backoff. |

## 9. Error Handling

| Failure | Current behavior | Risk |
| --- | --- | --- |
| Missing `GLM_API_KEY` | `GlmClient` construction throws `IllegalStateException`. | CLI cannot start; message is direct but no configuration abstraction exists. |
| LLM transport/API failure | OkHttp/Jackson `IOException` propagates through `Agent.run` and `Main`. | Current CLI invocation terminates; no retry, fallback, or friendly recovery. |
| HTTP non-2xx | Error body is printed, then an `IOException` containing status/body is thrown. | Possible sensitive/provider detail in console; no status-specific handling or retry. |
| Blank HTTP body | `IOException`. | Run terminates. |
| JSON response parse failure | Jackson `IOException` propagates. | Run terminates; no raw-response quarantine or typed parse error. |
| Missing/empty `choices` | Explicit `IOException`. | Correctly rejected, but run terminates. |
| Malformed tool-call fields | Missing fields can become empty strings; no complete validation before constructing `ToolCall`. | Error is delayed to dispatch or may create an invalid observation correlation. |
| Tool not found | Registry throws `IllegalArgumentException`; agent converts it to an error observation. | Recoverable by model, but error format is untyped. |
| Invalid JSON arguments | Registry throws `IllegalArgumentException`; agent converts it to an error observation. | Recoverable; no automatic schema repair or structured error code. |
| Schema/type mismatch | Only adapter-specific checks apply; no full JSON-schema validation. | Invalid or coerced values may reach implementation. |
| File missing/not regular | Raw tool throws; adapter wraps; agent converts to observation. | Agent can recover, but exception categories are lost in a string. |
| Individual search-file read failure | `SearchCodeTool` silently skips that file. | Partial/incomplete result is indistinguishable from a complete search. |
| Tool execution runtime exception | Caught by agent and returned as observation. | Loop survives; broad catch may hide implementation defects. |
| Neither answer nor tool call | With no tool call, nullable content is normalized to `""` and returned as the final answer. | Silent empty success rather than an explicit invalid-model-response error. |
| Maximum steps reached | `IllegalStateException` after five tool-bearing iterations. | Prevents infinite loops but terminates without a structured partial result. |
| Path escapes workspace | Registry resolves and normalizes paths but does not verify `startsWith(root)`; absolute paths may replace root. | Tools can inspect files outside the intended workspace. This is a P0 safety issue. |

## 10. Test Status

### Actual command and result

Command executed during this audit:

```text
mvn "-Dmaven.repo.local=<repo-root>\.m2\repository" test
```

Actual Maven result on 2026-09-23:

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Test class | Count | Category and coverage |
| --- | ---: | --- |
| `com.agent.AgentTest` | 6 | Unit tests for the legacy hello/echo class, including null/blank/case/trim branches. |
| `ListFilesToolTest` | 1 | Tool test: recursion, regular files, relative paths, no directory result. |
| `ReadFileToolTest` | 3 | Tool tests: exact UTF-8 content, missing file, directory rejection. |
| `SearchCodeToolTest` | 5 | Tool tests: root/subdirectory matches, line/content/path, multiple matches, exclusions, no-match behavior. |
| `ToolRegistryTest` | 4 | Registry tests: three registrations, lookup, execution, missing tool behavior. |
| `com.agent.agent.AgentTest` | 5 | Agent tests: ordinary answer, live GLM read call, multi-tool sequence, tool failure recovery, history clearing. |

The agent suite contains a deterministic `FakeLLMClient`; four agent behaviors do not require an online model. One integration test is guarded by `@EnabledIfEnvironmentVariable(named="GLM_API_KEY", matches=".+")`. In this audit environment it ran rather than being skipped, which explains the roughly 10-second agent test time.

There are no dedicated `GlmClient` unit tests using a mock HTTP server. Consequently request serialization, non-2xx behavior, response variants, malformed JSON, and timeouts are not deterministically covered. The environment-gated live test is useful as a smoke test but makes coverage differ between machines and depends on API/network/proxy availability.

## 11. Does the Project Currently Have Agent Characteristics?

### Tool Use

Yes. The model can select and invoke three registered tools through a generic interface.

### Function Calling

Yes. Schemas are sent to GLM and response tool calls are parsed and dispatched.

### Observation

Yes. Tool output and caught tool failures are added to history as `tool` messages tied to the call ID.

### Multi-step Agent Loop

Yes. The model is called repeatedly after observations, with an upper bound of five iterations. The test suite demonstrates a `search_code` → `read_file` sequence.

### ReAct-style behavior

Yes, at the action/observation loop level. It does not expose or persist a separate chain-of-thought field, which is neither required nor desirable for proving tool-using behavior.

### Trajectory

A complete conversational trajectory exists transiently in `Agent.history`: system/user/assistant, assistant tool calls, and tool observations. It is not saved as a structured trajectory artifact and lacks step IDs, timestamps, duration, token usage, tool status, final status, or reward.

### Evaluation

No true benchmark or metric framework exists. JUnit verifies selected behaviors, but there is no task dataset, evaluator, success-rate computation, tool-selection accuracy, argument accuracy, invalid-call rate, average-step metric, latency/cost tracking, or failure taxonomy report.

## 12. Technical Debt

### P0 — address before broader capabilities

1. **No workspace path boundary**
   - Problem: `ToolRegistry.FileToolAdapter.resolve` normalizes a resolved path but does not require it to remain beneath the configured root. Absolute paths and traversal can escape.
   - Impact: unintended local file disclosure today; potentially severe filesystem damage if write tools are later added.
   - Files: `src/main/java/com/agent/tool/ToolRegistry.java`.
   - Extension impact: blocks safe addition of editing, deletion, shell, or autonomous operation.

2. **Online integration test is part of the ordinary suite when a key is present**
   - Problem: a normal `mvn test` can call a paid/external service and depends on network, proxy, model availability, and nondeterministic model behavior.
   - Impact: flaky CI, variable cost/latency, and different executed coverage depending on environment.
   - Files: `src/test/java/com/agent/agent/AgentTest.java`, `src/main/java/com/agent/llm/GlmClient.java`.
   - Extension impact: undermines reproducible evaluation unless separated from deterministic tests.

### P1 — important for a credible Coding Agent

1. **Read-only capability ceiling:** no edit/write/patch, shell, Maven/test, or Git tools. Files: `com.agent.tool` package. This prevents completing coding tasks and iterative debugging.
2. **No structured trajectory and observability:** history is only in memory; no event model, persistence, step timing, token/cost data, or failure labels. Files: `com.agent.agent.Agent`, `com.agent.llm.Message`.
3. **No deterministic LLM protocol tests:** HTTP request/response handling is not isolated behind a mock server. File: `GlmClient`; test package has no client test.
4. **No transport resilience:** no retry/backoff, rate-limit handling, cancellation, or provider-independent error model. File: `GlmClient`.
5. **Unbounded context and file payloads:** complete history and complete file contents are repeatedly sent; no truncation, chunking, budgeting, or compression. Files: `Agent`, `ReadFileTool`, registry adapters.
6. **Incomplete validation:** declared schemas are not enforced generically and returned tool calls are weakly validated. Files: `ToolRegistry`, `GlmClient`.
7. **Hard-coded runtime configuration:** endpoint, model, and localhost proxy are embedded in `GlmClient`. This reduces portability and makes tests/environment setup brittle.

### P2 — maintainability and clarity

1. **Two classes named `Agent`:** the legacy `com.agent.Agent` and current `com.agent.agent.Agent` can confuse readers and imports. Both are real and tested; the former is not on the current CLI path.
2. **Stringly typed tool boundary:** arguments and outputs/errors are strings. There is no typed `ToolResult`, error code, metadata, or success flag.
3. **Private registry adapters combine concerns:** registration, schemas, parsing, path resolution, serialization, and adaptation live in one class.
4. **Console output instead of logging abstraction:** status/error output uses standard streams and offers only the `GLM_DEBUG` switch for HTTP status.
5. **Search incompleteness is silent:** unreadable files are skipped without returning warnings or coverage metadata.

## 13. Resume-project Gap Analysis

### Agent capability

The project can inspect a repository but cannot change or validate it. A resume-grade Coding Agent needs, at minimum, controlled patch/edit operations, build/test execution, result capture, and a loop that uses compiler/test feedback to revise changes. Git diff/status support would make changes reviewable. These should be introduced only after workspace and command safety boundaries exist.

### Safety

Missing safeguards include:

- canonical workspace confinement for every file operation;
- symlink/reparse-point policy;
- command allowlist and argument validation;
- time/resource/output limits for subprocesses;
- protection against destructive commands and secret files;
- approval policy for high-impact actions;
- explicit read/write permissions per tool.

The current read tools are not confined to the workspace, so the safety foundation is not yet sufficient for autonomous modification or shell access.

### Observability

The runtime needs structured per-step events containing request/response IDs, selected tool, validated arguments, outcome, duration, error category, token usage where available, and termination reason. Logs should redact secrets and large content. A persisted trajectory should be separable from console UX.

### Evaluation

JUnit is necessary but is not an agent benchmark. A credible evaluation layer should add a versioned set of isolated repository tasks and deterministic checks, then report:

- task success rate;
- tool-selection accuracy;
- argument accuracy;
- invalid tool-call rate;
- average/maximum steps;
- latency and token/cost usage;
- recovery rate after tool failure;
- failures grouped by taxonomy.

### Experiments

There is currently no experiment framework. Once deterministic tasks and metrics exist, meaningful comparisons could include a single-call baseline, current function-calling ReAct loop, explicit planning, error-aware retry, and ablations such as removing search or history compression. Without the same dataset and evaluator, such comparisons would be anecdotes rather than experiments.

## 14. Agentic RL Readiness

### Current state/action data availability

| RL/runtime element | Current status | Existing representation |
| --- | --- | --- |
| `state` | Partial | In-memory message history plus fixed registered tools. |
| `action` | Partial | Assistant final text or parsed tool calls. |
| `tool_call` | Present | `ToolCall(id, name, arguments)`. |
| `observation` | Present | String-valued `Message.tool`. |
| `next_state` | Implicit | History after appending assistant/tool messages; not emitted as an explicit transition. |
| `trajectory` | Partial | Recoverable from process-local history, but not a versioned/persisted schema. |
| `final_result` | Partial | `Agent.run` returns a string; termination reason and metadata are absent. |
| `reward` | Missing | No scorer, reward field, or evaluator. |

### Readiness rating: Low

The runtime has useful primitives—injectable policy client, discrete tool actions, observations, bounded episodes, and a clear-history reset—but it is not currently an RL environment. It does not emit transitions, persist trajectories, guarantee deterministic resets, isolate workspaces, calculate rewards, or expose an environment protocol. Therefore its Agentic RL readiness is **Low**, despite having a reasonable foundation for later extraction.

### Recommended future separation

The Java side should remain an execution/runtime service responsible for safe workspace state, tool schemas, action validation, deterministic reset, tool execution, and structured transition events. A Python training/evaluation pipeline should own datasets, policy/trainer integration, rollout orchestration, rewards, replay/trajectory storage, and metrics. They should communicate through a versioned, provider-neutral protocol (for example HTTP/gRPC plus JSON/Protobuf) with explicit messages for reset, observe, act, step, terminate, and artifact retrieval. This avoids coupling Java tool execution to a particular Python RL library or model provider.

No RL functionality is implemented today; the above is an upgrade boundary, not a claim about current features.

## 15. Recommended Next Milestones

The milestones below are recommendations, not existing functionality.

1. **Safety foundation:** enforce canonical workspace confinement, define symlink behavior, add adversarial path tests, and separate configuration from source.
2. **Deterministic protocol coverage:** add mock-HTTP tests for GLM request/schema serialization, tool-call response parsing, malformed responses, non-2xx cases, and timeouts; move live GLM smoke tests to an explicit integration-test profile.
3. **Structured runtime events:** introduce a typed result/error and trajectory/event model with termination reasons, timing, and redaction; retain the simple CLI as a presentation layer.
4. **Minimal coding actions:** add safe patch/edit operations with preview/diff and workspace restrictions; avoid unrestricted write primitives initially.
5. **Controlled execution:** add allowlisted Maven/test commands with timeout, output cap, working-directory confinement, and no implicit shell interpolation.
6. **Iterative debugging loop:** let the agent inspect a task, patch, run targeted tests, consume observations, and revise within explicit step and resource budgets.
7. **Evaluation harness:** create isolated fixture repositories, deterministic task checks, baseline policies, metrics, and machine-readable result reports.
8. **Context management:** add content limits, file chunking, observation summarization, and token budgeting while preserving audit references to source artifacts.
9. **Only then explore planning/RL:** compare current ReAct against planning/retry baselines using the same benchmark; expose the runtime through a versioned environment API before connecting a Python training pipeline.

## 16. Audit Conclusion

This is currently a functional, tested, read-only Java ReAct-style repository assistant—not yet a general Coding Agent. Its strongest assets are the clean LLM abstraction, real function calling, bounded multi-step tool loop, adapter-based reuse of file tools, and deterministic fake-client tests. Its principal blockers are workspace safety, lack of code-changing/execution capabilities, absent structured trajectories/evaluation, and limited resilience/context control.

It is suitable for continued evolution into a formal Agent project, provided safety and deterministic evaluation precede broader autonomy.
