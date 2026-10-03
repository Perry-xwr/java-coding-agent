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

This is a small deterministic-protocol DEV set, not a statistical evaluation. The heuristic may misclassify natural language; a route mismatch does not imply task failure, and a route match does not imply task success. No completed live adaptive evaluation has been run.

## Live DEV Evaluation

### Experimental Commit and Configuration

The frozen benchmark commit is `5f74a0424bb9134129a69ae603f218bd96d9e669`; the runtime identity supplied to the run was V1.7-A commit `fbf3be7`. The provider was GLM `glm-4-flash`, with nine DEV tasks, three modes, and a per-condition cap of 12 provider requests. One credential smoke request succeeded before the experiment; it did not use a task workspace or tools.

### Attempt 1 Status

The first live attempt was aborted during Round 1 after 14 infrastructure failures. The first recorded provider failure was a connection reset; subsequent requests could not connect to the local proxy at `127.0.0.1:7897`. This reached the frozen protocol's stop threshold, so Round 2 was not run. The attempt is incomplete and is not a completed Adaptive evaluation. No comparative conclusion can be drawn. Raw diagnostic artifacts remain in the ignored local `benchmark-runs/adaptive-planning-v1/` directory.
