# V1.9 Repair Reliability Benchmark

## Research question

Under the same task, model, tools, verifier, workspace, provider-request budget, Agent iteration limit, and evaluator, compare ordinary verification feedback with structured verification-guided repair after a real verification failure.

## Modes

- `VERIFICATION_ONLY`: retains post-edit verification, bounded failure diagnostics, and the completion guard. It does not enable structured `RepairDirective`, mandatory repair fresh-read enforcement, or repair capability context.
- `GUIDED_REPAIR`: uses the current V1.9 guided repair policy, including failure context, a required fresh read before repair mutation, and subsequent re-verification.

The CLI and normal runtime default remain guided. The benchmark injects only a generic `VerificationRepairPolicy`; it does not duplicate the Agent loop.

## Task set

This is a new 12-task DEV set: four Python, four JavaScript, and four standalone Java tasks. Each language has one `NESTED_BLOCK_EDIT`, `FUNCTION_SIGNATURE_EDIT`, `STRUCTURAL_INSERT`, and `TWO_STAGE_CHANGE` task. Every initial fixture is expected to pass its language syntax/compiler verifier. Task definitions and fixtures are new and are not copied from V1.8.

## Repair eligibility and recovery

A condition is repair-eligible only when its trajectory contains at least one actual `VerificationStatus.FAIL`. Runs with no FAIL are neither repair successes nor repair failures and are excluded from the recovery denominator. `UNAVAILABLE` and `NOT_APPLICABLE` do not create repair eligibility.

Recovery is per file: that same file must have a verification FAIL, then a later successful mutation, then a later verification PASS. A mutation to another file, final answer without re-verification, or another FAIL does not count as recovery.

At the condition level, `recoveredRun` is true only when the run was repair-eligible and no failed file remains unresolved at the end; report recovery rates using only repair-eligible runs as the denominator.

## Fresh-read and diagnostic metrics

The protocol records fresh reads after failure, repair attempts without a fresh read, mutations after a required fresh read, `REPAIR_REQUIRES_FRESH_READ` rejections, repair mutations, repeated failures, unresolved failures, diagnostic-location presence, whether a repair targeted the failed file, and later PASS/FAIL. These are observable events, not claims about model reasoning.

## Capability and tool-selection metric

Standalone Java workspaces advertise the environment's `javac` capability. `run_maven_test` calls in a workspace without `pom.xml` are counted as `tool_selection_mismatch`; the tool call is not prohibited and this metric is not a task success criterion.

## Success contract and independent evaluator

Success requires conversational completion, expected workspace content and target constraints, and an independent final syntax/compiler PASS. The evaluator reads the final workspace and invokes its verifier independently; it does not trust repair events, trajectory verification PASS events, or final prose. `FAIL` is a source-validation failure; `UNAVAILABLE` is infrastructure unavailability and is not treated as invalid source.

## Provider budget and isolation

Each condition receives the same maximum of 12 provider requests and the unchanged `Agent.MAX_ITERATIONS` limit of 10. Every mode/task condition has a new Agent, conversation, provider budget, workspace, progress state, repair state, and verification state. Conditions run sequentially. There is no live-provider default: a runner caller must explicitly inject a provider factory.

## Infrastructure semantics

No fault injection is used in the live protocol. A repair denominator includes only conditions where the real verifier actually reports FAIL. Verifier `UNAVAILABLE`, filesystem/process failures, or provider transport errors are recorded as infrastructure issues and must not be interpreted as source failures. Protocol tests use scripted providers and deterministic verifier doubles only.

## Known limitations

This small development set measures observable repair behavior, not broad language competence or semantic correctness. Syntax/compiler PASS does not establish that a requested change is semantically correct; expected-content checks are intentionally narrow. Provider/model variance, tool selection, and verifier availability can affect outcomes. Paired observations are descriptive, not evidence of statistical significance.

**NO LIVE RESULTS YET**
