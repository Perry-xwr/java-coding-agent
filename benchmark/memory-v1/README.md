# Memory V1 Ablation Protocol

## Research Question

Does bounded structured session working memory improve multi-turn workspace continuity compared with the pre-V1.4 file-reference handoff, while keeping the model, profiles, tools, task wording, budgets, fixtures, and evaluator constant?

The comparison is between `LEGACY_CONTEXT` and `STRUCTURED_MEMORY`. It asks whether bounded, tool-grounded session memory improves context continuity and efficiency in multi-turn coding-agent tasks.

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

## Experimental Setup

- Provider/model: GLM `glm-4-flash`
- Task set: eight multi-turn DEV tasks
- Design: paired conditions over identical tasks and isolated fixtures
- Controls: identical tools, prompts, request budgets, task wording, and evaluator
- Round 1 order: `LEGACY_CONTEXT` then `STRUCTURED_MEMORY` for each task
- Round 2 order: `STRUCTURED_MEMORY` then `LEGACY_CONTEXT` for each task
- Repetitions: two observed rounds, with no task-level retries
- Provider status: no external provider or network error interrupted either round

No frozen TEST split was run for this memory experiment. Runtime trajectories and raw result files remain ignored local artifacts rather than committed benchmark evidence.

## Results

| Round | Legacy Success | Structured Success | Legacy Requests | Structured Requests | Legacy Tool Steps | Structured Tool Steps |
|---|---:|---:|---:|---:|---:|---:|
| Round 1 | 6/8 (75.0%) | 7/8 (87.5%) | 47 | 48 | 26 | 29 |
| Round 2 | 6/8 (75.0%) | 7/8 (87.5%) | 49 | 45 | 29 | 24 |
| Two-round descriptive aggregate | 12/16 (75.0%) | 14/16 (87.5%) | 96 | 93 | 55 | 53 |

The aggregate row describes two repetitions of the same eight tasks. It does not represent sixteen independent benchmark tasks, and it is not a statistical-significance result.

## Repeatable Finding

Task 07 produced the clearest repeatable memory-specific signal. On its third turn, both rounds showed:

```text
LEGACY_CONTEXT:     read_file -> patch
STRUCTURED_MEMORY:  direct patch using persisted last-mutation context
```

Structured Memory used one fewer provider request and one fewer explicit read/tool step in each round while reaching the same correct final workspace state.

## Unstable Findings

Task 06 did not repeat consistently. Structured Memory passed in Round 1 but failed in Round 2. Its recovery behavior occurred within one Agent run after a tool observation, so it is not clean evidence for cross-turn session memory and may reflect model sampling variance.

## Protocol Limitation

Task 05 is affected by an interaction among clarification behavior, incomplete-turn handling, and the per-task request cap. This can change the evaluator outcome even when the final target file is safely and correctly modified. The protocol and historical runs are intentionally left unchanged; this limitation must be considered when interpreting aggregate success counts.

## Conclusion

Structured working memory showed a limited but repeatable benefit on cross-turn mutation continuity, while overall success-rate differences remain preliminary due to the small task set, model sampling variance, and known protocol limitations.

In both observed rounds, Structured Memory achieved 7/8 versus 6/8 for Legacy Context, but the specific task responsible for the difference was not stable across rounds. These results are descriptive and do not establish statistical significance.
