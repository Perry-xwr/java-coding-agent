# Phase 2 Structured Runtime and Trajectory Report

## 1. Summary

Phase 2 replaces the runtime's string-only tool-result boundary with immutable typed results and adds a structured trajectory for every Agent run. Each run now has a UUID, ordered steps, parsed and raw tool arguments, typed tool outcomes, per-tool duration, whole-run duration, final answer, completion state, and an explicit termination reason.

The existing `Agent.run(String)` API and CLI output remain compatible. A new `runWithTrajectory(String)` API exposes the structured result. Conversation history and trajectory are separate: `clearHistory()` changes future conversation context but cannot mutate an already returned immutable trajectory.

Optional JSON persistence uses Jackson, truncates persisted tool output, and redacts the current GLM API key, bearer-token values, and `GLM_API_KEY` assignments. Runtime observations sent to the LLM remain complete; persistence truncation does not change Agent behavior.

No write, edit, patch, delete, shell, Maven-execution, Git, benchmark, planning, evaluator, reward, or RL capability was added.

## 2. Architecture Changes

Before:

```text
Tool.execute(arguments)
  -> String
  -> success output or ad-hoc failure text
  -> Message.tool(...)
```

After:

```text
Tool.execute(arguments)
  -> ToolResult
       success / output / errorCode / errorMessage / metadata
  -> AgentStep
  -> text observation serialization
  -> Message.tool(...)

Agent.runWithTrajectory(task)
  -> AgentRunResult
       finalAnswer
       AgentTrajectory
         runId / ordered steps / termination / durations
```

`ToolRegistry.execute` now converts unknown tools and uncaught tool exceptions into typed failures. File adapters classify Java NIO failures while preserving Phase 1 workspace confinement. Successful observations remain their original output text for provider compatibility. Failed observations are serialized as JSON containing `success`, `errorCode`, and `errorMessage` before being appended to LLM history.

The console remains a user interface, not the trajectory source of truth. `Main` still calls `Agent.run` and prints only the final answer.

## 3. New Runtime Types

| Type | Responsibility |
| --- | --- |
| `ToolResult` | Immutable structured success or failure returned by every `Tool`. |
| `ToolErrorCode` | Stable classification for currently produced tool errors. |
| `AgentActionType` | Distinguishes `TOOL_CALL`, `FINAL_ANSWER`, and `ERROR` steps. |
| `AgentStep` | One ordered action, including arguments, typed result, timing, final answer, or runtime error. |
| `TerminationReason` | Explains why a run ended: final answer, step limit, or LLM failure. |
| `AgentTrajectory` | Immutable run-level record and ordered step sequence. |
| `AgentRunResult` | Compatibility-friendly return object containing final answer and trajectory. |
| `TrajectoryJsonWriter` | Optional UTF-8, pretty-printed, truncated, redacted JSON persistence. |

`ToolResult` fields:

```text
boolean success
String output
ToolErrorCode errorCode
String errorMessage
Map<String, Object> metadata
```

Success requires a non-null output and no error fields. Failure requires an error code and message; Phase 3 permits optional diagnostic output for recoverable process failures. Metadata is copied into an immutable map.

`AgentStep` fields:

```text
int stepIndex
AgentActionType actionType
String toolName
String toolCallId
String rawArguments
Map<String, Object> arguments
ToolResult toolResult
String finalAnswer
String errorMessage
long startedAtEpochMs
long durationMs
```

`AgentTrajectory` fields:

```text
String runId
String task
List<AgentStep> steps
String finalAnswer
TerminationReason terminationReason
boolean completed
Boolean taskSuccess
long startedAtEpochMs
long durationMs
```

`completed` means the runtime reached a normal final-answer termination. `taskSuccess` remains `null` because no evaluator exists and a model answer is not proof that the requested task was correct.

## 4. Trajectory Schema

The following shape is produced by the actual Java records for a one-tool run; timestamps, duration, UUID, and content vary per execution:

```json
{
  "runId": "3fef8216-3ec6-48ae-93df-23ee091ee803",
  "task": "读取README.md",
  "steps": [
    {
      "stepIndex": 1,
      "actionType": "TOOL_CALL",
      "toolName": "read_file",
      "toolCallId": "read-1",
      "rawArguments": "{\"path\":\"README.md\"}",
      "arguments": {
        "path": "README.md"
      },
      "toolResult": {
        "success": true,
        "output": "# Java Agent CLI ...",
        "errorCode": null,
        "errorMessage": null,
        "metadata": {}
      },
      "finalAnswer": null,
      "errorMessage": null,
      "startedAtEpochMs": 1790184000000,
      "durationMs": 2
    },
    {
      "stepIndex": 2,
      "actionType": "FINAL_ANSWER",
      "toolName": null,
      "toolCallId": null,
      "rawArguments": null,
      "arguments": {},
      "toolResult": null,
      "finalAnswer": "README 已读取。",
      "errorMessage": null,
      "startedAtEpochMs": 1790184000006,
      "durationMs": 0
    }
  ],
  "finalAnswer": "README 已读取。",
  "terminationReason": "FINAL_ANSWER",
  "completed": true,
  "taskSuccess": null,
  "startedAtEpochMs": 1790184000000,
  "durationMs": 8
}
```

