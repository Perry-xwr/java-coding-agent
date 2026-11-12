# M2: session memory and request context

M2 keeps full storage history while selecting a smaller request copy. It does not
delete historical messages, summarize with an online model, or enable cross-session
memory. The implementation uses the existing `RequestContextSelector` boundary and
P0 provider-usage accounting rather than adding another Agent loop.

## Existing chain and new responsibilities

- Each `CliSessions` profile owns a separate `Agent.history`. Before M2, decisions
  normally sent the full selected profile history plus current project/plan context.
- Optional P1-B, default OFF, only removes exact duplicate old context or complete
  identical discovery exchanges. It is not a summarizer or a hard context limit.
- `CliSessionSnapshot` stores all three histories plus `CliWorkingContext`; the
  latter holds bounded file references/tool facts, not the conversation's prose.
- M2 adds an optional structured `SessionSummary` to that same v1 snapshot. It
  contains exact user quotations and runtime task states with source references.
- `Agent.completeDecision` applies M2 only when the CLI configures a scoped policy.
  When M2 is disabled, the existing P1-B/default full-history path remains available.
  When both are enabled, M2 is the single selection pass; P1-B is not applied again
  against message indices already changed by M2.

Managed conversations bind summaries to their UUID. Within a conversation, facts
and runtime states retain their CHAT/READ/CODE profile; the model sees only the
current profile's memory. Switching restores raw histories and summary together and
clears last-task metric views. Legacy `/load` explicitly imports raw histories into
a new managed identity and re-extracts facts rather than silently sharing a source
session's summary. No other conversations are searched for memories.

## Summary sources

`ExtractiveSessionSummarizer implements SessionSummarizer`. The interface is a future
extension point; no paid summarizer or model invocation is implemented.

Whole user messages are retained as `USER_TASK`, `EXPLICIT_CONSTRAINT`,
`EXPLICIT_DECISION`, or `PENDING_REQUEST` using visible textual markers. Classification
does not validate the user's statement or infer a preference. Each fact carries its
profile, original zero-based history index and SHA-256 of the source content. The
extractor never turns assistant prose into verified facts. Each request rechecks the
sources and regenerates extracts from its own raw history, so stale/forged summary
text is not used to replace a source message.

Task-state entries come from `AgentRunResult`, `TaskEvidence` and actual trajectory
source ids. They distinguish execution, mutation, verification and functional evidence.
`functional=NOT_RUN` stays NOT_RUN when no functional test was executed, regardless
of assistant claims. Historical PASS describes that past run, not the current disk
version or complete semantic satisfaction of a user's request. Failure diagnostics
remain in the protected raw turn; they are not duplicated as inferred explanations.

The model receives selected extracts in a labeled **user-data message**, never as a
new privileged system policy. Source order and hashes remain visible. Project data,
tool output and memory quotations do not grant tool permissions or R1 approvals.

## Selection and bounds

For short histories below the configured estimate, no summary message is injected.
For longer histories, M2 considers whole old discussion turns in chronological order.
It keeps the configured recent turns and the entire current running turn. It never
shortens individual file snapshots, tool arguments, results or user messages.

Protected raw content includes:

- The original system policy and current project/plan/runtime constraints.
- Every historical turn containing structured tools or tool results; call/result
  groups and full `read_file`/`expectedContent` data remain intact.
- System verification, guard and failure feedback and recorded unfinished/failed tasks.
- Code-fenced assistant output and obvious dependent follow-up turns with their
  preceding discussion. Explicit requests to recall earlier answers/history use full
  history rather than guessing from extracts.

An eligible old turn is replaced only if its exact user quotation can be represented
and the resulting request is actually smaller by the same estimator. The old assistant
discussion can then be omitted from that request copy. Long user messages (over 4096
Java characters) are retained raw rather than cut. Extract/task capacity is 256 each;
overflow preserves full context instead of deleting sources. These are conservative
eligibility bounds, not truncation of the stored transcript.

The budget is **soft**. Required snapshots, recent turns, system policy and tool schemas
can exceed it. Metrics report `SOFT_BUDGET_EXCEEDED...` and keep the evidence. M2 does
not force a smaller request by stripping necessary data. Tool-heavy sessions can therefore
remain expensive. There is no general semantic relevance ranking, automatic preference
inference, or guarantee that every useful old assistant discussion is identified. Use a
larger recent window or disable M2 when exact conversational recall is important.

## Configuration and commands

CLI defaults: M2 enabled, budget 8192 ESTIMATED tokens, three recent historical user
turns plus the complete current turn. Non-CLI/harness Agents retain their existing
behavior unless explicitly configured. Controlled live tests disable M2 to preserve
frozen experiment behavior and budgets.

```powershell
$env:AGENT_SESSION_MEMORY_ENABLED = 'true'  # false restores the previous request path
$env:AGENT_SESSION_MEMORY_BUDGET_ESTIMATED_TOKENS = '8192'
$env:AGENT_SESSION_MEMORY_RECENT_TURNS = '3'
```

Equivalent JVM properties are `agent.sessionMemory.enabled`,
`agent.sessionMemory.budgetEstimatedTokens`, and `agent.sessionMemory.recentTurns`;
properties take precedence over environment variables. Budget range is 128..1000000,
recent turns 1..20. Invalid values are reported without silently disabling guards.

```text
/memory status
/memory show
/memory compact
```

