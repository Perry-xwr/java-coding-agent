# V1.10 Tool Availability Benchmark

## Research Question

Under the same coding Agent, task, model, full tool catalog, verifier, repair/planning behavior,
workspace fixture, and provider budget, does environment-aware advertisement reduce inappropriate
Maven attempts without harming tasks that legitimately benefit from Maven or unrelated standalone
work?

This is a DEV-only protocol with 12 new tasks and no TEST split. Live results below are descriptive;
they are not a holdout or a statistical study.

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

## Live DEV Evaluation

Freeze identities were captured before the run for benchmark commit `73d9129`. The same 12 DEV tasks
were repeated in two rounds (24 task-runs per mode, not 24 independent tasks), with `GLM` / `glm-4-flash`
through the explicitly configured local HTTP proxy. Round 1 used `LEGACY_ALL_TOOLS → ENVIRONMENT_AWARE`
per task; Round 2 reversed that order. No task was retried. There were 0 condition-level infrastructure
failures and no request cap was hit.

| Round | Legacy success | Aware success | Legacy requests | Aware requests |
|---|---:|---:|---:|---:|
| 1 | 6/12 | 6/12 | 72 | 79 |
| 2 | 4/12 | 4/12 | 80 | 79 |
| Total | 10/24 | 10/24 | 152 | 158 |

The primary metric, no-safe-root-POM `environment_mismatch_attempt`, was 0 in both modes. There were
also 0 mismatch executions, 0 aware-mode typed mismatch rejections, 0 MissingProject failures, 0 POM
creations, and 0 availability transitions. Therefore the run observed no model-initiated invalid Maven
attempt to avoid; it cannot establish a reduction in mismatch attempts.

Legacy advertised Maven on all 152/152 provider turns across all workspace classes. Aware mode advertised
Maven on 66/66 Maven-Java turns, and 0 turns in standalone Java, Python, or JavaScript workspaces
(66/158 turns overall). It still executed Maven when selected: Legacy made 15 appropriate Maven attempts
(2 passes, 13 failures); Aware made 13 (3 passes, 10 failures). All 28 Maven attempts were in safe Maven
workspaces. No `MissingProjectException` occurred.

By workspace class across both rounds, task success was:

| Workspace class | Legacy | Aware | Requests: Legacy / Aware |
|---|---:|---:|---:|
| Standalone Java | 5/8 | 4/8 | 46 / 39 |
| Maven Java | 2/8 | 3/8 | 66 / 66 |
| Python + JavaScript controls | 3/8 | 3/8 | 40 / 53 |

All task success totals were equal at 10/24 per mode. Paired outcomes were both pass in 9 pairs, both
fail in 13, Legacy-only pass in 1, and Aware-only pass in 1. Java category counts (six repeated
task-runs per category and mode) were: SIMPLE_METHOD_EDIT 5/6 Legacy vs 4/6 Aware;
SIGNATURE_CHANGE 2/6 vs 2/6; STRUCTURAL_INSERT 1/6 vs 1/6; TWO_STAGE_EDIT 2/6 vs 3/6. These small,
stochastic differences do not support a causal success or request-savings claim.

Across all 48 conditions there were 310 provider requests (Legacy 152, mean 6.33/condition; Aware 158,
mean 6.58/condition). By subset: standalone Java 46/39, Maven Java 66/66, controls 40/53. No condition
hit the 12-request cap; 11 trajectories ended at the 10-step Agent limit. Token usage and monetary cost
were unavailable from provider telemetry. There were 46 typed tool failures and 1 repeated failure;
these are observed runtime/task outcomes, not infrastructure failures. No target drift or unexpected
POM creation occurred.

No legacy mismatch, aware stale-call rejection, or dynamic-promotion example occurred, so none is
presented as a live representative case. Deterministic protocol tests separately confirm the expected
legacy execution, aware rejection, dynamic promotion, and invocation-time accounting behavior.

### Conservative conclusion

On this small repeated DEV set, environment-aware advertisement correctly hid Maven outside Maven
workspaces while preserving advertisement and observed execution in Maven workspaces. However, neither
mode produced any no-POM Maven attempt, and both modes had 10/24 task-runs succeed. The experiment
therefore demonstrates the configured availability mechanism in operation, but does not show that it
reduced observed mismatch attempts, improved task success, or reduced provider requests. No clear
unrelated-regression difference was observed on the small Python/JavaScript control subset (3/8 success
in each mode), but this is not evidence of general non-regression.

Artifacts are under `benchmark-runs/tool-availability-v1/73d9129/`; the raw directory is Git-ignored.
It includes pre-live freeze identities, both round summaries and condition metrics, sanitized
trajectories, and isolated workspaces.

## Known Limitations

- This is a small exploratory DEV task set, not a hidden holdout or a statistical study.
- All tasks use the existing CODE profile; the experiment isolates tool availability, not router quality.
- The legacy condition intentionally restores full tool advertisement/dispatch only inside the benchmark
  adapter and is not a second production policy.
- Tool choice still depends on model behavior; advertisement does not guarantee correct selection.
- `MissingProjectException` recognition is diagnostic-string based and may vary across Maven versions.
- Independent source validation checks syntax/build behavior, not semantic equivalence beyond explicit
  file-content assertions.
- The frozen paired runner API iterates the enum order Legacy then Aware. To honor the specified reversed
  Round 2 order without altering the frozen benchmark, this evaluation used a temporary ignored
  `target/` orchestrator around the same frozen per-condition runtime harness, provider limits, and
  infrastructure stop rule. The orchestration was not committed; the round artifacts preserve its
  outcomes and order, but this is a reproducibility limitation to address before a future rerun.
- Token usage and monetary cost were unavailable from provider telemetry.
