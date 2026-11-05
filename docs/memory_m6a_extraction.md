# M6-A: extraction and user review

Continues the existing MemoryExtractor / DeterministicMemoryExtractor foundation.
No second shared library, embedding backend or automatic update is introduced.

## Commands

`/memory auto on|off|status` controls automatic extraction (default OFF).
`/memory harvest` explicitly processes unprocessed current-profile user statements.
`/memory pending` shows pending IDs, categories, quoted evidence and source references.
`/memory approve <id>` and `/memory reject <id>` review exact candidate UUIDs.
All candidate access requires global ON. Auto OFF can always revoke automation.

`/memory extractor deterministic` is the zero-extra-API default.
`/memory extractor llm` explicitly opts into extra model cost using the same
configured Provider client, with no tools passed. No real model was invoked in
this implementation run. Controlled live-test mode prohibits these commands.

## Ownership, evidence and storage

ExtractionState is an optional field of the existing session snapshot; missing
fields default to OFF and an empty ledger. It stores auto/mode flags, per-profile
message cursors, candidate review state, extra call count and separate provider
usage. Candidates never enter shared retrieval or model history. Original messages
are never changed. Only approve calls the existing authorized shared save method.
Review statuses and deduplication retain rejected entries, preventing re-proposal.

Sources are current-session user messages only, referenced as PROFILE:index.
Candidates retain the exact original user text, evidence quote, category, session
UUID and creation timestamp. Both extractors require content to be an exact
substring of its quote, and the quote to be present in the cited source. Approval
rechecks the current stored source. Fabricated references and paraphrased facts
are rejected, not accepted based on confidence. Confidence is uncalibrated metadata.
M3 credential checks apply; they remain heuristic, not comprehensive secret detection.

The deterministic extractor selects conservative explicit durable phrases and may
produce multiple clauses, but does not invent separate Java/Maven claims to meet
a fixed number. Model answers, tool results and other conversations are never
source inputs. Statements containing obvious completion claims are excluded.
This is not a general natural-language truth verifier or quoted-document detector.

## Triggers and budgets

Automatic extraction runs only after a completed Agent turn, outside its tool loop,
with both global and auto enabled. Failure never changes the Agent result.
Manual harvest considers unprocessed user text, not model completion claims.
The unified completion guard may interpret “后续新增功能” as a mutation task;
the offline normal-chat E2E uses the existing `/chat` profile. No routing or guard
semantics were changed to make this test pass.

JVM options `agent.memory.extraction.interval` (default 1 eligible message) and
`agent.memory.extraction.maxRequests` (default 4, range 0..100) configure triggering
and extra logical client-call budget per session. At most eight user messages / 700
source characters are selected; the LLM adapter separately rejects serialized source
data over 4096 UTF-8 bytes. This is a payload proxy, not actual billed tokens. Output
is limited to 8192 characters and three validated proposals; queue maximum is 100.

Cursor and paid-attempt reservation are atomically checkpointed BEFORE calling the
optional extractor. Failed attempts are not automatically retried for that source.
Provider usage is stored separately from normal Agent usage; unknown remains unknown,
including API failures. The extractor itself sends one client call and never retries.
Transport timeouts/retries remain those of the reused Provider client: the extra-call
cap is **not a new physical HTTP-attempt hard cap**, and this module does not add a
separate HTTP cancellation mechanism. Production transport behavior needs independent
verification before enabling paid extraction; deterministic mode has no such cost.

Approval writes the shared library then checkpoints review state. These are two
atomic files, not one transaction: a second-file failure can leave a pending entry
whose shared save succeeded. Retrying is exact-duplicate-safe. The CLI reports this
possibility rather than claiming rollback. Source history remains intact.

## Offline coverage

AutoMemoryFlowTest covers normal-chat extraction, restart/cursor deduplication,
approval, rejection, actual B OFF/ON model requests, structured LLM output,
fabricated sources, invalid JSON/API exceptions, a one-extra-call budget and usage
separation. Existing M1–M4 suites cover shared file safety, atomic persistence,
ON/OFF echo filtering and permissions. No Calculator experiment is modified.

No M6-B or M6-C implementation is included.

## Validation recorded 2026-11-01

```powershell
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dtest=AutoMemoryFlowTest,MemoryExtractorTest,MemoryManagementTest,CrossSessionMemoryTest' test
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Final targeted: 35 tests, zero failures/errors/skips. Safe regression: 691 tests,
zero failures/errors, 14 skipped, BUILD SUCCESS. As in M4, two loopback HTTP test
classes (eight tests) remain outside this environment-safe selection; no new test
class was excluded to obtain a passing result. Actual provider calls: zero.
`git diff --check` passed. This is an offline integration acceptance, not proof
of real-model extraction accuracy or production transport hard-budget behavior.

Files changed in this continuation: `CliSessions.java`, `CliAgentFactory.java`,
`CliSessionSnapshot.java`, `InteractiveCli.java`, new `ExtractionState.java`,
new `LlmMemoryExtractor.java`, new `AutoMemoryFlowTest.java`, `README.md`,
`SPEC.md`, and this report. Existing extractor interfaces/tests are reused.
No R1/R2, Agent loop, shared database format, Calculator record or Git history
was changed. Existing dirty work is retained.
