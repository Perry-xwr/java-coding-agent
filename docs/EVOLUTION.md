# Project Evolution

This is a research story about making Agent behavior observable and bounded, not a list of guaranteed capability improvements. Evidence below is limited to the specific tests and small evaluations described.

## V1.1 — Interactive Agent Runtime

- **Problem:** A command-line model demo needed a usable repository interaction loop.
- **Change:** Added CHAT / READ / CODE profiles, workspace tools, bounded Agent execution, typed tool outcomes, and streaming CLI interaction.
- **Evidence:** Deterministic unit and integration tests exercise tool dispatch and file operations.
- **What we learned:** A valid model response alone does not establish that a requested workspace change happened.

## V1.2 — AUTO Routing

- **Problem:** Requiring users to choose a mode for each message interrupts normal interaction, while a wrong mode blocks the task.
- **Change:** Added deterministic AUTO intent decisions, confidence/reason codes, default AUTO mode, and short contextual follow-up continuation.
- **Evidence:** Router and CLI tests cover CHAT / READ / CODE routing, ambiguous requests, file discovery, and follow-ups. The original frozen V1 held-out benchmark remains a separate historical result (5/14).
- **What we learned:** Routing is useful orchestration but remains heuristic; tool selection and task success still depend on the model and request context.

## V1.4 — Structured Working Memory

- **Problem:** Short follow-ups can lose explicit targets and useful observations across turns.
- **Change:** Added bounded session-level working context grounded in user requests and tool observations.
- **Evidence:** In two paired rounds over the same eight DEV tasks, Legacy scored 12/16 and Structured Memory 14/16. The clearest repeated signal was one fewer request/read on a cross-turn mutation task in both rounds.
- **What we learned:** Context continuity can reduce a specific repeated interaction cost, but aggregate differences remain preliminary and the task set has a known completion-contract limitation.

## V1.5 — Model / Environment Boundaries

- **Problem:** Provider-specific construction and direct tool ownership make runtime substitution and isolated workspaces harder.
- **Change:** Established `LLMClient` provider abstraction and `AgentEnvironment` boundary while reusing local tool implementations.
- **Evidence:** Deterministic factory, mock HTTP, environment, and existing tool tests pass; GLM remains the default and OpenAI-compatible support is non-streaming.
- **What we learned:** Interfaces help isolate responsibilities, but local environment controls and verifier availability remain machine-dependent.

## V1.6 — Structured Planning

- **Problem:** A reactive loop may begin multi-requirement tasks without an explicit action outline.
- **Change:** Added opt-in bounded Plan → Execute with at most one replan; reactive remains the default.
- **Evidence:** Two paired rounds over the same eight tasks yielded 7/16 for REACTIVE and 5/16 for PLAN_EXECUTE, with 94 vs 119 requests.
- **What we learned:** Always-on planning did not show a repeatable success advantage on this set and added overhead on simple tasks.

## V1.7 — Adaptive Planning

- **Problem:** Planning overhead may not be useful for simple requests, while complex requests may benefit from an explicit plan.
- **Change:** Added a deterministic heuristic that selects REACTIVE or PLAN_EXECUTE before the provider call.
- **Evidence:** Attempt 2 repeated nine DEV tasks twice: REACTIVE 7/18, PLAN_EXECUTE 9/18, ADAPTIVE 8/18. The router sent all observed SIMPLE cases to REACTIVE and all COMPLEX cases to PLAN_EXECUTE.
- **What we learned:** Routing consistency did not translate into a demonstrated end-to-end advantage; all 26 Maven checks failed during dependency resolution, limiting Java task attribution.

## V1.8 — Post-edit Verification

- **Problem:** A successful write followed by a model final answer can leave syntactically invalid source.
- **Change:** Added generic local post-edit syntax/compile verification with explicit FAIL / UNAVAILABLE distinction and completion evidence.
- **Evidence:** In the valid Attempt 3 repeated DEV set, syntax false-success was 7/24 for reread-only and 0/24 with post-edit verification; task success was 1/24 in both, and final syntax FAIL was 7 vs 8.
- **What we learned:** Verification blocked observed syntax false-success in this set, but did not produce higher task success or successful repair of verifier-failed conditions.

## V1.9 — Verification-guided Repair

- **Problem:** Diagnostics can expose invalid edits, but the Agent may not make a grounded correction.
- **Change:** Added structured repair directives, mandatory fresh rereads, per-file state, and recovery accounting based on a later PASS.
- **Evidence:** Two rounds over 12 tasks produced task success 5/24 for verification-only and 6/24 for guided repair; each mode recovered one eligible run.
- **What we learned:** The repair policy changed trajectory behavior, but the small evaluation did not show a stable recovery advantage.

## V1.10 — Environment-aware Tool Availability

- **Problem:** A model should not be offered a tool that cannot apply to the current workspace.
- **Change:** Kept the full `ToolRegistry` and added `AgentEnvironment` availability filtering plus dispatch-time recheck. The first production rule gates Maven testing on a safe root POM.
- **Evidence:** Live Aware advertisement was 0/92 turns in no-POM workspaces and 66/66 turns in Maven workspaces. Both modes scored 10/24 and had zero live mismatch attempts.
- **What we learned:** The mechanism behaved as designed, but this run does not prove fewer mismatches, higher task success, or cost savings. The Round-2 temporary orchestration is a reproducibility limitation.
