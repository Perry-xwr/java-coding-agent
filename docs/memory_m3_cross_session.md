# M3: explicit cross-session memory

M3 extends M1 conversations and M2 request selection, not the ReAct loop. Shared
memory is workspace-local and separate from private histories. No model requests,
embeddings, automatic extraction, services, or new executable tools are introduced.

## Commands and consent

```text
/memory global status
/memory global on
/memory remember 该项目统一使用 Java 17
/memory global off
```

New sessions and old snapshots without M3 metadata default OFF. Each snapshot
stores its own consent. Switching/restarting restores it. `/memory status|show|compact`
still concerns Session Memory; switching shared memory OFF does not disable M2.
An empty conversation is checkpointed when consent is changed, so the choice can
survive restart. ON persistence failure restores the prior setting; OFF immediately
revokes in-process consent even if saving fails (the user is warned that persistence
failed). A failed OFF save cannot guarantee the old disk setting changed after restart.

Only the explicit CLI `remember` command authorizes a write. No ToolRegistry tool
implements this command. Text is an exact user statement, **not a system-verified
fact**. It must be nonblank, single-line, at most 1024 characters, and pass a
conservative credential-label/pattern check. Rejection does not print the text.
This heuristic cannot detect every secret; users must not save sensitive content.

## Storage and existing components

`CrossSessionMemory` is extended by `LocalCrossSessionMemory`; `CliConversationManager`
provides the consent-bound service. `SharedMemoryStore` reuses `CliSessionStore`'s
controlled directory checks and JSON/temporary-write seam. The version-1 library
is `.agent-cli/memory/shared.json` and is Git ignored. Each entry contains UUID,
exact content, source session UUID, timestamps, `USER_MEMORY_COMMAND`,
`EXPLICIT_USER` authorization, and normalized workspace scope. Optional source
message reference is absent: the CLI command, not a model message, is the source.

The library is limited to 1000 entries / 8 MiB. Invalid version, missing provenance,
unsupported authorization, duplicate IDs, malformed content or JSON fail closed:
the library is not used. Current conversation history remains intact. Writes use
a same-directory temporary file and atomic replacement; write failure preserves
the prior library. No settings, environment variables or secrets are collected.

The reserved `.agent-cli` boundary already blocks direct and symlink-alias access
through ordinary file discovery/read/edit tools. This is **not** an OS sandbox or
protection against another process running as the user. Storage is plaintext, not
encrypted/authenticated; manually forged files are not cryptographically detectable.
The MVP assumes one CLI writer; simultaneous processes are not transactionally coordinated.

## Retrieval, context and observability

OFF checks consent before any shared read/write. ON retrieves once for the current
task, without scanning other conversation files. Normalized Latin words/numbers
and Chinese bigrams are matched; generic stop words are excluded. Score is matched
query terms divided by query-term count; minimum 0.25 with at least one match.
Results sort by descending score then stable ID, maximum three. This lexical score
is not a semantic confidence or a measured model improvement.

`SessionRequestContext` supplies authorized candidates to the existing
`RequestContextSelector`. Exactly one low-trust **user-role data block**, with IDs,
sources and quoted content, can be added to a request copy. Current user instructions
and runtime permissions take precedence. Duplicate IDs are omitted. The complete
injection has a 512 ESTIMATED token ceiling and must also fit M2's overall estimated
budget (default 8192). If protected context is already over budget, shared data is
not added. Nothing is trimmed to make a shared hit fit; no extra LLM requests occur.

`SessionContextMetrics.sharedMemories` records selected IDs, scores and content
estimates per model decision; normal CLI metrics show these metadata only. The
selected request estimate includes the full injected block. All estimates use
serialized UTF-8 bytes / 4 and are **not provider-reported billing tokens**. Existing
P0 usage continues to expose actual usage only when a provider returns it.

## Preventing OFF leakage through history

Request-only injection alone is insufficient: assistant replies or tool arguments
can repeat shared data. Therefore a completed/failed turn that actually selected
shared memory is marked with profile, original history bounds and selected IDs.
These `CrossSessionState` exposure marks are saved atomically with raw history.

All later requests, ON or OFF, withhold entire exposed historical turns, including
tool calls/results and summary sources. The current active turn is never split.
Derived Working Context updates from an exposed trajectory are not retained.
The library is retrieved anew only if ON. This privacy filtering remains active
when M2 compaction is disabled; malformed persisted exposure bounds fail restore
instead of silently forgetting their provenance. `clear` removes matching marks
when it removes the corresponding profile history.

Raw histories stay complete on disk and remain available through session/history
facilities; injected blocks themselves never enter those histories. This conservative
choice can lose useful model context for later edits, requiring a fresh read. OFF
does not undo previously authorized file edits, erase user-entered text, or remove
information from files that a later task explicitly reads. It is not semantic
unlearning. Model compliance with quoted-data instructions is not guaranteed;
actual tool permissions remain Java-enforced. Controlled live-test mode does not
retrieve shared data, while imported exposure provenance still protects requests.

## Offline acceptance

`CrossSessionMemoryTest` uses actual FakeLLM request capture, not claimed answers:

- OFF reads/writes zero shared data; explicit writes preserve provenance.
- English and Chinese Java 17 A/B scenarios: B OFF absent, ON present, OFF again
  absent even when the ON reply deliberately echoed the statement.
- Switch/restart restores consent and exposure filtering; other private summaries
  and raw histories are not retrieval sources.
- Corrupt, unapproved, source-less and foreign-workspace records are not used.
- Atomic save failure preserves the prior library; unavailable shared data does
  not add model decisions or destroy conversation state.
- ID deduplication, bounded injection and whole-tool-exchange privacy filtering.
- A malicious memory cannot give a READ Agent write permissions; the attempted
  structured write fails and no file is created.

`SessionDataPrivacyTest` also checks the new shared-library path against discovery,
read and edit tools. Existing M1/M2, R1/R2, streaming, evidence, diff and usage tests
remain the regression baseline. No real model or Calculator experiment is run.

M4 editing/deletion/semantic deduplication, M5 effect evaluation, real-model usage
quality, cross-workspace sharing and concurrent-writer transactions are not implemented.

## Recorded validation (2026-10-25)

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dtest=CrossSessionMemoryTest,SessionContextMemoryTest,ManagedConversationTest,CliSessionStoreTest,SessionDataPrivacyTest' test
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Targeted: 54 total, 53 passed, 0 failures, 0 errors, 1 skipped. M3's 15 new
tests all passed. Safe regression selection: 671 total, 657 passed, 0 failures,
0 errors, 14 skipped, BUILD SUCCESS. This is not an unqualified entire-suite
claim: the same two classes / eight loopback HTTP tests excluded in M2 remain
unexecuted because of the environment limitation; real GLM integration stays off.
There were zero real provider calls. `git diff --check` passed; no commit/push.

M3 touched the following files (other pre-existing dirty files were retained):

- `.gitignore`, `README.md`, `SPEC.md`, this report.
- `src/main/java/com/agent/{CliConversationManager,CliSessionSnapshot,CliSessionStore,CrossSessionMemory,SharedMemoryStore,InteractiveCli}.java`.
- `src/main/java/com/agent/agent/RequestContextSelector.java`.
- `src/main/java/com/agent/memory/{CrossSessionState,LongTermMemory,SharedMemoryHit,LocalCrossSessionMemory,SessionRequestContext,SessionContextMetrics}.java`.
- `src/test/java/com/agent/CrossSessionMemoryTest.java`.
- `src/test/java/com/agent/tool/SessionDataPrivacyTest.java`.
