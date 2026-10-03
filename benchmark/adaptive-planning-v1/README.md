# adaptive-planning-v1 DEV protocol

## Research Question

Under identical tasks, provider/model, tools, workspace fixtures, evaluator, request cap, and runtime iteration limit, how do `REACTIVE`, `PLAN_EXECUTE`, and `ADAPTIVE` compare? This protocol separately measures adaptive routing behavior and actual task outcomes; it does not assume planning improves success.

## Modes

Each task is evaluated in all three configured modes. Every condition receives a fresh Agent, progress state, provider client, budget, and fixture copy. `ADAPTIVE` makes a local deterministic decision before any provider request.

## Task Classes

The DEV manifest has nine unique tasks: three `SIMPLE`, three `COMPLEX`, and three `AMBIGUOUS`. The expected adaptive mode is routing metadata only and never determines coding success.

## DEV Protocol

Manifest: `manifest.json`. The runner requires explicit provider injection; it has no default provider construction and no command-line path that silently starts a live provider. Every mode uses the same per-condition provider request cap and `Agent.MAX_ITERATIONS`. Conditions run in fixed order: REACTIVE, PLAN_EXECUTE, ADAPTIVE. Each workspace is copied from its fixture and isolated by run/task/mode.

## Routing Metrics

For adaptive conditions, record configured/effective mode, confidence, reason codes, route match, reactive/plan route totals, class-level route matches, planning false positives, and planning false negatives. `routing_match` is reported separately from task success and is derived from router metadata, not final prose.

## Task Success Contract

Success is evaluated from actual workspace contents, allowed mutation targets, explicitly requested file reads, verification results, and forbidden/unchanged paths. Final-answer prose and route match do not affect task success. Conversational completion is reported as a separate metric.

## Provider Budget Fairness

All three modes have the same per-task request cap (12 in this initial DEV manifest). Budgeting wraps the explicitly injected client. The router is synchronous local code and adds zero provider requests. PLAN_EXECUTE may make its existing V1.6 planning request.

## Simple-task Overhead

For each SIMPLE task, report `PLAN_EXECUTE requests - REACTIVE requests` and `ADAPTIVE requests - REACTIVE requests`. These are descriptive request-count differences; no cost/token estimate is inferred.

## Known Limitations

This is a small deterministic-protocol DEV set, not a statistical evaluation. The heuristic may misclassify natural language; a route mismatch does not imply task failure, and a route match does not imply task success. Attempt 2 completed both live rounds, but Maven verification was blocked by unavailable plugin resolution; see the run record below.

## Live DEV Evaluation

### Experimental Commit and Configuration

The frozen benchmark commit is `5f74a0424bb9134129a69ae603f218bd96d9e669`; the runtime identity supplied to the run was V1.7-A commit `fbf3be7`. The provider was GLM `glm-4-flash`, with nine DEV tasks, three modes, and a per-condition cap of 12 provider requests. One credential smoke request succeeded before the experiment; it did not use a task workspace or tools.

### Attempt 1 Status

The first live attempt was aborted during Round 1 after 14 infrastructure failures. The first recorded provider failure was a connection reset; subsequent requests could not connect to the local proxy at `127.0.0.1:7897`. This reached the frozen protocol's stop threshold, so Round 2 was not run. The attempt is incomplete and is not a completed Adaptive evaluation. No comparative conclusion can be drawn. Raw diagnostic artifacts remain in the ignored local `benchmark-runs/adaptive-planning-v1/` directory.

### Attempt 2 — Completed Two-Round Run

Attempt 2 was run from scratch after the optional-proxy fix and a separate 30/30 serialized provider transport gate. Attempt 1's partial records were not reused. The runtime identity was `fbf3be7`; the frozen benchmark identity was `5f74a0424bb9134129a69ae603f218bd96d9e669`. Provider/model were GLM `glm-4-flash`, using the explicit local HTTP proxy `http://127.0.0.1:7897`. There was no credential smoke. Each of 54 conditions used a fresh workspace, Agent, provider budget, and session state, with a 12-request per-condition cap and the frozen 10-iteration limit. No provider-transport infrastructure failures occurred, and no condition was retried.

The two rounds each completed all 27 conditions. Round 1 used `R_P_A`; Round 2 used `A_P_R`:

