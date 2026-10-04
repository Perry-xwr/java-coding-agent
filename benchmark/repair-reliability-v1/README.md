# V1.9 Repair Reliability Benchmark

## Research question

Under the same task, model, tools, verifier, workspace, provider-request budget, Agent iteration limit, and evaluator, compare ordinary verification feedback with structured verification-guided repair after a real verification failure.

## Modes

- `VERIFICATION_ONLY`: retains post-edit verification, bounded failure diagnostics, and the completion guard. It does not enable structured `RepairDirective`, mandatory repair fresh-read enforcement, or repair capability context.
- `GUIDED_REPAIR`: uses the current V1.9 guided repair policy, including failure context, a required fresh read before repair mutation, and subsequent re-verification.

The CLI and normal runtime default remain guided. The benchmark injects only a generic `VerificationRepairPolicy`; it does not duplicate the Agent loop.

## Task set

This is a new 12-task DEV set: four Python, four JavaScript, and four standalone Java tasks. Each language has one `NESTED_BLOCK_EDIT`, `FUNCTION_SIGNATURE_EDIT`, `STRUCTURAL_INSERT`, and `TWO_STAGE_CHANGE` task. Every initial fixture is expected to pass its language syntax/compiler verifier. Task definitions and fixtures are new and are not copied from V1.8.

## Repair Eligibility

A condition is repair-eligible only when its trajectory contains at least one actual `VerificationStatus.FAIL`. Runs with no FAIL are neither repair successes nor repair failures and are excluded from the recovery denominator. `UNAVAILABLE` and `NOT_APPLICABLE` do not create repair eligibility.

## Recovery Definition

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

## Infrastructure Rules

No fault injection is used in the live protocol. A repair denominator includes only conditions where the real verifier actually reports FAIL. Verifier `UNAVAILABLE`, filesystem/process failures, or provider transport errors are recorded as infrastructure issues and must not be interpreted as source failures. Protocol tests use scripted providers and deterministic verifier doubles only.

## Known limitations

This small development set measures observable repair behavior, not broad language competence or semantic correctness. Syntax/compiler PASS does not establish that a requested change is semantically correct; expected-content checks are intentionally narrow. Provider/model variance, tool selection, and verifier availability can affect outcomes. Paired observations are descriptive, not evidence of statistical significance.

## Live DEV evaluation

The protocol was frozen before the live run. The runtime and benchmark were not changed after the
freeze, and the same 12 manifest tasks were run in both rounds (24 repeated task-runs per mode, not
24 independent tasks). Order was counterbalanced as specified: Round 1 ran
`VERIFICATION_ONLY → GUIDED_REPAIR`; Round 2 ran `GUIDED_REPAIR → VERIFICATION_ONLY`. All 48
conditions completed; neither round reached the three-infrastructure-failure stop threshold.

### Experimental commits and freeze identity

| Item | Commit |
|---|---|
| V1.9-A verification-guided repair base | `3f455f1` |
| Configurable repair-policy seam | `62c7d0b` — `refactor: make verification repair policy configurable` |
| Initial benchmark harness | `fcbab68` — `test: add repair reliability benchmark` |
| Pre-live freeze identity path correction | `e107ce1` |
| Pre-live verifier smoke workspace correction | `3c619a0` |
| Frozen live-evaluation HEAD | `3c619a0666bb0deafe60e0f575b3e5cd37d68811` |

The two small `e107ce1` / `3c619a0` corrections were completed and tested before live evaluation.
The final SHA-256 identity manifest covers 38 runtime, benchmark-protocol, manifest, documentation,
and fixture files at
`benchmark-runs/repair-reliability-v1/3c619a0666bb0deafe60e0f575b3e5cd37d68811/pre-live/freeze-identities.json`.
That file and all live result directories are ignored by Git. The API key was not recorded.

Live configuration: GLM `glm-4-flash`, explicit `MODEL_PROXY=http://127.0.0.1:7897`, 12-request
per-condition cap, and `Agent.MAX_ITERATIONS=10`. Each condition used a fresh fixture workspace,
Agent, progress state, budget, history, and verification/repair state. There were no live-provider
retries, no benchmark edits between rounds, and no request-cap hits.

