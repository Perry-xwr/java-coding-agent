# M6-B: derived embeddings and hybrid retrieval

The sole authority remains `.agent-cli/memory/shared.json`. Pending candidates,
private conversation histories and session summaries are never indexed. No ANN,
vector database, downloaded model, or M6-C update logic is introduced.

## Commands and default

`/memory retrieval keyword|semantic|hybrid|status` selects a persisted per-session
mode (old snapshots default keyword). Global OFF denies changes and skips shared
retrieval, vector lookup and embedding calls. Status shows mode plus aggregate
process metrics, not cached vectors or content. Controlled live tests retain their
existing command restrictions. Existing file-tool restrictions cover `.agent-cli`.

Every retrieval reads the authorized, current-workspace library first. Keyword
ranking remains unchanged. Semantic search normalizes vectors, computes cosine,
filters positive similarities at the configured threshold, and sorts by score then
stable UUID. Hybrid uses reciprocal rank fusion `1/(60+rank)` over each ranked list,
deduplicates UUIDs and breaks ties by UUID; raw score scales are never added.
All paths return at most three hits and retain M2/M3 request injection limits.

`-Dagent.memory.semanticThreshold=0.5` adjusts the initial cosine threshold (0..1).
0.5 is an initial test setting, not a universally meaningful semantic confidence.
Real model/domain calibration has not been performed.

## Cache and consistency

`SemanticMemoryIndex` is a small **process-local** derived cache, not a second
persistent database. Entries include cache version 1, UUID, SHA-256 content hash,
provider/model identity, dimensions and normalized vector data. Query vectors use
query hashes. No cache file is created, so restart rebuilds lazily rather than
loading possibly corrupt disk vectors.

Content hash, provider identity and dimensions must match before reuse. Update
therefore makes the old vector unusable immediately; forget excludes the ID from
the next authoritative candidate set even if bytes remain in RAM temporarily.
Changing model/dimensions cannot reuse old vectors. Invalid vectors/cache entries,
provider failure or exhausted budget produce keyword fallback without modifying
shared content. Deleted, unapproved and foreign-scope entries cannot be revived by
cache data. New authorized saves/approvals are embedded lazily on next retrieval.

There is no multi-process transaction or semantic conflict resolution. Existing
M3 exposed-turn withholding prevents historical model echoes returning after OFF,
update or deletion. This does not undo prior user-authorized file changes.

## Provider and cost boundaries

`EmbeddingProvider` is independent of chat clients. `FakeEmbeddingProvider` accepts
explicit fixture vectors and is used only in tests, never as a production semantic
fallback. Real HTTP requires all of:

```text
AGENT_EMBEDDING_ENABLED=true
EMBEDDING_ENDPOINT=https://<trusted-provider>/<embedding-path>
EMBEDDING_MODEL=<embedding-model>
EMBEDDING_API_KEY=<secret>
EMBEDDING_DIMENSIONS=<1..4096>
```

These settings are independent of DeepSeek/GLM; no assumption that their chat
endpoints provide embeddings. Missing/invalid config means no provider/no request
and keyword fallback. Merely changing retrieval mode does not enable a real API.
HTTPS only; no credentials in URL, query/fragment, redirects, tools or application
retry loop. Keys are used only in Authorization, not cache identity or diagnostics.

Connect timeout is 10 seconds; request timeout is 20 seconds, with an additional
20-second bounded response-body read. Input maximum 2048 characters, response maximum
256 KiB, dimensions maximum 4096. Null, nonnumeric/nonfinite, zero or wrong-length
vectors are rejected. These are application-level limits; this implementation
does not claim an OS sandbox or a physical network-transmission hard cap for Java
HTTP internals. No real HTTP integration request was performed here.

The index caps uncached provider calls at 64 and total uncached input at 32768
characters per CLI manager/process lifetime, with no retry loop after failure.
Budgets reset on restart; they are not a durable account spending cap. Calls,
hits, failures and accumulated latency are available through retrieval status.
If the HTTP response supplies valid usage.total_tokens, status records the **last
provider-reported total**, not an invented aggregate; otherwise usage is UNKNOWN.
Fake vectors and bytes/characters are not billing-token measurements.

## Offline evaluation

Three Chinese/Java query fixtures each have one relevant memory plus unrelated
memories: exact `Java 17`, paraphrased `验证新增行为`, and `诊断信息本地化`.
Vectors are explicitly hand-assigned test fixtures. Captured results:

| Mode | Hit@3 | MRR |
| --- | --- | --- |
| Keyword | 1/3 | 0.3333 |
| Semantic (Fake) | 3/3 | 1.0 |
| Hybrid (Fake) | 3/3 | 1.0 |

These numbers verify process/ranking only, not real embedding quality or an
empirical model improvement. `SemanticMemoryTest` also checks invalid vectors,
cache corruption, update/delete, dimensional changes, stable deduplication, snapshot
mode round-trip and actual FakeLLM CLI requests under global OFF/ON/OFF.

M3/M4/M6-A regression remains responsible for approved-only data admission,
permissions, old-history filtering and candidate isolation. The context selector
and its 512 ESTIMATED token shared-data limit are unchanged.

## Changed files

New: `EmbeddingProvider`, `FakeEmbeddingProvider`, `HttpEmbeddingProvider`,
`SemanticMemoryIndex`, `SemanticMemoryTest`, this report.
Updated: `CrossSessionState`, `LocalCrossSessionMemory`, `CliConversationManager`,
`InteractiveCli`, `README.md`, `SPEC.md`. Agent loop, R1/R2, editing algorithms,
Calculator experiment records and existing shared JSON format are unchanged.

## Recorded validation — 2026-11-01

```powershell
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dtest=SemanticMemoryTest,AutoMemoryFlowTest,MemoryManagementTest,CrossSessionMemoryTest' test
mvn -o '-Dmaven.repo.local=.\.m2\repository' '-Dglm.integration=false' '-Dtest=*,!OpenAiCompatibleClientTest,!AgentRequestUsageTest' test
```

Final targeted: 40 tests, zero failures/errors/skips. New semantic tests: eight,
including similar-memory ties, changed model identity, and corrupted derived data.
Safe regression: 699 tests, zero failures/errors, 14 skipped, BUILD SUCCESS.
The same two classes/eight loopback HTTP tests excluded in previous stages remain
unexecuted; they are not counted as passed. Real GLM integration is off. Actual
DeepSeek/GLM/embedding requests: zero. `git diff --check` passed. No commit, push,
branch change, cleanup, original experiment modification or M6-C work occurred.
