# V1.10 Tool Availability Benchmark

## Research Question

Under the same coding Agent, task, model, full tool catalog, verifier, repair/planning behavior,
workspace fixture, and provider budget, does environment-aware advertisement reduce inappropriate
Maven attempts without harming tasks that legitimately benefit from Maven or unrelated standalone
work?

This is a DEV-only protocol with 12 new tasks and no TEST split. **NO LIVE RESULTS YET.** The current
implementation provides an injectable runner and deterministic offline protocol tests; this document
does not claim a provider evaluation has been performed.

## Modes

- `LEGACY_ALL_TOOLS`: every tool definition from the full `ToolRegistry` is exposed. Calls dispatch
  directly to the same registered tool implementation, including Maven's original no-project error
  path. It does not apply the local environment availability filter.
- `ENVIRONMENT_AWARE`: uses the production `LocalWorkspaceEnvironment`; a safe regular root `pom.xml`
  controls `run_maven_test` advertisement and execution-time availability checks.

Both modes retain normal tool path safety, post-edit verification, planning, repair, and the same Agent
loop. The ablation adapter is benchmark-only; the production CLI default remains environment-aware.

## Workspace Classes and Task Set

There are four unique standalone Java fixtures, four unique Maven Java fixtures, two Python controls,
and two JavaScript controls. Each Java class has one task in each category: `SIMPLE_METHOD_EDIT`,
`SIGNATURE_CHANGE`, `STRUCTURAL_INSERT`, and `TWO_STAGE_EDIT`. Controls exercise ordinary source edits
without Maven applicability. Every task has an independent expected-file assertion and one allowed
mutation target. Standalone tasks disallow adding a root `pom.xml`.

See `manifest.json` for the complete task IDs, fixture paths, instructions, target contracts, and
budgets. Every task/mode condition receives a fresh workspace, Agent, history, environment, provider
budget, repair state, and verification state.

## Invocation-time Availability

Each provider turn records whether `run_maven_test` was advertised and a contemporaneous safe-root-POM
snapshot. Each Maven invocation records another snapshot immediately before dispatch. Metrics retain
these ordered snapshots; final workspace state is never used to rewrite an earlier invocation.

`environment_mismatch_attempt` means that the model requested `run_maven_test` while that invocation's
snapshot showed no safe root `pom.xml`. Legacy mode may execute this call through the original Maven
tool and receive Maven's no-project failure. In aware mode the stale call is rejected as
`TOOL_UNAVAILABLE_IN_ENVIRONMENT` and must not start Maven. A rejection is not task success.

The protocol also records Maven advertisements/turns, attempts, process executions, successes/failures,
appropriate attempts, `MissingProjectException`/no-POM failures, typed rejections, and availability
transitions. Creating a safe root POM during a run is observed as a false-to-true transition, and only
later calls can be appropriate Maven attempts.

## Dynamic Capability and Unexpected POM Creation

Availability is recomputed at each provider request and invocation. If a task starts without a POM and
the Agent creates one, aware mode can advertise Maven on the next request. Standalone task contracts
record `pom_created_during_run`; because those tasks disallow project conversion, creating the POM is
also target drift. No inference about model intent is made.

## Success Contract and Independent Evaluator

Success requires conversational completion, all required final file content, no disallowed workspace
changes, compliance with the task's target constraints, and an independent final syntax/build check.
The evaluator does not treat trajectory claims such as `verification PASS` as evidence of task success.
Java standalone, Python, and JavaScript files are checked independently with their local syntax/compiler
validators. Maven Java workspaces receive a fresh independent project test run. Maven-fixture preflight
uses offline mode and must pass for all four Maven projects before a paired run can create any provider.

## Provider Budget and Infrastructure Semantics

Both conditions use the identical per-condition provider cap of 12 requests and `Agent.MAX_ITERATIONS`
of 10 steps. The full run uses a fresh provider for each condition. After three condition-level provider,
workspace, evaluator, verifier/build-environment, or runner infrastructure failures, execution stops.
An actual legacy `MissingProjectException` from a no-POM Maven call is a task/tool mismatch outcome, not
an infrastructure failure.

Run artifacts, when a caller executes the runner, belong under the gitignored `benchmark-runs/` tree.
The result writer stores condition metrics and sanitized trajectories only.

## Known Limitations

- This is a small exploratory DEV task set, not a hidden holdout or a statistical study.
- All tasks use the existing CODE profile; the experiment isolates tool availability, not router quality.
- The legacy condition intentionally restores full tool advertisement/dispatch only inside the benchmark
  adapter and is not a second production policy.
- Tool choice still depends on model behavior; advertisement does not guarantee correct selection.
- `MissingProjectException` recognition is diagnostic-string based and may vary across Maven versions.
- Independent source validation checks syntax/build behavior, not semantic equivalence beyond explicit
  file-content assertions.
- **NO LIVE RESULTS YET.**