All 12 initial fixtures passed their independent syntax/compiler verifier before the run. Python,
Node.js, and `javac` valid/invalid verifier smoke checks also passed. Provider transport and verifier
infrastructure failures: 0 in both rounds.

### Round summaries

| Round | Mode | Task success | Repair eligible | Recovered runs | Verification FAIL events | Provider requests |
|---|---|---:|---:|---:|---:|---:|
| 1 | VERIFICATION_ONLY | 1/12 | 7/12 | 0 | 16 | 81 |
| 1 | GUIDED_REPAIR | 3/12 | 4/12 | 1 | 6 | 77 |
| 2 | VERIFICATION_ONLY | 4/12 | 5/12 | 1 | 8 | 75 |
| 2 | GUIDED_REPAIR | 3/12 | 4/12 | 0 | 6 | 76 |

Each round completed all 24 mode/task conditions and recorded zero condition-level infrastructure
failures. Provider requests were 158 in Round 1 and 151 in Round 2.

### Per-task results

Cell format: `task success / repair eligibility / recovery / final syntax / requests`. `S` means task
success, `F` means task failure; `E` means at least one verifier FAIL made the run eligible, `—`
means not eligible; `R` means a recovered run, `—` means not recovered; final syntax is `P` (PASS),
`F` (FAIL), or `U` (UNAVAILABLE / NOT_APPLICABLE at final evaluation).

#### Round 1 (VERIFICATION_ONLY then GUIDED_REPAIR)

| Task | Category | VERIFICATION_ONLY | GUIDED_REPAIR |
|---|---|---|---|
| `py_nested_01` | NESTED_BLOCK_EDIT | F / E / — / F / 7 | S / — / — / P / 6 |
| `py_signature_01` | FUNCTION_SIGNATURE_EDIT | F / — / — / P / 5 | S / — / — / P / 5 |
| `py_insert_01` | STRUCTURAL_INSERT | F / E / — / F / 6 | F / — / — / P / 5 |
| `py_two_stage_01` | TWO_STAGE_CHANGE | F / — / — / P / 7 | F / — / — / P / 6 |
| `js_nested_01` | NESTED_BLOCK_EDIT | F / E / — / F / 9 | F / — / — / P / 7 |
| `js_signature_01` | FUNCTION_SIGNATURE_EDIT | F / — / — / P / 4 | F / — / — / P / 5 |
| `js_insert_01` | STRUCTURAL_INSERT | S / — / — / P / 5 | S / — / — / P / 5 |
| `js_two_stage_01` | TWO_STAGE_CHANGE | F / E / — / F / 10 | F / — / — / P / 6 |
| `java_nested_01` | NESTED_BLOCK_EDIT | F / — / — / P / 4 | F / E / — / F / 10 |
| `java_signature_01` | FUNCTION_SIGNATURE_EDIT | F / E / — / F / 8 | F / E / — / F / 7 |
| `java_insert_01` | STRUCTURAL_INSERT | F / E / — / F / 6 | F / E / — / F / 7 |
| `java_two_stage_01` | TWO_STAGE_CHANGE | F / E / — / U / 10 | F / E / R / P / 8 |

#### Round 2 (GUIDED_REPAIR then VERIFICATION_ONLY)

| Task | Category | VERIFICATION_ONLY | GUIDED_REPAIR |
|---|---|---|---|
| `py_nested_01` | NESTED_BLOCK_EDIT | S / — / — / P / 6 | S / — / — / P / 6 |
| `py_signature_01` | FUNCTION_SIGNATURE_EDIT | F / — / — / P / 3 | F / — / — / P / 5 |
| `py_insert_01` | STRUCTURAL_INSERT | F / E / — / F / 6 | F / — / — / P / 5 |
| `py_two_stage_01` | TWO_STAGE_CHANGE | F / — / — / P / 7 | F / — / — / P / 6 |
| `js_nested_01` | NESTED_BLOCK_EDIT | S / — / — / P / 5 | S / — / — / P / 5 |
| `js_signature_01` | FUNCTION_SIGNATURE_EDIT | F / E / — / F / 5 | F / — / — / P / 5 |
| `js_insert_01` | STRUCTURAL_INSERT | S / — / — / P / 5 | S / — / — / P / 5 |
| `js_two_stage_01` | TWO_STAGE_CHANGE | F / — / — / P / 7 | F / E / — / F / 7 |
| `java_nested_01` | NESTED_BLOCK_EDIT | F / — / — / P / 6 | F / — / — / P / 5 |
| `java_signature_01` | FUNCTION_SIGNATURE_EDIT | F / E / — / F / 10 | F / E / — / F / 10 |
| `java_insert_01` | STRUCTURAL_INSERT | F / E / — / F / 6 | F / E / — / F / 7 |
| `java_two_stage_01` | TWO_STAGE_CHANGE | S / E / R / P / 9 | F / E / — / F / 10 |

