# Design Notes

## Model boundary

`Agent` depends on `LLMClient`, not a provider class. `LlmClientFactory` reads and validates environment-backed configuration, then creates GLM, DeepSeek, or generic OpenAI-compatible clients. GLM implements the streaming interface. DeepSeek uses the shared OpenAI-compatible transport through a streaming facade and sends its non-thinking setting. Generic `openai-compatible` mode currently exposes the non-streaming path. All supported adapters parse structured tool calls; assistant prose that merely resembles JSON is not dispatched as a tool call. `MODEL_PROXY`, when set, configures the provider HTTP client.

## Environment and tool catalog

`ToolRegistry` is the registered tool catalog: names, schemas, and dispatch. `AgentEnvironment` is the runtime boundary for tool definitions, execution, workspace identity, availability, and verification. `LocalWorkspaceEnvironment` binds that interface to the local filesystem and existing registry. Before each model request, only available tools are advertised; availability is checked again at execution time to reject stale calls. The initial production availability policy is narrow: `run_maven_test` is available only with a safe regular root `pom.xml`.

## Workspace safety and typed outcomes

Workspace tools resolve relative paths through `WorkspacePathResolver`, which rejects absolute paths, traversal, and detected symlink escapes. Writes are constrained to supported operations; new-file creation is non-overwriting, and text replacement uses temporary-file/atomic-move behavior where supported. There is no arbitrary shell or delete tool in the normal CLI set.

Tools return `ToolResult` values containing success, output or typed error, and metadata. Expected failures—such as invalid arguments, text mismatch, unavailable capability, or test failure—become observations in the Agent loop instead of being treated as successful tool calls. This gives orchestration and recovery logic machine-readable evidence.

## Working memory

The interactive layer keeps bounded, session-scoped workspace context such as the active task, explicit target, discovered files, verified observations, typed failures, and last mutation. It supports short follow-ups and reduces context loss across mode/session turns. This is not semantic retrieval, RAG, or a general long-term memory service. The CLI can manually save and restore the existing profile histories and working context in a versioned workspace-local snapshot; nothing is automatically saved. The `memory-v1` benchmark compares structured and legacy context conditions and should not be confused with a separate production memory service.

Each provider request can carry server-reported token usage when present. `ModelRequestTelemetry` also records exact request payload byte size and message/tool counts. Missing usage stays unknown; payload bytes and character counts are not token estimates, and the CLI does not estimate currency cost.

`RequestContextSelector` is a default-off, deterministic request-copy filter controlled by `AGENT_REQUEST_CONTEXT_MANAGEMENT_ENABLED=true` (or its JVM property). It may omit only exact duplicate older internal workspace-context messages and identical, successful, fully paired read-only discovery exchanges before the current turn. Original Agent history and saved snapshots remain intact. It is not general history compression; full file snapshots, edits, verification feedback, uncertain groups, and current-turn messages are preserved.

## Planning

The normal CODE profile defaults to `REACTIVE`. `PLAN_EXECUTE` adds a separate bounded plan step and at most one replan. `ADAPTIVE` applies a deterministic heuristic before model execution to select reactive or plan-execute behavior. Both planning modes are opt-in through `PLANNING_MODE` and remain experimental; planning output is guidance, not verified workspace state. There is no multi-agent planner.

## Verification and repair

Mutation success means text was written, not that the program is correct. After mutation, the runtime rereads affected content and invokes an applicable local verifier. Java in a Maven workspace uses controlled Maven verification when the file is within a known Maven source scope; standalone or out-of-scope Java uses temporary-output `javac` verification when the source scope can be established. Python, JavaScript, C, and C++ syntax checks depend on locally installed interpreters/compilers. Outcomes distinguish `PASS`, `FAIL`, `UNAVAILABLE`, and not-applicable cases. A verifier can only establish the checks it actually ran; it does not prove semantic correctness.

When verification returns `FAIL`, guided repair produces a structured `RepairDirective` from the observed file and bounded diagnostics. The Agent must perform a fresh read before another repair mutation, and a recovery is counted only when a later verification passes. Recovery is bounded and does not provide transactional rollback; an invalid final workspace may remain after a failed run.

## Completion consistency

For a clearly mutation-required CODE turn, a model final answer is not accepted until at least one successful workspace mutation (`ToolResult.success` and `changed=true`) occurs in that same `Agent.runWithTrajectory` call. This current-turn guard is distinct from verification: mutation existence confirms an edit happened, verification checks only the syntax/build condition it actually runs, and neither proves semantic task correctness or completion of every requested target. Read-only, advice, and explicitly non-mutating requests do not require a mutation; ambiguous requirements retain the existing completion behavior.

## Evaluation

The benchmark runner copies fixtures into isolated workspaces, executes the Agent, then applies an independent evaluator to workspace state and trajectory evidence. The evaluator does not accept the model's final prose as proof of completion. Protocols record their own metrics and limitations; paired DEV results are descriptive and repeated task-runs are not independent observations. See [Evaluation](EVALUATION.md) and the source protocols under `benchmark/`.
