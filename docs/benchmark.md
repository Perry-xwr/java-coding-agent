# Benchmark

## Version and Scope

Benchmark v0.1 contains 20 compact Java 17/Maven maintenance tasks. Six tasks form the DEV split and fourteen form the held-out TEST split.

| Category | Intent |
|---|---|
| `BUG_FIX` | Repair a localized defect |
| `LOGIC_FIX` | Correct behavior or edge cases |
| `TEST_FIX` | Repair an incorrect test while preserving production behavior |
| `SMALL_REFACTOR` | Remove duplication without behavior changes |
| `MULTI_STEP_DEBUG` | Resolve multiple related requirements |

Tasks are labeled EASY, MEDIUM, or HARD. These labels organize analysis; they are not claims of universal difficulty.

## Task Schema

Each task declares an ID, category, difficulty, natural-language description, fixture, expected file set, evaluation type, task-specific `maxSteps`, tags, optional hidden test class, split, and required tools. Evaluation checks are versioned separately.

## Isolation and Reset

Before a run, `FixtureWorkspaceManager` copies the selected fixture into a new task workspace under the build directory. Tasks cannot alter the source fixtures or one another. Each experiment writes a separate summary and per-task evaluation/trajectory artifacts.

## Hidden Evaluation

Hidden tests are not copied into the Agent workspace during execution. The deterministic evaluator injects them afterward and runs controlled Maven validation. This prevents the Agent from reading the answers while preserving reproducible behavioral checks.

The calibrated evaluator is behavior-first. If hidden behavior passes and strict source content is not required, a noncanonical but correct implementation can pass. Strict content constraints are applied only to tasks where the requested artifact itself matters, such as repairing a specific test or performing a named refactor.

## Split Policy

DEV was used for strategy development and calibration. TEST was reserved for the final V1 held-out measurement. The frozen TEST split was run exactly once with `REACT_DIAGNOSTIC_RECOVERY`; it must not be used for prompt selection, strategy tuning, or favorable-run selection.

## Metrics

The runner reports task success and completion, steps, tool calls, required-tool usage, invalid calls, Maven failures and recoveries, max-step termination, duration, evaluator errors, category/difficulty breakdowns, and failure classifications. These results describe this small benchmark and should not be interpreted as statistically significant estimates of general coding-agent performance.
