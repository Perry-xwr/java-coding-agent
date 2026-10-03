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

After invalid Attempt 2, the Python syntax verifier and edit-reliability condition infrastructure stop propagation were corrected. This changes benchmark infrastructure semantics; Attempt 1/2 raw records remain historical and unchanged. At that checkpoint no new live result was available. Attempt 3, documented below, is the first valid complete paired live evaluation after those fixes.

## Live Evaluation: Attempt 3

Attempt 3 started from a fresh run directory after the verifier and infrastructure-stop-rule corrections. The frozen runtime behavior and benchmark inputs were not changed during or after execution.

### Identity and protocol

- Runtime behavior identity: `c776d6d` (`fix: make verifier infrastructure failures explicit`)
- Benchmark runner / stop-rule identity: `fbc37ac` (`fix: enforce verifier infrastructure stop rules`)
- Prior task and fixture identity: `4d774ee`
- Provider/model: GLM `glm-4-flash`, explicit HTTP proxy `http://127.0.0.1:7897`
- Both rounds completed all 24 conditions, with zero infrastructure failures; no condition was retried.
- Round 1 used manifest task order and `REREAD_ONLY → POST_EDIT_VERIFY` (`R_V`). Round 2 used the same task order and `POST_EDIT_VERIFY → REREAD_ONLY` (`V_R`). Each condition had a fresh workspace, Agent, progress, provider budget, verifier state, and conversation, with a 12-request cap.
- The two-round comparison repeats the same 12 DEV tasks twice. Each mode therefore has 24 repeated task-runs, not 24 independent tasks.

### Round summaries

| Round / mode | Task success | Conversational completion | Final syntax PASS / FAIL / UNAVAILABLE | False success | Syntax false-success | Requests | Tool steps | Reads | Mutations | Verification PASS / FAIL | Repair mutations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| R1 `REREAD_ONLY` | 0/12 | 8/12 | 8 / 4 / 0 | 8 | 4 | 53 | 36 | 14 | 11 | 0 / 0 (11 N/A) | 0 |
| R1 `POST_EDIT_VERIFY` | 1/12 | 4/12 | 8 / 4 / 0 | 3 | 0 | 60 | 39 | 16 | 9 | 4 / 5 | 1 |
| R2 `REREAD_ONLY` | 1/12 | 8/12 | 9 / 3 / 0 | 7 | 3 | 57 | 39 | 14 | 9 | 0 / 0 (9 N/A) | 0 |
| R2 `POST_EDIT_VERIFY` | 0/12 | 5/12 | 8 / 4 / 0 | 5 | 0 | 73 | 54 | 18 | 14 | 9 / 5 | 1 |

### Two-round descriptive aggregate

| Mode (same 12 tasks repeated twice) | Task success | Conversational completion | Final syntax PASS / FAIL / UNAVAILABLE | False success | Syntax false-success | Requests (average / condition) | Tool steps | Reads | Mutations | Verification PASS / FAIL | Repair mutations / recovered conditions |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `REREAD_ONLY` (24 repeated runs) | 1/24 | 16/24 | 17 / 7 / 0 | 15 | 7/24 | 110 (4.58) | 75 | 28 | 20 | 0 / 0 (20 N/A) | 0 / 0 |
| `POST_EDIT_VERIFY` (24 repeated runs) | 1/24 | 9/24 | 16 / 8 / 0 | 8 | 0/24 | 133 (5.54) | 93 | 34 | 23 | 13 / 10 | 2 / 0 |

Across this small repeated set, both modes achieved one task success (1/24 each). Syntax false-success appeared in 7/24 `REREAD_ONLY` runs and 0/24 `POST_EDIT_VERIFY` runs, but final syntax FAIL occurred in 7 `REREAD_ONLY` and 8 `POST_EDIT_VERIFY` workspaces. Thus the observed difference is false-success prevention, not fewer invalid final workspaces or guaranteed repair. There were no `UNAVAILABLE` final syntax results. These descriptive counts do not establish a general advantage.

### Language breakdown

Each cell is task success / final syntax FAIL / syntax false-success / general false-success / requests / verification FAIL events / repair mutations / recovered conditions / UNAVAILABLE, over 8 repeated runs per language and mode.

| Language | `REREAD_ONLY` | `POST_EDIT_VERIFY` |
|---|---|---|
| Python | 1 / 2 / 2 / 5 / 39 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 5 / 39 / 1 / 0 / 0 / 0 |
| JavaScript | 0 / 0 / 0 / 4 / 32 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 3 / 38 / 1 / 0 / 0 / 0 |
| Standalone Java | 0 / 5 / 5 / 6 / 39 / 0 / 0 / 0 / 0 | 1 / 6 / 0 / 0 / 56 / 8 / 2 / 0 / 0 |

