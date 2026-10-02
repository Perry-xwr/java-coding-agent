# Memory V1 Ablation Protocol

## Research question

Does bounded structured session working memory improve multi-turn workspace continuity compared with the pre-V1.4 file-reference handoff, while keeping the model, profiles, tools, task wording, budgets, fixtures, and evaluator constant?

## Conditions

- `LEGACY_CONTEXT` retains only `lastResolvedFiles`, `lastResolvedFile`, unique candidate resolution, and multiple-candidate ambiguity. It injects the legacy contextual handoff only for a contextual follow-up.
- `STRUCTURED_MEMORY` additionally records the active user task, explicit targets, bounded discovered files, verified file facts, typed recent failures, and the last successful mutation. READ and CODE receive a bounded deterministic snapshot.

The production CLI defaults to `STRUCTURED_MEMORY`. The ablation runner selects a condition explicitly through constructor configuration; there is no environment variable or mutable global switch.

## DEV tasks

The first protocol contains eight small multi-turn DEV tasks: unique discovery/read, READ-to-CODE handoff, explicit-target override, unresolved ambiguity, explicit ambiguity resolution, typed edit failure, last-mutation continuity, and context replacement by a new task. Every task receives a fresh fixture workspace, fresh sessions, and fresh working memory.

## Metrics

- task success from deterministic workspace and typed trajectory checks
- provider requests and tool steps
- successful mutation count
- mutation target drift
- consecutive identical typed failures
- redundant same-file reads without intervening mutation, failure, or user turn

## Evaluation principles

Final-answer prose is not evidence. Evaluation uses exact file state, unchanged-file checks, selected routes, successful read targets, typed tool steps, mutation safety, completion state, and task budgets. Both conditions use the same provider, Agent profiles, tools, turns, fixtures, and evaluator.

## Status

Protocol implemented; no live-model ablation result yet.