| Round | Mode | Success | Provider requests | Average requests/run |
|---|---|---:|---:|---:|
| 1 | REACTIVE | 4/9 | 49 | 5.44 |
| 1 | PLAN_EXECUTE | 4/9 | 69 | 7.67 |
| 1 | ADAPTIVE | 4/9 | 54 | 6.00 |
| 2 | REACTIVE | 3/9 | 51 | 5.67 |
| 2 | PLAN_EXECUTE | 5/9 | 67 | 7.44 |
| 2 | ADAPTIVE | 4/9 | 62 | 6.89 |

The following aggregate repeats the same nine DEV tasks twice. Each mode therefore has 18 repeated task-runs, not 18 independent tasks:

| Mode | Success | Conversation complete | Workspace outcome | Requests (avg/run) | Tool steps | Mutations | Target drift | Typed failures | Repeated failures | Explicit reads | Maven attempts | Plan steps (completed) | Fallbacks | Replans |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| REACTIVE | 7/18 (38.9%) | 6/18 | expected 7, unsatisfied 11 | 100 (5.56) | 69 | 15 | 1 | 18 | 3 | 37 | 6 | 0 (0) | 0 | 0 |
| PLAN_EXECUTE | 9/18 (50.0%) | 8/18 | expected 9, unsatisfied 9 | 136 (7.56) | 83 | 19 | 1 | 15 | 6 | 34 | 12 | 174 (28) | 2 | 4 |
| ADAPTIVE | 8/18 (44.4%) | 6/18 | expected 8, unsatisfied 10 | 116 (6.44) | 73 | 15 | 1 | 18 | 5 | 31 | 8 | 98 (11) | 1 | 5 |

Requests per successful task-run were 14.29 for REACTIVE, 15.11 for PLAN_EXECUTE, and 14.50 for ADAPTIVE. This is only a descriptive ratio. Four conditions used exactly 12 requests; none recorded a request-cap termination. Token usage and monetary cost were unavailable.

#### Per-task outcomes

`success / requests` is shown per configured mode. Round 1 columns are `R / P / A`; Round 2 columns are also normalized to `R / P / A` for comparison, although its execution order was `A_P_R`.

| Task | Class | Round 1: R / P / A | Round 2: R / P / A |
|---|---|---|---|
| `simple_01_note_literal` | SIMPLE | pass/4 · pass/6 · pass/4 | pass/4 · pass/5 · pass/4 |
| `simple_02_readme_tagline` | SIMPLE | pass/4 · pass/6 · pass/5 | pass/5 · pass/6 · pass/5 |
| `simple_03_java_greeting` | SIMPLE | fail/10 · fail/7 · fail/8 | fail/7 · fail/7 · fail/6 |
| `complex_01_locate_normalizer_verify` | COMPLEX | fail/7 · fail/10 · fail/8 | fail/7 · fail/9 · fail/10 |
| `complex_02_two_requirements` | COMPLEX | fail/10 · fail/12 · fail/12 | fail/10 · fail/11 · fail/12 |
| `complex_03_cross_file_rule` | COMPLEX | fail/4 · fail/10 · fail/6 | fail/5 · fail/12 · fail/11 |
| `ambiguous_01_parser_inspection` | AMBIGUOUS | pass/3 · fail/8 · pass/3 | fail/6 · pass/4 · pass/5 |
| `ambiguous_02_config_default` | AMBIGUOUS | fail/3 · pass/6 · fail/3 | fail/3 · pass/6 · fail/3 |
| `ambiguous_03_literal_verify_phrase` | AMBIGUOUS | pass/4 · pass/4 · pass/5 | pass/4 · pass/7 · pass/6 |

#### Routing behavior

Across 18 Adaptive runs, routing matched the task-class expectation in 16/18 cases. All six SIMPLE runs in each round routed to REACTIVE, and all six COMPLEX runs routed to PLAN_EXECUTE. Among AMBIGUOUS runs, four routed to REACTIVE and two to PLAN_EXECUTE; the two false positives were both `ambiguous_03_literal_verify_phrase`, whose literal request contained a verification-related phrase. There were no false negatives. These routing matches do not imply task success.

The same route was selected for each task in both rounds. This repeatable routing observation is separate from outcomes: SIMPLE task 3 and all six COMPLEX task-runs per mode failed in both rounds, while other ambiguous-task outcomes varied by task and mode.

#### Simple-task request overhead

The paired deltas below are `planning/adaptive requests - reactive requests` for each task and round:

| Task | Round 1 PLAN−R | Round 1 ADAPTIVE−R | Round 2 PLAN−R | Round 2 ADAPTIVE−R |
|---|---:|---:|---:|---:|
| `simple_01_note_literal` | +2 | 0 | +1 | 0 |
| `simple_02_readme_tagline` | +2 | +1 | +1 | 0 |
| `simple_03_java_greeting` | -3 | -2 | 0 | -1 |
| Total | +1 | -1 | +2 | -1 |