### Two-round descriptive aggregate

The denominator is 24 repeated task-runs per mode over the same 12 tasks repeated twice.

| Measure | VERIFICATION_ONLY | GUIDED_REPAIR |
|---|---:|---:|
| Task success | 5/24 (20.8%) | 6/24 (25.0%) |
| Repair-eligible runs | 12/24 | 8/24 |
| Recovered runs | 1 | 1 |
| Recovery among eligible runs | 1/12 (8.3%) | 1/8 (12.5%) |
| Verification attempts | 41 | 35 |
| Verification FAIL events | 24 | 12 |
| Verification PASS events | 16 | 23 |
| Verification UNAVAILABLE events | 0 | 0 |
| Final syntax PASS / FAIL / UNAVAILABLE | 13 / 10 / 1 | 17 / 7 / 0 |
| Completed false-success outcomes | 5 | 11 |
| Syntax false-success outcomes | 0 | 0 |
| Provider requests | 156 (6.50/run average) | 153 (6.375/run average) |
| Maximum requests in one condition | 10 | 10 |
| Request-cap hits (cap = 12) | 0 | 0 |
| Infrastructure-failed conditions | 0 | 0 |

The `falseSuccess` field means the Agent completed while the independent evaluator found the
workspace outcome or final syntax requirement unsatisfied. `syntaxFalseSuccess` specifically counts
completed runs with invalid final syntax; none occurred. There was one final `UNAVAILABLE` syntax
status in `VERIFICATION_ONLY`: the model created a `pom.xml` in a standalone-Java fixture, causing
the Java syntax verifier to return `NOT_APPLICABLE` because verification had switched to the Maven
path. This was model-created workspace state, not a verifier-infrastructure failure.

### Repair eligibility and recovery

Eligibility is an observed, post-treatment trajectory event: a condition is eligible only after at
least one actual verification FAIL. The number and timing of failures differed by mode (12 eligible
baseline runs versus 8 guided runs), so the conditional ratios above are descriptive, not causal
repair-effect estimates. A run with no FAIL is neither a repair success nor a repair failure.

Recovery required a same-file chain of `FAIL → later successful mutation → later PASS`, with no
unresolved failed file at the end of the run. One run recovered in each mode. The both-modes-eligible
paired subset contains six task/round pairs; it is a descriptive subset, not a new success metric:

| Round | Task | VERIFICATION_ONLY: recovered / final syntax / task success | GUIDED_REPAIR: recovered / final syntax / task success |
|---|---|---|---|
| 1 | `java_signature_01` | no / FAIL / no | no / FAIL / no |
| 1 | `java_insert_01` | no / FAIL / no | no / FAIL / no |
| 1 | `java_two_stage_01` | no / UNAVAILABLE / no | yes / PASS / no |
| 2 | `java_signature_01` | no / FAIL / no | no / FAIL / no |
| 2 | `java_insert_01` | no / FAIL / no | no / FAIL / no |
| 2 | `java_two_stage_01` | yes / PASS / yes | no / FAIL / no |

Within this six-pair subset each mode recovered one run. Final task outcome was one success for
`VERIFICATION_ONLY` and zero for `GUIDED_REPAIR`. These pairs share task and round but still reflect
different stochastic trajectories and execution order; no statistical test is appropriate here.

### Fresh-read behavior, repair mutations, and repeated failures

| Metric | VERIFICATION_ONLY | GUIDED_REPAIR |
|---|---:|---:|
| Explicit successful reads after a FAIL | 14 | 10 |
| Repair mutation attempts without a model-requested fresh read | 11 | 5 |
| `REPAIR_REQUIRES_FRESH_READ` guard rejections | 0 | 5 |
| Repair mutations after a fresh read | 5 | 5 |
| Repair mutations on failed files | 13 | 5 |
| Recovered failed files | 1 | 1 |
| Eligible runs with unresolved failure at end | 11 | 7 |
| Runs with at least two verification FAIL events | 5 | 2 |
| Additional repeated FAIL events beyond the first | 12 | 4 |
| Failures with a diagnostic location | 24 | 12 |

