# M4: user-controlled long-term memory management

M4 extends the existing `CrossSessionMemory`, `LocalCrossSessionMemory` and
`SharedMemoryStore`. There is one library, `.agent-cli/memory/shared.json`, with
the same version-1 schema, workspace scope and private file-tool boundary as M3.
The Agent loop, M2 selector, M3 exposure filtering and R1/R2 are unchanged.

## User commands

All shared management requires the active conversation's global memory ON.
OFF denies access before library I/O, including entry counting. It does not
disable the independent Session Memory.

| Command | Behavior |
| --- | --- |
| `/memory global on` | Explicitly enable shared access in this session. |
| `/memory list` | Stable-ID order; ID, content summary, original source session and update time. |
| `/memory search Java` | Existing M3 lexical ranking, minimum score, top three; includes scores. |
| `/memory update <full-id> <new-text>` | Exact canonical UUID only; replace that statement atomically. |
| `/memory forget <full-id>` | Delete exactly one scoped entry atomically. |
| `/memory status` | Session context metrics plus shared count only when authorized. |
| `/memory global status` | Consent and authorized count, or NOT_ACCESSED when OFF. |
| `/memory global off` | Revoke shared access; retain session-local history and memory. |

Commands are handled directly by `InteractiveCli`; no model-callable management
tool is registered. Lists show at most 160 content characters per entry, with
an explicit summary truncation marker; control/format characters are suppressed
for terminal display. Library size remains bounded by M3 (1000 entries, 8 MiB).
Search is deliberately top-three, not an exhaustive full-library text search.

## Update, duplicates and deletion

Update preserves the stable ID, original `sourceSessionId`, creation timestamp,
source type, sharing authorization and workspace scope. A changed statement
gets a strictly later update timestamp. Repeating the same update is idempotent
and leaves its timestamp unchanged. The original provenance identifies the
original authorized source, not a claim that its original message was rewritten.

Blank, whitespace-only, oversized, multiline/control-containing and obvious
credential-like text is rejected using M3 validation. Missing, abbreviated,
malformed or foreign-workspace IDs cannot modify other entries. No fuzzy-ID
selection is used.

Exact same-content, same-workspace `remember` or update-to-another-entry is
rejected with the existing ID; it does not silently append duplicates or merge
provenance. Equality is exact, not semantic or whitespace-normalized. Existing
duplicate content from older data is not silently removed. Different or
conflicting statements remain independent until the user explicitly manages them.

Forget removes the shared entry only. **It does not erase original conversation
messages, summaries, prior files, terminal scrollback or external copies.** The
CLI explicitly states this scope; deletion is not comprehensive data erasure.

## Persistence, failures and immediate effect

Each mutation first reads and validates the entire existing library, creates a
replacement in memory, and uses the existing same-directory temporary write and
atomic replacement. Invalid JSON/version/provenance is never treated as an empty
library that can be overwritten. Rejection and simulated write failures preserve
the original library bytes. No global Maven/configuration changes are involved.

Retrieval reads the current library for each new task, without a cross-task
cache. Successful CLI update/delete also clears active request candidates and
last-request metrics. The M3 exposure marks are retained: an old assistant echo,
tool exchange or summary source from a shared-memory-exposed turn cannot flow
back into later model requests, even after its shared entry is changed/deleted
or after restarting. Full raw histories are retained for the user.

Current user instructions and runtime permissions still take precedence over
low-trust shared data. The M3 512 ESTIMATED token injection cap and M2 overall
soft budget are unchanged. Retrieval hits do not prove model understanding or
task correctness. No additional model requests are made for management.

## Offline acceptance and demonstration

`MemoryManagementTest` captures actual FakeLLM requests:

1. A enables global and explicitly saves `项目使用 Java 17`.
2. B enables global and its real captured request contains that statement.
3. User updates the same ID to `项目使用 Java 21`; the next captured request
   contains only the new shared statement, with no prior deliberately echoed reply.
4. User forgets the ID; the next request has no shared block or old echoed replies.
5. Restart/switch retains consent while updated/deleted library state and exposure
   filtering remain effective. Original source snapshot bytes remain unchanged.

Additional tests cover list/search metadata, OFF zero-I/O, exact IDs and scopes,
timestamps, duplicate/conflicting content, invalid input, partial temporary-write
failure, corrupt JSON, counts and user-facing deletion scope. Existing M3 tests
cover malicious-memory permission denial and M1/M2 isolation.

A zero-model-request manual management demo inside an already running CLI:

```text
/memory global on
/memory remember 项目使用 Java 17
/memory list
/memory search Java
/memory update <paste-the-returned-full-id> 项目使用 Java 21
/memory search Java
/memory forget <paste-the-same-full-id>
/memory search Java
/memory global off
/memory list
```

Natural-language questions would invoke the configured model; this implementation
run used FakeLLM instead and did not start a real Agent/model experiment.

## Limitations

The library is local plaintext, without encryption, authentication, multi-process
transactions or semantic conflict resolution. As in M3, another process with the
same user permissions can edit files; ordinary file-tool privacy is not an OS
sandbox. Credential detection is heuristic. Exposed-turn withholding can require
fresh file reads in future edits; OFF/delete cannot undo previously authorized
workspace changes. M5 effects/cost evaluation and automatic extraction are not
implemented.

## Files changed in M4

- `src/main/java/com/agent/CrossSessionMemory.java`
- `src/main/java/com/agent/SharedMemoryStore.java`
- `src/main/java/com/agent/memory/LocalCrossSessionMemory.java`
- `src/main/java/com/agent/InteractiveCli.java`
- `src/test/java/com/agent/MemoryManagementTest.java`
- `SPEC.md`, `README.md`, this report.

## Recorded offline validation (2026-10-25)

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dtest=MemoryManagementTest,CrossSessionMemoryTest,SessionContextMemoryTest,ManagedConversationTest,CliSessionStoreTest,SessionDataPrivacyTest' test
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

- New M4 tests: 13 passed.
- Targeted selection: 67 total, 66 passed, 0 failures, 0 errors, 1 skipped.
- Safe regression selection: 684 total, 670 passed, 0 failures, 0 errors,
  14 skipped; BUILD SUCCESS. This includes M1/M2/M3, Unified Agent, R1/R2,
  streaming, TaskEvidence, Diff and usage regression coverage available offline.
- The same two classes / eight loopback HTTP tests excluded in M3 remain
  unexecuted due to the recorded environment restriction; these are not counted
  as passed. Real GLM integration is disabled. This is not an unqualified full-suite claim.
- `git diff --check` and the README M4 relative-link target check passed.
- Zero real provider requests; no original Calculator experiment touched.
  Existing dirty work is retained; no branch switch, commit or push. M5 not started.