### Task-category breakdown

Each mode/category cell contains 6 repeated runs (three languages by two rounds): task success / final syntax FAIL / syntax false-success / verification FAIL events / repair mutations / recovered conditions / requests.

| Category | `REREAD_ONLY` | `POST_EDIT_VERIFY` |
|---|---|---|
| `SIMPLE_INSERT` | 0 / 2 / 2 / 0 / 0 / 0 / 23 | 0 / 2 / 0 / 2 / 0 / 0 / 22 |
| `STRUCTURAL_INSERT` | 0 / 3 / 3 / 0 / 0 / 0 / 28 | 0 / 4 / 0 / 4 / 0 / 0 / 36 |
| `MODIFY_EXISTING` | 1 / 0 / 0 / 0 / 0 / 0 / 26 | 1 / 0 / 0 / 0 / 0 / 0 / 37 |
| `TWO_STEP_EDIT` | 0 / 2 / 2 / 0 / 0 / 0 / 33 | 0 / 2 / 0 / 4 / 2 / 0 / 38 |

### Detection and recovery

`POST_EDIT_VERIFY` produced 23 verification attempts: 13 PASS and 10 FAIL, across 8 conditions with at least one verification failure. The local verifier itself does not call the model. In 2 of those conditions, a later successful mutation was recorded; neither condition reached a later PASS, so recovered conditions were 0. This separates observed detection from successful recovery: the verifier surfaced invalid edits in these runs, but recovery remained limited.

Among the 17 POST conditions with a successful mutation followed by a verifier result, the first such verification was PASS in 9 and FAIL in 8. The other 7 conditions ended without a successful mutation followed by verification. The verification command is local and adds no provider request directly.

The baseline had 7 syntax false-successes: the Agent completed conversationally after editing/rereading, while the independent evaluator found invalid final source. Representative cases are R1 `py_structural_insert`, R1 `py_two_step_edit`, and R1 `java_simple_insert`; their trajectories are preserved under `round-1/trajectories/`.

In the two `java_two_step_edit` verification-failure runs, `javac` reported FAIL twice and the Agent attempted a subsequent edit in each; neither reached syntax PASS. Across Attempt 3, the Agent made four `run_maven_test` calls against standalone-Java fixture workspaces; each returned `TEST_FAILED` because no Maven POM was present. These calls did not count as benchmark infrastructure failures: they were unnecessary tool choices, not failed infrastructure preflight.

### Requests, caps, and infrastructure

Attempt 3 used 243 provider requests total: 110 in `REREAD_ONLY` (4.58 per condition) and 133 in `POST_EDIT_VERIFY` (5.54 per condition), a descriptive difference of +23. Across the 8 conditions with verifier FAIL, POST used 55 requests versus 43 in the paired baseline (+12); across the 16 without verifier FAIL, POST used 78 versus 67 (+11). These are stochastic trajectory counts and do not isolate verifier-induced cost. The local verifier adds no provider request by itself.

No condition hit its 12-request cap (maximum observed: 10); no Agent run terminated at the 10-step limit. There were zero provider transport, proxy, runtime-verifier, evaluator-verifier, workspace, or runner infrastructure failures in either round. Round durations were 499,228 ms and 516,164 ms (combined 1,015,392 ms). Token usage and monetary cost are `UNAVAILABLE`.

### Interpretation and limitations

On this small repeated DEV set, post-edit verification eliminated the observed syntax false-successes seen in the reread-only baseline (0/24 versus 7/24), but it did not improve overall task success (1/24 in both modes) and none of the verifier-detected failed conditions recovered to a later verification PASS. These results support the value of explicit failure detection and completion guarding in the observed runs, while showing that verification-guided repair remains an open problem. The experiment is descriptive and does not establish statistical significance or general superiority. It also did not produce fewer invalid final workspaces: final syntax FAIL was observed in 7 baseline runs and 8 verification runs.

The study has 12 manually designed DEV tasks repeated twice, one provider/model, stochastic model behavior, and only Python/JavaScript/standalone Java live coverage; C/C++ is deterministic-test-only. Syntax validity is not semantic correctness. `REREAD_ONLY` is an ablation shim rather than a historical binary. There is no transactional rollback, and local verifier/compiler availability remains environment-dependent.

### Attempt 3 artifacts

Raw condition metrics and trajectories are retained separately from Attempts 1 and 2 under `benchmark-runs/edit-reliability-v1/fbc37ac/attempt-3/round-1/` and `round-2/`. The round summaries, per-condition JSONL, and sanitized trajectories preserve this run; no Attempt 1 or Attempt 2 results were overwritten, replayed, or merged into the Attempt 3 aggregate.