The guided guard visibly rejected five immediate repair attempts; subsequent repair mutations were
preceded by a fresh explicit read. That is evidence that the guard changed observed behavior, not
evidence by itself that repair became more successful. `VERIFICATION_ONLY` still retained generic
post-mutation rereads and completion safety; it disabled only the V1.9 repair-specific guard,
directive, and capability guidance.

### Task success, language, and category breakdowns

#### By language (8 repeated runs per mode/language)

| Language | Mode | Task success | Eligible / recovered / unresolved | Verification FAILs | Final syntax FAIL | Requests |
|---|---|---:|---:|---:|---:|---:|
| Python | VERIFICATION_ONLY | 1/8 | 3 / 0 / 3 | 3 | 3 | 47 |
| Python | GUIDED_REPAIR | 3/8 | 0 / 0 / 0 | 0 | 0 | 44 |
| JavaScript | VERIFICATION_ONLY | 3/8 | 3 / 0 / 3 | 10 | 3 | 50 |
| JavaScript | GUIDED_REPAIR | 3/8 | 1 / 0 / 1 | 1 | 1 | 45 |
| Standalone Java | VERIFICATION_ONLY | 1/8 | 6 / 1 / 5 | 11 | 4 | 59 |
| Standalone Java | GUIDED_REPAIR | 0/8 | 7 / 1 / 6 | 11 | 6 | 64 |

#### By task category (6 repeated runs per mode/category)

| Category | Mode | Task success | Eligible / recovered / unresolved | Verification FAILs | Requests |
|---|---|---:|---:|---:|---:|
| NESTED_BLOCK_EDIT | VERIFICATION_ONLY | 2/6 | 2 / 0 / 2 | 3 | 37 |
| NESTED_BLOCK_EDIT | GUIDED_REPAIR | 3/6 | 1 / 0 / 1 | 3 | 39 |
| FUNCTION_SIGNATURE_EDIT | VERIFICATION_ONLY | 0/6 | 3 / 0 / 3 | 7 | 35 |
| FUNCTION_SIGNATURE_EDIT | GUIDED_REPAIR | 1/6 | 2 / 0 / 2 | 4 | 37 |
| STRUCTURAL_INSERT | VERIFICATION_ONLY | 2/6 | 4 / 0 / 4 | 4 | 34 |
| STRUCTURAL_INSERT | GUIDED_REPAIR | 2/6 | 2 / 0 / 2 | 2 | 34 |
| TWO_STAGE_CHANGE | VERIFICATION_ONLY | 1/6 | 3 / 1 / 2 | 10 | 50 |
| TWO_STAGE_CHANGE | GUIDED_REPAIR | 0/6 | 3 / 1 / 2 | 3 | 43 |

All tasks targeted one source file; `TWO_STAGE_CHANGE` meant multiple requested changes within that
file, not a multi-file edit.

### Verification and false-success safety

Across both modes there were 76 verifier attempts: 36 FAIL events, 39 PASS events, and one
`NOT_APPLICABLE` event. The explicit `verificationUnavailable` event counter was zero. Final syntax
status was PASS in 30/48 workspaces, FAIL in 17/48, and UNAVAILABLE in one workspace as explained
above. No completed run had invalid final syntax (`syntaxFalseSuccess=0`). This supports the narrow
claim that the independent final syntax gate did not accept an invalid final source in these runs;
it does not establish semantic correctness.

### Tool-selection mismatch and Maven observations

The frozen metric records two `toolSelectionMismatch` observations. A trajectory-level audit against
the initial fixtures found four `run_maven_test` calls across three conditions, all in workspaces
that were standalone Java (no `pom.xml`) at task start. Three calls failed with Maven
`MissingProjectException`; one later call returned `BUILD SUCCESS` after the Agent had created a
`pom.xml`. The serialized mismatch metric checks whether a `pom.xml` exists after the Agent run,
which misses the earlier standalone state when the model creates one. For descriptive reporting, the
initial-fixture/trajectory count is therefore four mismatched calls in three conditions; the frozen
metric undercounts this behavior. This instrumentation limitation was discovered after the live
rounds and was not patched or used to change any result.