Raw arguments remain available even when JSON parsing fails. The parsed argument map is empty in that case, while the corresponding `ToolResult` records `INVALID_ARGUMENTS`.

## 5. Error Model

Current `ToolErrorCode` values are limited to errors the runtime can currently produce:

| Code | Source |
| --- | --- |
| `WORKSPACE_VIOLATION` | Traversal, absolute path, or real-path escape rejected by Phase 1 confinement. |
| `FILE_NOT_FOUND` | Java NIO `NoSuchFileException`. |
| `ACCESS_DENIED` | Java NIO `AccessDeniedException`. |
| `INVALID_ARGUMENTS` | Malformed/non-object JSON, missing required value, invalid path syntax, or an argument exception from a tool. |
| `TOOL_NOT_FOUND` | Requested tool name is not registered. |
| `TOOL_EXECUTION_ERROR` | Other I/O or unexpected runtime failure during tool execution. |

Run-level `TerminationReason` values are:

- `FINAL_ANSWER`: model returned no tool calls; `completed=true`.
- `MAX_STEPS`: the configured maximum LLM iterations completed without a final answer; `completed=false`. Phase 3 raises the limit to ten to support edit/test recovery loops.
- `LLM_ERROR`: `LLMClient.chat` threw `IOException`; `completed=false`.

Tool failures do not automatically terminate a run. They are stored in the tool step, serialized into the LLM observation, and allow the next model turn to recover.

## 6. Persistence

Persistence is **disabled by default**. `Agent.run` and `runWithTrajectory` do not write files or change CLI behavior. A caller enables persistence explicitly:

```java
AgentRunResult result = agent.runWithTrajectory(task);
new TrajectoryJsonWriter(Path.of("trajectories"))
        .write(result.trajectory());
```

This writes UTF-8, pretty-printed JSON to:

```text
trajectories/<runId>.json
```

The output directory is caller-configurable. Unit tests use `@TempDir`, not the project directory.

Persisted `ToolResult.output` is limited to 10,000 characters by default. The writer redacts the current `GLM_API_KEY` value when available, bearer-token values, and textual `GLM_API_KEY=...` or `GLM_API_KEY:...` assignments. HTTP headers and environment maps are not part of the trajectory schema. Runtime tool output and the LLM observation are not truncated by the writer.

## 7. Backward Compatibility

- `Agent.run(String)` remains public, returns the final-answer string, and is still used unchanged by `Main`.
- `Agent.run` delegates to `runWithTrajectory` and preserves prior exceptional behavior: max iterations throw `IllegalStateException`, and LLM I/O failure throws `IOException`.
- `Main`, `Message`, `ToolCall`, `LLMClient`, `GlmClient`, provider request format, and CLI console output were not changed.
- `clearHistory()` still resets only conversation history. Previously returned trajectory records are immutable and unaffected.
- The three raw Lab02 tools retain their original APIs and behavior. Only their registry adapters now wrap output in `ToolResult`.
- `Tool.execute` and `ToolRegistry.execute` intentionally return `ToolResult`; custom Tool implementations must adopt the typed contract.

## 8. Tests

Baseline before Phase 2:

```text
Tests run: 37, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

Final ordinary offline suite:

```text
Tests run: 50, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

- Passed: 48
- Failed: 0
- Errors: 0
- Skipped: 2
- Skip reasons: the opt-in live GLM integration test and the unavailable Windows symbolic-link capability test.
- External GLM calls in ordinary suite: 0.

New deterministic coverage includes:

- successful `ToolResult`;
- `FILE_NOT_FOUND`;
- `WORKSPACE_VIOLATION`;
- `TOOL_NOT_FOUND`;
- unexpected tool execution exception;
- final-answer-only trajectory;
- one-tool trajectory with parsed arguments and result;
- ordered multi-tool trajectory;
- tool failure followed by recovery and final answer;
- explicit `MAX_STEPS` incomplete termination;
- explicit `LLM_ERROR` termination;
- JSON persistence fields and error code;
- persistence-only output truncation and bearer-token redaction.

All existing list/read/search tests and all Phase 1 workspace security tests continue to pass. The symlink test remains conditionally skipped on this Windows environment as documented in Phase 1.

## 9. Remaining Limitations

- The Agent remains read-only.
- There is no evaluator, so `taskSuccess` is always unknown (`null`).
- There is no benchmark, success-rate metric, reward, or RL integration.
- There is no safe edit/write/patch capability.
- There is no shell or Maven/test execution tool.
- Successful LLM decision latency is not a separate step field; whole-run timing includes it, while tool duration is recorded explicitly.
- Trajectories are not saved automatically and there is no retention/indexing policy.
- Output redaction is intentionally basic, not a general secret-detection engine.
- `GlmClient` still lacks deterministic mock-HTTP protocol tests.

## 10. Next Phase

Phase 3: Safe Editing + Controlled Maven/Test Execution + Iterative Coding Loop

This report does not implement Phase 3.
