# Failure Analysis

The frozen V1 TEST run succeeded on 5 of 14 tasks. Nine failures were manually reviewed against their trajectories, final workspaces, Maven diagnostics, and deterministic evaluations.

## 1. Patch Precision

Several exact patches were syntactically accepted but produced malformed Java or overly broad class-body changes. `refactor_001` duplicated a helper and removed the public methods it was meant to preserve. Compact one-line fixtures increased the cost of reconstructing safe exact-match edits.

## 2. Step Budget Exhaustion

Seven tasks terminated at their original `maxSteps`. Repeated stale or invalid edits consumed the remaining budget before the Agent could validate or produce a final answer. The budget is part of the frozen benchmark and was not raised after seeing failures.

## 3. Compiler/Test Recovery

The runtime produced useful structured diagnostics, but the model often failed to convert them into a valid next patch. Duplicate methods, misplaced imports, and unbalanced braces persisted across recovery attempts. The TEST run recorded seven recovery attempts and no successful test-failure recovery.

## 4. Hidden Edge Cases

Local fixture projects can have no visible tests, making model reasoning important. In `multi_004`, local Maven succeeded, but the hidden test exposed Java's trailing-empty behavior for `String.split("@")`. In `multi_002`, the last CSV column was still omitted even though the Agent finalized the task.

## 5. Refactoring Weakness

The three `SMALL_REFACTOR` tasks scored 0/3. The Agent could often identify the requested helper but struggled to preserve public behavior and produce a coherent whole-file structure. `refactor_003` is also a tool-use failure: five insertion attempts used empty `oldText`, which the safe patch tool correctly rejected.

## Representative Tasks

- `refactor_001`: broad edits created duplicate helpers and a compilation failure.
- `refactor_003`: safety checks rejected invalid empty-match patches; no recovery strategy emerged.
- `multi_004`: visible requirements were partly addressed, but a language-specific edge case failed hidden evaluation.

No evaluator regression was found. The two automatic `UNKNOWN` categories were explainable through manual review, and no mojibake was present in diagnostics. These findings describe one small, stochastic held-out run and should guide a separately versioned V2 rather than retroactive V1 tuning.
