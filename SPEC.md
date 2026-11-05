# Session Save and Restore Specification

## D2-P isolated real-validation preflight

A separate fixed three-chat validation path must not weaken existing live-test or
R1/R2 policies. Local Memory management uses a denying client and consumes no
network budget. Real transport requires explicit authorization/balance confirmation,
a persistent maximum-three reservation ledger and fail-closed restart/error policy.
No real API is called in D2-P. The user has explicitly authorized a selectable
BYTE_LIMITED_INPUT policy instead of exact input Tokens: require that mode alongside
separate API authorization/balance confirmation, cap final serialized JSON request
at 4096 UTF-8 bytes (all messages and parameters), reject rather than truncate, and
keep server max_tokens=128. Byte estimates are never reported as actual Usage. See `docs/memory_d2p_budget_gate.md`.


## M6-C reviewed conflict suggestions

Extend pending candidate review without a second shared library. Global OFF denies
all conflict/version access. Deterministic classification is the default; optional
LLM suggestions cannot approve or write. Review exact authorized IDs and source
quotes; replace requires both exact candidate and target UUIDs from a fresh review.
Retain stable ID, creation/source metadata, current content and bounded old versions
in one atomic shared document. Check target content/timestamp under a runtime write
lock; stale or deleted targets fail closed. Old versions never enter retrieval.
M4 update and M6-B content-hash invalidation remain effective. See
`docs/memory_m6c_conflicts.md` for tests, costs and multi-file limitations.


## M6-B retrieval extension

Persist per-session keyword (default), semantic or hybrid mode. All shared access
remains consent-gated. Only current authorized shared records can be indexed;
provider/model/dimension/content-hash checks invalidate derived vectors. Use local
normalization/cosine and rank fusion, never raw cross-scale score addition. Missing
provider, corrupt cache or embedding failure falls back to keyword. Explicit separate
real-provider opt-in is required; this implementation is tested with FakeEmbedding
only. M2/M3 budgets and M3 exposure withholding remain unchanged. See
`docs/memory_m6b_retrieval.md` for costs, cache ownership and acceptance evidence.
M6-C is not implemented.


## M6-A candidate extraction and review

Current extension: `/memory auto on|off|status`, `/memory harvest`, `/memory pending`,
`/memory approve <id>`, `/memory reject <id>` and explicit paid opt-in
`/memory extractor llm` (default deterministic). Session snapshot extraction state
defaults OFF for old snapshots. Candidates and cursors remain session-owned; no
shared save until explicit approval. Source quotes are exact user-message evidence,
never assistant/tool claims. Reserve cursor and optional call budget before extraction;
failures do not change task results. Preserve complete history and M3 exposure
filtering. See `docs/memory_m6a_extraction.md` for budgets, two-file approval failure
semantics, transport limitations, and offline acceptance. M6-B/M6-C remain out of scope.


## M4 long-term memory management (current extension)

M4 extends M3 with direct-user-only `/memory list`, `/memory search <query>`,
`/memory update <full-memory-id> <new-text>`, and `/memory forget <full-memory-id>`.
All require the active session's global consent; OFF must perform zero shared I/O,
including count/status reads. Search reuses M3 top-three lexical ranking; list is
ID-sorted and displays bounded summaries, source and update time. IDs must be exact
canonical UUIDs; missing/foreign-scope IDs cannot modify any entries. Updates
preserve creation time, original source, ID and authorization, and advance update
time on content change. Same-content updates are idempotent. Exact same-scope
content duplicates are rejected with the existing ID; differing/conflicting content
is not automatically reconciled. All writes reuse the validated atomic library
replacement. Corrupt data must not be overwritten by a management attempt.

Retrieval is refreshed per task; successful management clears active request metrics
and candidates without deleting M3 exposure provenance. After update, only the new
shared content may be injected; after forget the entry may not be retrieved, including
after restart. Old exposed turns remain withheld to prevent echo leakage. Deletion
only affects shared retrieval, not original histories, prior files or comprehensive
erasure. No new model tools, API requests or permission changes. M5 is out of scope.
See `docs/memory_m4_management.md` for offline acceptance.

