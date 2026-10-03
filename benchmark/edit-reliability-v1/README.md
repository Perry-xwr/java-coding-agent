# Edit Reliability Benchmark v1

## Research Question

Under the same task, model, tools, workspace fixture, and provider-request budget, does adding generic post-edit verification change syntax-valid outcomes, false-success frequency, repair behavior, and execution cost for incremental edits to existing files?

## Modes

- `REREAD_ONLY`: benchmark-only baseline representing the pre-generic-verifier behavior. Runtime reread still occurs; the no-op verifier returns `NOT_APPLICABLE`. The synthetic verification observation is hidden, and the changed convergence message is mapped to its pre-V1.8 wording.
- `POST_EDIT_VERIFY`: uses the normal production verifier pipeline and exposes PASS/FAIL/UNAVAILABLE observations to the Agent.

Both modes use the production CODE profile, tools, Agent loop, and a per-task cap of 12 provider requests. Each condition gets a fresh Agent, workspace, progress state, and budget. Provider clients are injected; there is no default real provider in this protocol.

## Languages and Tasks

The DEV-only manifest contains 12 unique tasks: four each for Python, JavaScript, and standalone Java. Each language has one `SIMPLE_INSERT`, `STRUCTURAL_INSERT`, `MODIFY_EXISTING`, and `TWO_STEP_EDIT` task. Standalone Java is intentional so existing Maven verification does not confound this generic-verifier comparison. C/C++ verifier behavior is covered by deterministic unit tests but is not part of this live-language subset because the development machine lacks gcc/g++.

## Success Contract

Task success requires conversational completion, the required final workspace content, and an independently verified syntax/compile PASS. The evaluator reads final workspace files and runs a fresh verifier itself; it does not trust final prose, Agent verification observations, or `AgentProgress` state. An unavailable independent verifier is recorded as infrastructure error, not as PASS.

## Independent Syntax Evaluation

The evaluator uses a separate `PostEditVerificationService` invocation against the final isolated workspace. It shares only the safe process-runner utility and built-in verifier definitions with runtime verification. The final check is independent of the runtime's stored evidence and occurs after the Agent finishes.

## False-success Metric

`false_success` means the Agent conversationally completed but the required workspace outcome or independent syntax result failed. `syntax_false_success` is the syntax-invalid subset.

## Repair Metrics

The protocol records runtime verification PASS/FAIL/UNAVAILABLE/NOT_APPLICABLE counts, successful mutation attempts after a verification failure, and whether a later PASS recovered the failed verification. Provider requests, tool steps, reads, and mutations are recorded alongside these values; verification is not assumed to reduce request cost.

## Provider Budget

Both conditions receive the same 12-request cap per task. No extra budget is granted for repair. Each run uses an explicitly injected `LLMClient`; all automated protocol tests use scripted clients.

## Known Limitations

- Only 12 development tasks and three local languages are covered; results are descriptive, not statistically conclusive.
- The evaluator checks required textual outcomes and syntax/compilation, not semantic correctness beyond those assertions.
- The REREAD_ONLY condition is an ablation shim over the current Agent runtime: rereads and existing convergence behavior remain, while generic verifier feedback is suppressed.
- Local verifier availability and compiler behavior depend on the host environment.
- No provider/model comparison is defined here.

## Live Results

**NO LIVE RESULTS YET.** This phase creates the deterministic harness and protocol only; it does not call a live provider or run DEV tasks.
