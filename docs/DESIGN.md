# Design Notes

## Model boundary

`Agent` depends on `LLMClient`, not a provider class. `LlmClientFactory` reads and validates environment-backed configuration, then creates either the default GLM client or an OpenAI-compatible chat-completions adapter. Tool schemas and tool-call results are represented at the shared runtime boundary. GLM currently supports the CLI streaming path; the OpenAI-compatible adapter is non-streaming.

## Environment and tool catalog

`ToolRegistry` is the registered tool catalog: names, schemas, and dispatch. `AgentEnvironment` is the runtime boundary for tool definitions, execution, workspace identity, availability, and verification. `LocalWorkspaceEnvironment` binds that interface to the local filesystem and existing registry. Before each model request, only available tools are advertised; availability is checked again at execution time to reject stale calls. The initial production availability policy is narrow: `run_maven_test` is available only with a safe regular root `pom.xml`.

## Workspace safety and typed outcomes

Workspace tools resolve relative paths through `WorkspacePathResolver`, which rejects absolute paths, traversal, and detected symlink escapes. Writes are constrained to supported operations; new-file creation is non-overwriting, and text replacement uses temporary-file/atomic-move behavior where supported. There is no arbitrary shell or delete tool in the normal CLI set.

Tools return `ToolResult` values containing success, output or typed error, and metadata. Expected failures—such as invalid arguments, text mismatch, unavailable capability, or test failure—become observations in the Agent loop instead of being treated as successful tool calls. This gives orchestration and recovery logic machine-readable evidence.

## Working memory

The interactive layer keeps bounded, session-scoped workspace context such as the active task, explicit target, discovered files, verified observations, typed failures, and last mutation. It supports short follow-ups and reduces context loss across mode/session turns. It is not persistent memory, semantic retrieval, or RAG. The `memory-v1` benchmark compares this structured condition with a legacy context condition; benchmark ablation modes should not be confused with an additional production memory service.

## Planning

The normal CODE profile defaults to `REACTIVE`. `PLAN_EXECUTE` adds a separate bounded plan step and at most one replan. `ADAPTIVE` applies a deterministic heuristic before model execution to select reactive or plan-execute behavior. Both planning modes are opt-in through `PLANNING_MODE` and remain experimental; planning output is guidance, not verified workspace state. There is no multi-agent planner.

## Verification and repair

Mutation success means text was written, not that the program is correct. After mutation, the runtime rereads affected content and invokes an applicable local verifier. Java in a Maven workspace uses controlled Maven verification; standalone Java and supported script/native languages use local syntax/compiler checks when available. Outcomes distinguish `PASS`, `FAIL`, `UNAVAILABLE`, and not-applicable cases. A verifier can only establish the checks it actually ran; it does not prove semantic correctness.

When verification returns `FAIL`, guided repair produces a structured `RepairDirective` from the observed file and bounded diagnostics. The Agent must perform a fresh read before another repair mutation, and a recovery is counted only when a later verification passes. Recovery is bounded and does not provide transactional rollback; an invalid final workspace may remain after a failed run.

## Evaluation

The benchmark runner copies fixtures into isolated workspaces, executes the Agent, then applies an independent evaluator to workspace state and trajectory evidence. The evaluator does not accept the model's final prose as proof of completion. Protocols record their own metrics and limitations; paired DEV results are descriptive and repeated task-runs are not independent observations. See [Evaluation](EVALUATION.md) and the source protocols under `benchmark/`.