## M3 explicit cross-session memory (previous extension)

M3 extends the earlier M1/M2 OFF-only boundary. New/legacy sessions default OFF;
consent is stored per snapshot, independent of Session Memory and file permissions.
`/memory global on|off|status` controls shared access; `/memory remember <text>`
is the only CLI write authorization. No model tool or automatic extraction writes memories.

Acceptance: OFF performs zero shared reads/writes; explicit ON writes exact bounded
non-credential statements with stable UUID/source/time/authorization/workspace scope;
local retrieval uses deterministic top-three ranking and a 512 ESTIMATED token
injection ceiling within M2's overall soft budget. No hit means no injection.
Shared statements are low-trust user-data, never system instructions. Complete
stored histories remain unchanged; request-only injected data is never stored in
raw history. Entire prior turns exposed to shared data (including assistant echoes,
tool exchanges and summary sources) are withheld from subsequent requests in ON
and OFF states. Exposure provenance and consent survive switch/restart; corrupt
provenance fails restore closed. Old snapshots without M3 state default OFF.
Store corruption/failure must not destroy current history. Private `.agent-cli`
files remain inaccessible to ordinary workspace file tools. M4 management and M5
evaluation are out of scope. See `docs/memory_m3_cross_session.md` for limits.


## M2 session-local summary and model-context update

M2 adds optional source-linked summary data to the existing full snapshot. Storage
history is never pruned. `/memory status|show|compact` operates only on the active
conversation; cross-session memory stays OFF. Quotes come from whole user messages,
and task status comes from actual runtime evidence. Current turn, recent turns,
structured tool exchanges, file snapshots and verification feedback stay intact.
The configurable budget is ESTIMATED and soft; inability to safely shrink is reported.
No extra LLM calls, paid summaries, embeddings or shared memory are introduced.
Old snapshots without summary remain readable; invalid summary falls back to raw-history
selection. See `docs/memory_m2_context.md` for policy, configuration, limits and tests.

## M1 managed multi-session update

The Memory coursework now adds managed conversations. This section supersedes the earlier
non-goals of automatic saving and renaming for **managed conversations only**. Legacy
`/save` and `/load` remain explicit, non-overwriting v1 snapshot operations.

- `/new` creates a new independent conversation in UNIFIED mode; `/sessions` lists ids/titles;
  `/switch <id>` resumes all profile histories, permission mode and working context;
  `/rename <id> <title>` updates the title; `/history` shows the current profile's recent messages.
- Managed conversations checkpoint after each ordinary CLI task and after permission changes.
  Restart starts in UNIFIED without silently loading old data; use `/sessions` then `/switch`.
- Store each managed record with its id/title/timestamps and the unchanged v1 snapshot in
  `.agent-cli/conversations`. Read/validate candidates before switching. A failed checkpoint
  blocks `/new` or `/switch` and retains the current in-memory state.
- Each conversation owns all CHAT/READ/CODE histories and its structured Working Context.
  Switching replaces them, clears the previous task Diff, and never replays tool actions.
- Runtime state under `.agent-cli` is excluded from normal workspace read/write/discovery tools.
  This is a file-tool privacy boundary, not OS isolation of approved host Maven execution.
- Cross-session memory defaults to OFF. M1 supplies only a disabled interface: no shared
  memory storage, automatic extraction, embeddings, retrieval, or model-generated summaries.
- Controlled live-test mode retains its existing persistence behavior and disallows managed
  conversation changes. No additional model requests are introduced.

M1 offline acceptance: A/B history and working-context isolation; restart recovery of both;
empty new history; retained READ permissions; inert historical tool calls; failed/corrupt loads
leave state unchanged; default Unified tools remain present; private state cannot be read by
workspace tools; cross-session memory disabled; legacy manual snapshots remain compatible.

## User stories

