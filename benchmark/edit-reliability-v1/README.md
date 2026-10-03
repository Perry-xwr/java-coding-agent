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

## Live Evaluation: Attempt 1 and Attempt 2

### Attempt 1

Attempt 1 was aborted before Agent or provider startup because the temporary launcher passed `benchmark/edit-reliability-v1/fixtures` as the fixture root even though manifest entries already begin with `fixtures/`. The resulting `fixtures/fixtures/...` lookup failed. Provider requests were 0; there are no model results or trajectories from Attempt 1. Its raw launcher record is preserved and is not merged with Attempt 2.

### Attempt 2 identity and execution

- Runtime seam commit: `bb27b02`; V1.8-A base: `670bfd5`
- Benchmark commit: `4d774ee`
- Provider/model: GLM `glm-4-flash`, explicit HTTP proxy `127.0.0.1:7897`
- Two rounds each attempted 24 serial conditions, with fresh workspace/Agent/budget per condition and 12 requests maximum; observed maximum was 10 requests. No request cap was hit.
- Round 1 order: `REREAD_ONLY → POST_EDIT_VERIFY` per task (`R_V`). Round 2 reversed the order (`V_R`).
- The two rounds completed with 0 runner-reported infrastructure errors. A post-run audit below found Python verifier infrastructure failures that the harness had incorrectly recorded as verifier `FAIL`, so the protocol stop rule was not applied in Round 2.

### Protocol validity warning

**Attempt 2 is not a valid paired evaluation and must not support a comparative conclusion.** On Windows, the Python verifier's `PYTHONPYCACHEPREFIX` mirrored the long benchmark workspace path beneath a temporary directory. Several `py_compile` invocations failed with `FileNotFoundError` while writing the temporary `.pyc.<id>` file. `CommandVerifier` represented the nonzero process exit as `FAIL` rather than `UNAVAILABLE`, so these local verifier failures were misclassified as source syntax failures and were not counted by the infrastructure stop rule.

Four `POST_EDIT_VERIFY` conditions show this cache-path failure: `py_simple_insert` in each round, plus `py_structural_insert` and `py_modify_existing` in Round 2. Thus Round 2 had three condition-level local-verifier infrastructure failures and should have stopped at the third. A direct no-bytecode `compile()` diagnostic confirmed that the Round 2 Python files labelled syntax-invalid in `py_structural_insert` and `py_modify_existing` are syntactically valid; their independent evaluator results are contaminated too. The benchmark code, evaluator, and raw results remain frozen and unchanged; these post-hoc findings are recorded here rather than silently rewriting results.

The runner's raw per-condition outcomes below are retained for audit only. Because Round 2 breached the stop rule, do not treat the two-round aggregate or mode difference as valid evidence. No attempt was made to retry a condition or rerun either round.

### Raw round summaries (harness-recorded; protocol-invalid)

| Round / mode | Task success | Evaluator final syntax invalid | False success | Syntax false-success | Requests | Tool steps | Reads | Mutations | Verifier PASS / FAIL |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Round 1 `REREAD_ONLY` | 1/12 | 5/12 | 7 | 4 | 62 | 47 | 19 | 14 | 0 / 0 (14 N/A observations) |
| Round 1 `POST_EDIT_VERIFY` | 2/12 | 6/12 | 2 | 0 | 74 | 55 | 23 | 15 | 6 / 9 |
| Round 2 `REREAD_ONLY` | 1/12 | 4/12 | 8 | 4 | 68 | 55 | 18 | 15 | 0 / 0 (15 N/A observations) |
| Round 2 `POST_EDIT_VERIFY` | 1/12 | 3/12 | 2 | 0 | 59 | 37 | 13 | 8 | 4 / 4 |

Across raw records, `REREAD_ONLY` logged 2/24 successes, 9 final syntax-invalid outcomes, 15 evaluator-defined false-success outcomes, 8 syntax false-success outcomes, and 130 requests. `POST_EDIT_VERIFY` logged 3/24 successes, 9 final syntax-invalid outcomes (including the contaminated Python classifications noted above), 4 false-success outcomes, 0 syntax false-success outcomes, and 133 requests. These totals are provided for reproducibility, not as a valid mode comparison.

### Raw language breakdown

Each cell is success / evaluator syntax-invalid / syntax false-success / requests / verifier FAIL. The Python verifier contamination applies to the affected `POST_EDIT_VERIFY` rows.