### Provider request cost and caps

| Mode | Round 1 | Round 2 | Total | Average per condition | Average among eligible | Average among non-eligible |
|---|---:|---:|---:|---:|---:|---:|
| VERIFICATION_ONLY | 81 | 75 | 156 | 6.50 | 7.67 (92/12) | 5.33 (64/12) |
| GUIDED_REPAIR | 77 | 76 | 153 | 6.375 | 8.25 (66/8) | 5.44 (87/16) |

Guided minus baseline was -3 requests overall (-0.125 per run), +0.58 requests per eligible run,
and +0.11 per non-eligible run. The observed aggregates do not show a consistent fixed request
overhead attributable to the policy; mode-specific trajectories and eligibility counts differ.
Maximum observed requests were 10 in either mode, below the per-condition cap of 12. No condition
hit its cap. Token usage and monetary cost are **UNAVAILABLE**; neither was supplied by the provider
in the captured artifacts. Elapsed time was not recorded per condition.

### Representative trajectories

- **Baseline direct-repair behavior:** Round 1 `js_two_stage_01` in `VERIFICATION_ONLY` had
  `FAIL → insert_before mutation` without a model-requested reread, then repeated edits and verifier
  FAILs until `MAX_STEPS`; it used 10 requests and ended with syntax FAIL.
- **Guided guard and recovery:** Round 1 `java_two_stage_01` in `GUIDED_REPAIR` had
  `FAIL → repair mutation rejected → read_file → successful repair mutation → AUTO_REREAD → PASS
  → final`. It recovered the file, but the task evaluator still failed the overall task contract.
- **Guided unresolved case:** Round 2 `java_signature_01` in `GUIDED_REPAIR` had repeated
  `REPAIR_REQUIRES_FRESH_READ` rejections, then repair edits and further verifier FAILs, reaching
  `MAX_STEPS` after 10 requests with syntax FAIL.

### Infrastructure, repeatability, and interpretation

There were zero provider-transport, workspace-runtime, runner, evaluator-verifier, or verifier
`UNAVAILABLE` infrastructure failures, and neither round stopped early. Source syntax FAIL and
Maven `MissingProjectException` were task/runtime behavior, not infrastructure stops. The one final
NOT_APPLICABLE Java verifier state followed the Agent-created `pom.xml`, not missing local verifier
tools. Both rounds had one recovery per mode in aggregate, but not consistently on the same
task/round pair; this is not a repeatable per-case repair outcome. The guard's rejection behavior
was observed in both rounds, while recovery/task-success behavior remained mixed.

### Limitations and conservative conclusion

- 12 manually designed DEV tasks, with the same tasks repeated twice
- one provider/model (`glm-4-flash`); stochastic model behavior
- repair eligibility is mode-dependent and trajectory-dependent (post-treatment)
- paired eligible subset is small and descriptive; no statistical significance
- syntax/compile validity does not imply semantic correctness
- only Python, JavaScript, and standalone Java; no C/C++ live tasks
- no transactional rollback
- one model-created Maven file changed a standalone fixture's verifier applicability
- tool-selection mismatch instrumentation used post-run rather than initial workspace state
- no provider token or reliable cost data

On this small repeated DEV set, `GUIDED_REPAIR` visibly changed some repair behavior through its
fresh-read guard, but the end-to-end and recovery observations were mixed: one run recovered in each
mode, and task success was 5/24 for `VERIFICATION_ONLY` versus 6/24 for `GUIDED_REPAIR`. Eligibility
itself depended on the observed trajectory, and the small sample, stochastic provider, and
tool-selection instrumentation limitation prevent a causal or superiority claim. The experiment
does not establish that guided repair improves recovery or overall success.

## Frozen implementation status

After the live freeze there were no production, benchmark, task, fixture, evaluator, metrics, or
protocol changes. After documentation updates, the only intended working-tree changes are the root
`README.md` and this file. Raw run outputs remain under the ignored
`benchmark-runs/repair-reliability-v1/3c619a0666bb0deafe60e0f575b3e5cd37d68811/` directory; no
result artifacts are committed.
