# Evaluation Summary

All figures below are drawn from the checked-in benchmark protocol documentation. Except for the historical V1 held-out test, the live studies are small DEV experiments with repeated tasks and stochastic model behavior. They are descriptive, not statistical-significance claims. A repeated task-run is not an independent task.

| Benchmark | Question / modes | DEV size and observed result | Main limitation |
|---|---|---|---|
| V1 held-out coding benchmark | Early coding-agent baseline | Frozen TEST: **5/14** | One held-out run; not a measure of current V1.10 behavior. Never tuned or rerun for selection. |
| Working Memory (`memory-v1`) | `LEGACY_CONTEXT` vs `STRUCTURED_MEMORY` | Same 8 tasks in two rounds: 12/16 vs 14/16; requests 96 vs 93 | Small repeated set; known clarification/completion-contract issue; advantage not statistically established. |
| Planning (`planning-v1`) | `REACTIVE` vs `PLAN_EXECUTE` | Same 8 tasks twice: 7/16 vs 5/16; requests 94 vs 119 | Sampling varies by round; plan-execute had request overhead; no repeatable advantage. |
| Adaptive Planning (`adaptive-planning-v1`) | `REACTIVE`, `PLAN_EXECUTE`, `ADAPTIVE` | 9 tasks twice: 7/18, 9/18, 8/18 respectively | All 26 Maven verification calls failed during dependency resolution due to environment permission/network failure; Java verification attribution is limited. |
| Edit Reliability (`edit-reliability-v1`, Attempt 3) | `REREAD_ONLY` vs `POST_EDIT_VERIFY` | 12 tasks twice: syntax false-success 7/24 vs 0/24; task success 1/24 in both; final syntax FAIL 7 vs 8 | Earlier attempts were invalid; Attempt 3 is the valid report. Verification detected failures but no failed condition recovered to a later PASS. |
| Verification-guided Repair (`repair-reliability-v1`) | `VERIFICATION_ONLY` vs `GUIDED_REPAIR` | 12 tasks twice: task success 5/24 vs 6/24; each mode recovered one eligible run | Eligibility depends on trajectory; behavior changed, but no stable recovery advantage was shown. |
| Environment Tool Availability (`tool-availability-v1`) | `LEGACY_ALL_TOOLS` vs `ENVIRONMENT_AWARE` | Same 12 tasks twice: task success 10/24 in both; live mismatch attempts 0 in both. Aware Maven advertisement: 0/92 no-POM turns, 66/66 Maven-workspace turns | No live mismatch attempt to avoid; result supports mechanism correctness, not reduced mismatch or behavior superiority. Round 2 used a documented temporary ignored orchestrator. |

## Interpretation notes

- V1.8's observed syntax false-success difference is a failure-detection/completion-guard observation, not a task-success or semantic-correctness gain.
- V1.9's recovery count is eligible-run-specific. One recovery in each mode does not establish a repeatable repair advantage.
- V1.10's Aware policy is intentionally limited to `run_maven_test`. It did not suppress valid Maven usage: all observed Maven attempts were in safe Maven workspaces. But the legacy mode produced no invalid no-POM attempt during this evaluation.
- Several studies use GLM `glm-4-flash`; results do not establish provider/model-general behavior. Token and monetary costs are unavailable where protocol reports say so.
- Historical protocol and result details remain in each benchmark's README. Raw run data is local and git-ignored unless a protocol explicitly says otherwise.