`status` shows the current scoped history count, a request preview, last task's
decision metrics, injected information types and available P0 usage records. Preview
performs no model request. `show` displays only the current session's source quotes
and historical runtime state. `compact` records a per-profile history boundary and
computes a safe preview; later requests reapply selection to that region without
permanently forcing compaction of all future turns. The boundary is persisted.
All commands make zero summary-model calls. `/history` retains its bounded terminal
display; full transcripts remain locally inspectable in the session JSON.

## Estimated versus reported usage

The estimator uses **ceil(serialized UTF-8 message/schema bytes / 4)**. Its label is
`ESTIMATED_UTF8_BYTES_DIV_4`; it is a heuristic, not an exact tokenizer, a billing
counter or a provider limit. Message metrics distinguish raw stored history entries
from selected history entries; the injected memory-data message is additional request
overhead. Estimates include message arguments and tool schemas. The context estimate
is not the exact full HTTP envelope byte count recorded by P0.

P0's `ModelRequestUsage` is unchanged: actual request sequences, bytes and server usage
remain provider-derived. `/memory status` labels those as `PROVIDER_REPORTED` and shows
UNKNOWN for missing fields or absent records. Mock provider usage in tests is simulation,
not real API consumption. M2 does not estimate currency or claim guaranteed total-cost
reduction, cache-hit improvement or model task-quality improvement. M5 needs real paired
measurements for those conclusions. Context metrics count model decision requests;
the existing HTTP-attempt budget remains the authority for actual network exchanges.

## Persistence and safety

The existing v1 snapshot accepts the optional `summary` field; constructors and readers
still support old snapshots with no summary. A malformed optional summary is discarded
without discarding raw history; invalid history/version/tool association still fails
closed. A managed summary from a different UUID is discarded. Re-extraction then works
only from the loaded raw history. Atomic save failure leaves full in-memory history
available under the existing M1 behavior. These are backward-reading guarantees; an old
binary may not understand new optional fields.

No API credentials/configuration are added to summaries. Session files remain local,
unencrypted user data. Existing `.agent-cli` file-tool restrictions, workspace boundaries,
R1 approval, R2 fresh-read/repair limits, Diff capture, exact edits and provider adapters
are unchanged. Cross-session memory is OFF, with no shared store, embeddings or retrieval.
Approved host project code remains outside an OS sandbox, as already documented in M1.

## Offline validation (2026-10-25)

Targeted command:

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dtest=SessionContextMemoryTest,ManagedConversationTest,CliSessionStoreTest,InteractiveCliSessionTest,RequestContextSelectorTest' test
```

Final result: **52 tests, 0 failures, 0 errors, 0 skipped**. This includes 18 M2
tests: short/full context, representative long discussion, protected snapshots and
tool pairs, active failure feedback, unfinished state, forged/foreign facts, unsupported
test-PASS metadata, manual compaction scope, explicit recall fallback, oversize sources,
summary-capacity fallback, A/B/profile isolation, restart, old/corrupt summary loading,
CLI commands without model calls, mock usage separation, disabled selection and actual
temporary file patch/stale-snapshot rejection.

Broader regression command:

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Final result: **656 tests, 0 failures, 0 errors, 14 skipped, BUILD SUCCESS**.
The same two loopback HTTP classes as M1 (eight tests) were excluded. Skipped tests
include disabled real integrations and unavailable symlink operations. The selection
includes DeepSeek/GLM in-memory streaming tests, Usage parsing, R1/R2 FakeRunner tests,
three-turn editing, Diff, project rules and original session tests. No real model API,
production CLI or existing Calculator experiment was executed.

The representative pure message/schema fixture measured:

| Metric | Full history | M2 request |
|---|---:|---:|
| Stored-history messages included | 36 | 6 |
| Total model messages | 36 | 7 (one extract message added) |
| UTF-8 bytes/4 ESTIMATED tokens | 42,720 | 5,881 |
| Configured soft estimate budget | 8,192 | 8,192 |

The raw 36 messages remained unchanged. The early Java 17 and no-test-edit constraints
were present with source hashes, as were both most recent original turns. This is an
**86.2% reduction of that synthetic estimator**, not a measured provider-token or cost
reduction. Separate protected-content fixtures deliberately exceed their soft budget
and retain the full snapshots/failures.

The equal-task FakeLLM comparison made one model decision in both full-history and M2
modes. Mock telemetry reported 7 input/2 output/9 total in both; those fixture numbers
remain independent of local estimates and are not real provider usage.

## Files changed for M2

- `src/main/java/com/agent/memory/SessionSummary.java`
- `src/main/java/com/agent/memory/SessionSummarizer.java`
- `src/main/java/com/agent/memory/ExtractiveSessionSummarizer.java`
- `src/main/java/com/agent/memory/SessionContextConfig.java`
- `src/main/java/com/agent/memory/SessionRequestContext.java`
- `src/main/java/com/agent/memory/SessionContextMetrics.java`
- `src/main/java/com/agent/agent/RequestContextSelector.java`
- `src/main/java/com/agent/agent/Agent.java`
- `src/main/java/com/agent/InteractiveCli.java`
- `src/main/java/com/agent/SessionMemory.java`
- `src/main/java/com/agent/CliSessionSnapshot.java`
- `src/main/java/com/agent/CliSessionStore.java`
- `src/main/java/com/agent/CliConversationManager.java`
- `src/test/java/com/agent/memory/SessionContextMemoryTest.java`
- `README.md`, `SPEC.md`, `docs/memory_m2_context.md`