1. As a CLI user, I want to save a conversation explicitly with `/save <session-name>` so that no conversation is persisted unless I request it.
2. As a returning user, I want to restore a saved conversation with `/load <session-name>` after exiting and restarting the CLI.
3. As a user with unsaved work, I want `/load` to ask before replacing the current in-memory session; the default answer is cancel.
4. As a user, I expect a loaded session to replace, not merge with, the current conversation, and I expect prior tool calls to remain history rather than execute again.

## Scope

- Add manual `/save <session-name>` and `/load <session-name>` commands to the existing interactive CLI.
- Preserve the existing AUTO, CHAT, READ, and CODE behavior and session separation.
- Persist the three existing Agent message histories, current CLI mode, last AUTO-routed mode, and the structured workspace context needed for contextual routing.
- Preserve ordered message roles and assistant tool-call/tool-result IDs so a resumed LLM conversation has its original conversational shape.
- Store a versioned JSON session document under a controlled local application directory; use UTF-8 and safe atomic creation.
- Validate session names, schema/version, mode values, message roles and tool-call/result associations before changing live in-memory state.
- Never persist provider configuration, API keys, Authorization headers, or other runtime credentials.
- A missing, invalid, unsupported, or corrupt session must leave the active CLI state unchanged.
- Existing session names must not be overwritten.

## Non-goals

- Automatic/background saving, cloud sync, encryption/key management, import/export, session deletion/rename, history editing, or merging histories.
- Replaying a tool call or any other side effect recorded in history.
- Changing Agent reasoning, prompts, provider behavior, tool semantics, verification behavior, or benchmark protocols.
- Persisting ephemeral per-run AgentProgress, plans, credentials, provider clients, or workspace file contents beyond text already present in conversation/tool observations.

## Acceptance criteria

AC-01. `/save <valid-name>` writes one UTF-8, versioned session file in the controlled session directory and reports success.
AC-02. Saving a name that already exists is rejected and leaves the original saved bytes unchanged.
AC-03. Session names containing path separators, traversal (`..`), absolute paths, blank values, or unsupported characters are rejected.
AC-04. A newly created CLI process can load a saved session and recover all three profile histories, active mode, AUTO continuation mode, and required workspace context.
AC-05. Loading replaces every profile history; messages present only in the pre-load session do not remain.
AC-06. If current state has unsaved changes, `/load` requires explicit affirmative confirmation; blank input, `n`, or any non-affirmative response cancels without changing state.
AC-07. `/load` of a missing file leaves all histories, modes, routing state, and working context unchanged.
AC-08. `/load` of malformed JSON, invalid message data, broken tool-call/tool-result association, or an unsupported schema version leaves live state unchanged.
AC-09. A successful load preserves assistant tool-call messages and matching tool-result IDs in order; loading never invokes a tool or replays an action.
AC-10. Session output contains no provider API key, Authorization header, or provider runtime configuration.
AC-11. A failed save (including a write/atomic-create failure) does not leave a partial final session file and does not mark the in-memory state as saved.
AC-12. A failed load does not partially replace one profile before another profile fails validation.
AC-13. Existing `/chat`, `/read`, `/code`, `/auto`, and `clear` commands retain their behavior; ordinary Agent ReAct tests continue to pass.
AC-14. Saving and loading does not invoke the LLM, any Tool, Maven, or benchmark runner.
AC-15. Session document version is explicit; unknown future versions are rejected safely.
AC-16. CLI restart recovery is verified using a fresh CLI/session instance, not merely the same in-memory object.

## Constraints and safety notes

- Read and validate the complete candidate document before replacing any live state.
- Persist all existing profile histories as one snapshot so AUTO/CHAT/READ/CODE state cannot be silently truncated to only the currently active profile.
- Use `Path`/`Files`, constrain resolved paths to the dedicated session directory, and do not follow a user-controlled path outside that directory.
- History text is user/model/tool conversation data and may contain workspace content; provider credentials and runtime configuration are explicitly excluded. The implementation must also reject or redact recognizable credential material before persistence, and document any limits of secret detection.
- Tool-call records are inert conversation data on restore. Only a future model response may request a new tool execution.