Across these six repeated simple-task runs, PLAN_EXECUTE used 3 more requests than REACTIVE, while ADAPTIVE used 2 fewer. ADAPTIVE routed all six to REACTIVE, avoiding an always-plan request; actual request counts still varied because the model's tool interactions varied. This is an observation on these runs only.

#### Complex and ambiguous task behavior

All 18 COMPLEX task-runs failed evaluation (0/6 for each mode); Adaptive routed to PLAN_EXECUTE in all six repetitions. The aggregate request totals were 43 REACTIVE, 64 PLAN_EXECUTE, and 59 ADAPTIVE. These results do not establish that planning caused or would prevent the failures.

For AMBIGUOUS tasks, success was 3/6 REACTIVE, 5/6 PLAN_EXECUTE, and 4/6 ADAPTIVE. Adaptive consistently routed `ambiguous_01_parser_inspection` and `ambiguous_02_config_default` to REACTIVE, and consistently routed `ambiguous_03_literal_verify_phrase` to PLAN_EXECUTE (two false positives against the frozen expected mode). In one otherwise successful read-only Adaptive run, the trajectory included failed patch attempts; the evaluator observed no successful mutation. This is a trajectory-quality caveat, not an outcome change.

#### Fallback, replan, and verification

PLAN_EXECUTE had two fallback conditions: Round 1 `complex_01_locate_normalizer_verify` and `complex_02_two_requirements`. Adaptive had one fallback: Round 1 `complex_02_two_requirements`. There were nine replan events: PLAN_EXECUTE on `complex_03_cross_file_rule` in Round 1 and the three COMPLEX tasks in Round 2; ADAPTIVE on `complex_01_locate_normalizer_verify` and `complex_03_cross_file_rule` in Round 1, and on all three COMPLEX tasks in Round 2. A fallback or replan is not itself evidence of successful recovery.

There were 26 `run_maven_test` tool invocations. Every invocation returned `BUILD FAILURE` while Maven attempted to resolve `maven-surefire-plugin:3.2.5` from Maven Central and the execution environment returned `Permission denied: getsockopt`. No Maven verification passed. The benchmark runner recorded zero provider-transport infrastructure failures, but this separate Maven dependency-resolution limitation materially constrains interpretation of Java verification tasks: the observed failures cannot be attributed solely to model/planner capability.

Evaluator failure labels were overlapping per condition: `MAVEN_NOT_PASSED_AFTER_LATEST_MUTATION` appeared 24 times, `NO_SUCCESSFUL_MUTATION` 8 times, missing expected Formatter content 6 times, missing required `FormatRules.java` read 4 times, missing expected `application.properties` content 4 times, missing expected Settings content 3 times, target drift 3 times, unexpected Parser changes 2 times, and missing expected TextNormalizer content once. These counts are failure evidence, not mutually exclusive categories.

Among successful runs, no target drift was recorded; 20 had a successful mutation, and the remaining four successful outcomes were read-only. Successful mutation runs did not show multi-mutation over-editing. Some trajectories made extra reads, and one successful read-only run attempted edits that failed; no successful unrelated-file mutation was observed.

#### Attempt 2 limitations and conclusion

- Nine DEV tasks only, each repeated twice; repeated runs are not independent samples.
- Task classes are manually labeled; the router is heuristic and the model is stochastic.
- One provider/model only; no token or monetary accounting was available.
- No statistical significance is claimed. Routing correctness is not task success.
- Maven verification was blocked by the local execution environment's inability to fetch Surefire; Java task outcomes requiring a Maven pass are therefore infrastructure-limited.
- Attempt 1 remains an aborted proxy-infrastructure history and is not combined with Attempt 2.

In this small repeated DEV evaluation, PLAN_EXECUTE had the highest observed task success count (9/18), followed by ADAPTIVE (8/18) and REACTIVE (7/18), while also using the most requests. The one-run-per-mode difference is descriptive only; Maven blockage and stochastic behavior prevent a superiority claim. The runs support the narrower observation that Adaptive routed the observed SIMPLE tasks reactively and the COMPLEX tasks to PLAN_EXECUTE, but its ambiguous-task false positives and end-to-end outcomes remain mixed. Raw Attempt 2 artifacts, including per-condition records and trajectories, are retained locally under the ignored path `benchmark-runs/adaptive-planning-v1/5f74a0424bb9134129a69ae603f218bd96d9e669/attempt-2/`.