| Language | `REREAD_ONLY` (8 runs) | `POST_EDIT_VERIFY` (8 runs) |
|---|---|---|
| Python | 1 / 3 / 3 / 39 / 0 | 1 / 4 / 0 / 48 / 7 |
| JavaScript | 0 / 1 / 1 / 45 / 0 | 1 / 1 / 0 / 39 / 1 |
| Java | 1 / 5 / 4 / 46 / 0 | 1 / 4 / 0 / 46 / 5 |

### Raw task-category breakdown

Each mode/category contains six task-runs (three languages by two rounds). Each cell is success / evaluator syntax-invalid / syntax false-success / requests / verifier FAIL.

| Category | `REREAD_ONLY` | `POST_EDIT_VERIFY` |
|---|---|---|
| `SIMPLE_INSERT` | 0 / 3 / 3 / 24 / 0 | 0 / 3 / 0 / 29 / 5 |
| `STRUCTURAL_INSERT` | 0 / 4 / 4 / 31 / 0 | 0 / 3 / 0 / 33 / 3 |
| `MODIFY_EXISTING` | 2 / 0 / 0 / 36 / 0 | 3 / 1 / 0 / 33 / 1 |
| `TWO_STEP_EDIT` | 0 / 2 / 1 / 39 / 0 | 0 / 2 / 0 / 38 / 4 |

### False-success and repair observations

Raw `REREAD_ONLY` trajectories include syntax false-success examples in `py_structural_insert` and `py_two_step_edit` (Round 1), and `java_simple_insert` (both rounds): mutation → reread → final answer, followed by independent syntax failure. These are raw examples only; the full details are in the trajectory JSON.

`POST_EDIT_VERIFY` recorded 13 verification FAIL events and 10 PASS events across 23 attempts. Two conditions had a subsequent mutation after a verification failure, but neither reached a final verification PASS; recorded recovery count is 0. The Python cache-path failures are infrastructure, not reliable source diagnostics. A representative actual source failure is Round 1 `py_two_step_edit`: Python reported `IndentationError`, a second insertion was attempted, and verification failed again. No verifier-detected repair recovery was observed.

### Request cost and infrastructure

The raw total is 263 provider requests: 130 `REREAD_ONLY` and 133 `POST_EDIT_VERIFY` (difference +3 overall; 5.42 and 5.54 average requests per condition). In the 13 paired task-rounds where `POST_EDIT_VERIFY` logged no verifier FAIL, its request-count difference versus the paired baseline ranged from -4 to +4 and summed to -9; this stochastic sample does not isolate a causal request overhead. Token usage and currency cost are `UNAVAILABLE`.

Attempt 2 had no provider transport failures, no request-cap hits, and no detected proxy outage. It did have the post-hoc local verifier issue described above. Attempt 1's fixture lookup failure remains separate and is not merged.

## Limitations and interpretation

- Attempt 2 breached its frozen infrastructure stop rule because Python cache-path failures were misclassified. Its paired comparison is invalid; raw counters are retained only for audit.
- The protocol contains only 12 DEV tasks repeated twice; task-runs are repeated observations, not independent samples.
- Only one provider/model was used; behavior is stochastic.
- Live tasks cover Python, JavaScript, and standalone Java. C/C++ checks are deterministic-only.
- The evaluator checks expected text and syntax/compilation, not general semantic correctness.
- `REREAD_ONLY` is an ablation shim over the current Agent, not a historical binary.
- No rollback exists; verifier feedback can block a success claim but does not guarantee repair or a valid final workspace.
- Token/currency cost was unavailable, and no statistical significance is claimed.

## Conservative conclusion

Attempt 2 produced no valid evidence for whether post-edit verification improves outcomes, because its Round 2 should have stopped after three condition-level Python verifier infrastructure failures. The raw logs show some invalid-source final answers in `REREAD_ONLY` and verifier failures in `POST_EDIT_VERIFY`, but the latter includes cache-path errors and zero completed verification recoveries. Do not claim that verification solved editing, guaranteed correctness, or improved success. Preserve the run as an infrastructure-limited evaluation record; any future evaluation would require a separately authorized protocol after correcting the Python verifier infrastructure.

## V1.8-C infrastructure correction

After invalid Attempt 2, the Python syntax verifier and edit-reliability condition infrastructure stop propagation were corrected. This changes benchmark infrastructure semantics; Attempt 1/2 raw records remain historical and unchanged. No new live result or mode-effect conclusion is available. Any future attempt requires a new runtime/protocol freeze and explicit authorization.
