# M6-C: suggestions and user-confirmed replacement

The existing candidate ledger and shared library remain authoritative. No new
memory database, automatic coverage/update or model-callable command is added.

## Commands

With global ON, `/memory review <candidate-id>` validates the current session's
pending candidate and source, reads authorized scoped records, and shows a relation,
target, quote and explanation. `/memory replace <candidate-id> <target-memory-id>`
is the explicit user confirmation: both UUIDs must exactly match an effective review.
Missing/stale reviews are rejected. `/memory reject` remains the skip/reject action;
it does not modify shared content. NEW/RELATED/UNCERTAIN can be explicitly approved
as separate records using the existing command. A deterministic possible conflict
cannot be silently appended through approve: the CLI directs the user to review/
replace or reject. Exact duplicates are handled by the existing duplicate-safe save.

`/memory versions <memory-id>` explicitly displays historical versions, marked
OLD_VERSION, not current facts. None of these commands is registered as a model
tool. Global OFF denies review/version access and replacement. Auto OFF continues
to prevent new automatic extraction, independently of explicit review.

## Classification

Default `DeterministicConflictDetector` uses exact content equality first. Simple
Java/JDK version paraphrases can be duplicates; different explicit versions in the
same recognized project scope are only POSSIBLE_CONFLICT. Java and Maven can be
RELATED. Historical descriptions and insufficient evidence remain UNCERTAIN.
Additional constraints on a same-version statement are not silently treated as
duplicates. It is deliberately not a general natural-language contradiction engine.
Embedding similarity alone never proves conflict, and review does not call embeddings.

Explicit `/memory conflict llm` enables an optional no-tools client adapter;
`/memory conflict deterministic` revokes it and clears review cache. Both default
and restart behavior are deterministic. `/memory conflict status` shows separate
call count/provider usage. Four extra logical calls are allowed per CLI lifetime;
only explicit review may call the model, never each automatic candidate or tool.
Existing effective reviews are cached by session/candidate and a fingerprint of
authorized current IDs, contents and timestamps. Topic filtering limits LLM input
to eight Java/JDK/Maven-related records; input/output sizes are bounded. No API call
was made here. Underlying client transport behavior is unchanged: the logical cap
is not a physical network-attempt or durable account-spending cap.

LLM JSON must have exactly relation, targetMemoryId, proposedAction, proposedContent,
evidence and explanation. Targets must exist among supplied authorized records,
content must equal the candidate, and evidence must quote candidate and target.
Malformed/ungrounded/error responses yield UNCERTAIN. Explanations are untrusted
suggestions, not verified facts. Model output cannot invoke replace or choose an
unreviewed target. Provider usage is separate from normal chat usage; missing is UNKNOWN.

## Atomic versions and stale protection

SharedMemoryStore.updateChecked reuses M4's update path, adding expected target
version comparison (SHA-256 of content and updatedAt). A replacement preserves ID,
creation and original source, updates current content/time, and appends an old-content
revision with runtime reason, source candidate UUID and timestamp. The latest 20
revisions are retained. Normal explicit M4 updates also keep revisions (candidate
is absent). Old JSON without revisions defaults to an empty list; no format reset.

Current content and revision list are one atomically replaced JSON document. Failed
writes cannot publish only half. All runtime shared writes use an OS file lock,
including append, update and forget; busy locks fail closed, and checked replacement
compares versions while holding the lock. External writers that ignore this protocol
are not sandboxed and remain outside the concurrency guarantee.

Review fingerprints detect target changes/deletion before replacement; target CAS
under lock catches concurrent changes afterward. Reviews are process-local, so a
restart requires review again. Candidate status and shared content are separate
atomic files: a checkpoint failure after a successful library replacement can leave
a pending candidate. Re-review/reconciliation is required; no cross-file transaction
or rollback is claimed. Original chat history is not rewritten.

Only current content participates in retrieval. The M6-B content hash invalidates old
vectors; revisions cannot restore deleted records. Forget removes the entry including
its revisions from the live library, not terminal logs, original history or backups.

## Offline evidence

MemoryConflictTest covers deterministic relations, explicit Java 17→21 replacement,
historical scope, project A/B separation, stale versions, atomic write failure,
restart reading, invalid/forged LLM JSON, valid suggestion without mutation and API
failure. Its FakeLLM conversation has both rejection and confirmation branches:
normal user statements create candidates, user review/replace changes the existing
ID, B receives only Java 21 in the shared block, and OFF removes shared injection.
Existing semantic tests verify content-hash invalidation and deletion; M1–M6-B
regression covers permissions and private-history separation. These tests do not
measure a real model's conflict classification accuracy.

Modified modules: LongTermMemory, SharedMemoryStore, CliSessionStore,
DeterministicMemoryExtractor, InteractiveCli; new ConflictDetector,
DeterministicConflictDetector, LlmConflictDetector and MemoryConflictTest; README,
SPEC and this report. Agent loop, R1/R2 and original Calculator experiments are unchanged.

## Recorded validation — 2026-11-01

```powershell
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dtest=MemoryConflictTest,AutoMemoryFlowTest,MemoryManagementTest,SemanticMemoryTest' test
mvn -o '-Dmaven.repo.local=D:\Development\agent-cli\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Final targeted: 31 tests, zero failures/errors/skips; six new conflict tests passed.
Final safe regression: 705 tests, zero failures/errors, 14 skipped, BUILD SUCCESS.
The same two classes/eight loopback HTTP tests excluded previously remain
unexecuted and are not reported as passed. No new exclusion was introduced.
Actual chat/embedding/conflict API calls: zero. `git diff --check` passed. Existing
dirty work was preserved; no branch switch, commit, push or experiment cleanup.

Known limits: deterministic parsing supports only narrow Java/JDK, Maven and
recognized project/historical expressions, not arbitrary fact categories, complete
project identity resolution or semantic equivalence. LLM outputs remain untrusted
and real-model quality is unverified. Review cache, opt-in mode and independent
four-call budget are process-local, not account-wide durable cost controls.
Version retention is bounded; file locking protects cooperating runtime writers,
not hostile external editors or OS-level filesystem races. The two-file candidate/
shared-library checkpoint limitation above remains explicit.
