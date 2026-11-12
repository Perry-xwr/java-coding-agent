# M1: managed CLI conversations

M1 reuses `CliSessions` (the CHAT/READ/CODE Agent instances), `CliSessionSnapshot` v1
(all profile histories, permission mode and `CliWorkingContext`) and `CliSessionStore`
(UTF-8 JSON, size/credential checks, controlled paths). It adds a managed conversation
record and manager at the CLI boundary; it does not change the ReAct loop.

## Commands

```text
/new
/sessions
/rename <full-session-uuid> <title>
/switch <full-session-uuid>
/history
```

Ordinary tasks are checkpointed after each turn, including failed turns when their
history has valid tool-call/result pairs. A conversation is created on `/new` or on
the first task. Mode changes and `clear` also checkpoint an existing conversation.
Startup stays in UNIFIED with an empty history; use `/sessions` and `/switch` to
resume a record after restart. Nothing retrieves all old sessions into a model request.

`/new` explicitly starts UNIFIED with only each Agent's original system message and
an empty Working Context. `/switch` restores all profile histories, saved permission
mode and Working Context together after validation. READ never upgrades itself.
The previous task's Diff is cleared on switch; changes on disk are not reverted.
`/history` displays up to 100 recent non-system messages from the selected profile,
with per-message display bounds and tool-result ids instead of full tool payloads.
The stored histories themselves are not truncated.

## Persistence and compatibility

Managed records live at `.agent-cli/conversations/<uuid>.json` (ignored by Git).
The wrapper has a version, id, title, timestamps and an unchanged v1 CLI snapshot.
Updates publish a fully written temporary file with atomic replacement; unsupported
atomic replacement fails visibly. A failed save retains in-memory history and blocks
`/new` and `/switch` rather than silently discarding it. Missing/corrupt or incompatible
snapshots do not partially replace active state. Historical tools are never replayed.

Legacy `/save <name>` still refuses overwrite under `.agent-cli/sessions`; `/load`
still confirms replacement of dirty history and preserves old permission modes.
Loading a legacy snapshot detaches managed identity, so subsequent tasks are saved
as a separate conversation rather than overwriting the previous managed record.

`.agent-cli` is a reserved private runtime directory: normal workspace read/write,
file discovery and code search exclude it, including filesystem aliases to its files.
Titles never become filesystem paths. Persistence excludes provider configuration
and rejects recognizable credential-like text, but it does not promise detection of
every possible secret embedded in arbitrary conversation content. Records are local,
unencrypted user data. Size limit is 8 MiB per record.

## Memory boundary

`CliSessionSnapshot implements SessionMemory`: history and Working Context belong to
one conversation. `CrossSessionMemory.disabled()` returns no retrieved memories and
rejects shared writes. M1 provides no enabled implementation or enable command. It
performs no extraction, summaries, embeddings or shared storage. M2 can add explicit
authorization and a separate long-term-memory store behind that boundary.

Conversation isolation does not isolate filesystem edits: all sessions still share
the selected project workspace, and disk-version guards continue to apply. File-tool
privacy is not an OS sandbox: approved host Maven plugins/tests still run with the
current user's filesystem permissions and may read runtime files. Existing R1 approval,
process environment filtering and R2 limits remain necessary.

Controlled live-test mode disables managed checkpointing and `/new`/`/switch`/`/rename`
to preserve the existing experiment persistence and process-wide budgets.

## Current limits

- No automatic reopening of the last session, deletion UI, pagination or indexed search.
- One active CLI writer per workspace is supported. Concurrent processes can overwrite
  the same record; atomic replacement prevents partial files, not lost updates.
- A corrupt managed file is reported when listing; it is not silently repaired/deleted.
- Prompt-incompatible old snapshots are rejected rather than silently losing history.
- A failed turn with unmatched structured tool calls cannot be checkpointed until its
  history is valid; the CLI reports this and retains the in-memory state.
- No cross-session long-term memory is implemented in M1.

## Offline validation (2026-10-25)

Targeted command:

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dtest=ManagedConversationTest,SessionDataPrivacyTest,CliSessionStoreTest,InteractiveCliSessionTest' test
```

The targeted run passed 28 tests, zero failures/errors/skips. A later symlink-alias
case was included in the broader run below; Windows could not create its symlink,
so that case was reported as skipped rather than verified.

Broader offline regression:

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Result: **638 tests, 0 failures, 0 errors, 14 skipped, BUILD SUCCESS**.
The two excluded classes contain eight loopback HTTP tests; they were not executed.
Real GLM integration was disabled. DeepSeek/GLM streaming interceptor tests, CLI,
R1/R2 FakeRunner tests, exact edits, context selection, evidence and Diff regressions
were included. No real model API, Calculator experiment or production CLI was started.

`ManagedConversationTest` covers independent A/B histories and Working Context,
restart, empty `/new`, Unicode rename, preserved READ permissions, invalid/corrupt
switches, failed checkpoint retention, inert historical tool associations, credential
rejection, disabled shared memory and legacy snapshot imports. `SessionDataPrivacyTest`
covers discovery/search/read/create restrictions, ordinary file access, normalized paths
and the separately skipped real symlink-alias case.
