# Experiments

All DEV results below use benchmark v0.1, the six-task DEV split, `glm-4-flash`, GLM, and provider-default temperature. The experiments are retained whether positive or negative.

## REACT

Result: **0/6**. The initial loop frequently returned a plausible final answer before carrying out the required repository edit and validation. Premature finalization was the primary observed failure mode.

## ACTION_ORIENTED

Result: **3/6**. Explicit action-completion requirements increased actual tool execution and validation. This established that orchestration policy, not only task reasoning, materially affected completion.

## DIAGNOSTIC_RECOVERY

Result: **4/6**. Structured compiler/test diagnostics, reread guidance, and recovery-oriented observations produced the strongest calibrated DEV result. It was selected as the V1 default before TEST was opened.

## PLANNING

Result: **3/6**. The planning protocol did not activate reliably in the real run and did not improve the DEV score. It remains a negative experiment rather than part of V1.

## PRECISE_EDIT

Result: **1/6**. One real fallback succeeded: two exact patches failed, the Agent reread the file, `replace_lines` succeeded, and the task passed. Across the run, however, line-range/stale-context interactions caused significant overhead and a severe aggregate regression. The tool remains experimental and is excluded from V1 default behavior.

## Held-out TEST

Frozen V1 result: **5/14 (35.71%)** with `REACT_DIAGNOSTIC_RECOVERY`. This was the first and only TEST run: no TEST tuning, rerun, or random-run selection was performed. The lower result than DEV exposes generalization limits, especially for refactoring, patch precision, and recovery after compiler/test failures.

| Strategy | DEV Success |
|---|---:|
| REACT | 0/6 |
| REACT_ACTION_ORIENTED | 3/6 |
| REACT_DIAGNOSTIC_RECOVERY | 4/6 |
| REACT_PLANNING | 3/6 |
| REACT_PRECISE_EDIT | 1/6 |

The samples are deliberately reported without statistical-significance claims. Full development-stage records are retained under `docs/archive/`, while the final TEST record is `docs/v1-final-test-report.md`.
