package com.agent;

import com.agent.llm.*;
import com.agent.memory.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Daily controller integration coverage for segmented semantic Memory. */
class MemorySemanticV2Test {
    // This suite validates the retained legacy semantic extractor and opts into it explicitly.
    private String previousManagedMode;
    @BeforeEach void selectLegacyExtractor(){previousManagedMode=System.getProperty("agent.memory.agentManaged");System.setProperty("agent.memory.agentManaged","false");}
    @AfterEach void restoreManagedMode(){if(previousManagedMode==null)System.clearProperty("agent.memory.agentManaged");else System.setProperty("agent.memory.agentManaged",previousManagedMode);}
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final DailyRuntimeTest runtime = new DailyRuntimeTest();

    private JsonNode body(Request request) throws IOException {
        var bytes = new okio.Buffer(); request.body().writeTo(bytes); return json.readTree(bytes.readUtf8());
    }
    private boolean extraction(JsonNode body) {
        return body.path("messages").get(0).path("content").asText().startsWith("Classify each user-authored sentence");
    }
    private MemoryExtractor.Source source(JsonNode body) throws IOException {
        return json.treeToValue(json.readTree(body.path("messages").get(1).path("content").asText()).get(0), MemoryExtractor.Source.class);
    }
    private Map<String,Object> proposal(String content, MemoryExtractor.Source source, double confidence) {
        return Map.of("proposedContent", content, "category", "PREFERENCE", "sourceMessageRefs", List.of(source.messageRef()),
                "evidenceQuote", content, "confidence", confidence, "proposedAction", "PROPOSE");
    }
    private Response extractionResponse(Request request, List<Map<String,Object>> proposals) throws IOException {
        String payload = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(proposals)), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline extraction")
                .body(ResponseBody.create(payload, MediaType.get("application/json"))).build();
    }
    private Response malformedExtractionResponse(Request request){
        String payload="{\"choices\":[{\"message\":{\"content\":\"{broken\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}";
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline malformed extraction")
                .body(ResponseBody.create(payload,MediaType.get("application/json"))).build();
    }
    private void authorize(TuiSessionController controller, Path settings) {
        controller.enableMemoryConsent(new MemoryConsentStore(settings));
        assertTrue(controller.memoryCommand("smart on")); controller.resolveMemoryConsent(true);
        assertTrue(controller.memoryCommand("semantic on")); controller.resolveMemoryConsent(true);
    }
    private void finish(TuiSessionController controller, DailyAgentAdapter adapter) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((controller.semanticMemoryBusy() || controller.loop().snapshot().current().turn().running() || !adapter.available()) && System.nanoTime() < deadline) {
            controller.pump(); Thread.sleep(3);
        }
        controller.pump(); assertFalse(controller.semanticMemoryBusy()); assertTrue(adapter.available());
    }
    private void send(TuiSessionController controller, DailyAgentAdapter adapter, String text) throws Exception {
        runtime.send(controller, adapter, text); finish(controller, adapter);
        assertEquals("COMPLETED", controller.loop().snapshot().current().turn().status(), () -> controller.loop().snapshot().notice());
        assertTrue(controller.loop().snapshot().current().turn().tools().isEmpty(), "semantic extraction and Memory injection must not rely on UX-4 history tools");
    }

    @Test void naturalLanguageWithoutTemplateGetsSemanticReviewAndMalformedSiblingIsIsolated() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("natural"));
        Path settings = root.resolve("user/natural-consent.json");
        String user = "遇到陌生技术路线时，我通常会先理解它的适用条件和替代方案差异，再决定是否采用。";
        var extractionCalls = new AtomicInteger(); var requestCalls = new AtomicInteger(); var injected = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try {
                     JsonNode payload = body(request); requestCalls.incrementAndGet();
                     if (extraction(payload)) {
                         extractionCalls.incrementAndGet(); var source = source(payload);
                         var good = proposal(user, source, .99);
                         var bad = new LinkedHashMap<>(proposal("今天网络很慢", source, .99));
                         bad.put("evidenceQuote", "这段文字并不存在于原文");
                         return extractionResponse(request, List.of(good, bad));
                     }
                     if (payload.toString().contains("CROSS_SESSION_MEMORY_DATA")) { injected.incrementAndGet(); assertTrue(payload.toString().contains(user)); }
                     return runtime.answer(request, "已了解你的技术路线讨论习惯。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null); var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings);
            assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CHAT));
            send(controller, adapter, user);
            var diag = new MemoryRunDiagnostics(workspace).latest();
            assertNotNull(diag); assertEquals("COMPLETED", diag.status()); assertEquals(1, extractionCalls.get());
            assertEquals(1, diag.semanticCandidates()); assertEquals(1, diag.effective()); assertEquals(0, diag.pending()); assertEquals(1, diag.rejected());
            assertEquals(1, new CliConversationManager(workspace).load(controller.loop().snapshot().currentSessionId()).snapshot().extraction().candidates().stream().filter(c -> c.reviewStatus().equals("APPROVED")).count());
            assertEquals(user,new SharedMemoryStore(workspace).read().get(0).content());
            assertTrue(controller.createSession());
            send(controller,adapter,"讨论陌生技术路线时，应该先比较什么？");
            assertEquals(1,injected.get(),"new session request receives the effective semantic preference without history search");
        }
        assertEquals(3, requestCalls.get());
    }

    @Test void labeledCommunicationPreferenceAutoSavesBesideJavaMemoryAndInjectsInNewSession() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("labeled-communication"));
        Path settings = root.resolve("user/labeled-communication-consent.json");
        String user = "长期沟通偏好：讨论复杂问题时，请先给结论，再说明理由和步骤。只讨论，不修改工作区中的文件。";
        String preference = "讨论复杂问题时，请先给结论，再说明理由和步骤";
        assertTrue(MemoryQualityPolicy.entailed(preference, user), "the colon after a user-authored preference label bounds the exact quote");
        assertTrue(MemoryQualityPolicy.semanticPreferenceAutoEligible(preference, preference, user));
        assertTrue(new DeterministicMemoryExtractor().extract(List.of(new MemoryExtractor.Source("CODE:1", user))).stream()
                .noneMatch(p -> p.proposedContent().equals("长期沟通偏好：讨论复杂问题时")),
                "a label fragment must not become a misleading PROJECT_CONSTRAINT candidate");

        var java17 = new LongTermMemory(UUID.randomUUID().toString(), "以后这个项目统一使用 Java 17",
                UUID.randomUUID().toString(), java.time.Instant.now().toString(), java.time.Instant.now().toString(),
                "USER_MEMORY_COMMAND", "EXPLICIT_USER", workspace.toAbsolutePath().normalize().toString(), null);
        new SharedMemoryStore(workspace).append(java17);
        var requestCalls = new AtomicInteger(); var extractionCalls = new AtomicInteger(); var injected = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try {
                     JsonNode payload = body(request); requestCalls.incrementAndGet();
                     if (extraction(payload)) {
                         extractionCalls.incrementAndGet(); MemoryExtractor.Source src = source(payload);
                         assertEquals(user, src.userText());
                         var full = new LinkedHashMap<>(proposal(preference, src, .99));
                         full.put("category", "PROJECT_CONSTRAINT");
                         return extractionResponse(request, List.of(full, proposal("长期沟通偏好：讨论复杂问题时", src, .99)));
                     }
                     if (payload.toString().contains("CROSS_SESSION_MEMORY_DATA") && payload.toString().contains(preference)) injected.incrementAndGet();
                     return runtime.answer(request, "已按当前偏好先给结论，再说明理由和步骤。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null); var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings); assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CODE));
            send(controller, adapter, user);
            var diag = new MemoryRunDiagnostics(workspace).latest();
            assertNotNull(diag); assertEquals("COMPLETED", diag.status()); assertEquals(1, diag.semanticCandidates());
            assertEquals(1, diag.effective()); assertEquals(0, diag.pending()); assertEquals(1, diag.rejected());
            var entries = new SharedMemoryStore(workspace).read();
            assertEquals(2, entries.size()); assertTrue(entries.stream().anyMatch(m -> m.content().equals("以后这个项目统一使用 Java 17")));
            assertTrue(entries.stream().anyMatch(m -> m.content().equals(preference)));
            var extractionState = new CliConversationManager(workspace).load(controller.loop().snapshot().currentSessionId()).snapshot().extraction();
            assertTrue(extractionState.candidates().stream().anyMatch(c -> c.reviewStatus().equals("APPROVED") && c.category().equals("PREFERENCE") && c.candidateContent().equals(preference)));
            assertFalse(extractionState.candidates().stream().anyMatch(c -> c.candidateContent().equals("长期沟通偏好：讨论复杂问题时")));

            assertTrue(controller.createSession()); assertTrue(controller.dailyProfile(CliMode.CHAT));
            send(controller, adapter, "我有个复杂问题：请解释一下缓存。");
            assertEquals(1, injected.get(), "the new-session model request must contain the saved preference, without history search");
            assertEquals(1, extractionCalls.get()); assertEquals(3, requestCalls.get());
        }
        // Restart the actual Daily bridge and use only shared retrieval, never UX-4.
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 var payload = body(request);
                 assertTrue(payload.toString().contains("CROSS_SESSION_MEMORY_DATA"));
                 assertTrue(payload.toString().contains(preference));
                 assertFalse(payload.toString().contains("长期沟通偏好：讨论复杂问题时\""));
                 return runtime.answer(request, "结论：缓存用于提高重复读取效率。理由和步骤如下。");
             }); var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.enableMemoryConsent(new MemoryConsentStore(settings));
            assertTrue(controller.createSession());
            send(controller, adapter, "请解释一个复杂问题：缓存是什么？");
            assertEquals(1, client.chatAttempts());
        }
    }

    @Test void malformedSemanticResponseIsReportedAsParserFailureNotRejectedCandidate() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("parser-diagnostic"));Path settings=root.resolve("user/parser-diagnostic-consent.json");
        String user="长期沟通偏好：讨论复杂问题时，请先给结论，再说明理由和步骤。只讨论，不修改文件。";
        try(var lease=DailyWorkspace.acquire(workspace);
            var client=DailyDeepSeekClient.offline(request->{
                try{JsonNode payload=body(request);if(extraction(payload))return malformedExtractionResponse(request);
                    return runtime.answer(request,"已收到，本轮只讨论，不修改文件。");
                }catch(Exception failure){throw new IOException(failure);}
            });var adapter=new DailyAgentAdapter(lease,client,null);var controller=new TuiSessionController(workspace,true,adapter)){
            controller.createSession();authorize(controller,settings);assertTrue(controller.memoryCommand("global on"));
            send(controller,adapter,user);
            var diagnostic=new MemoryRunDiagnostics(workspace).latest();assertNotNull(diagnostic);
            assertEquals("FAILED",diagnostic.status());assertEquals("SEMANTIC_PARSER_ERROR",diagnostic.reason());
            assertEquals("PARSER_ERROR",diagnostic.failureCode());assertEquals(0,diagnostic.rejected());assertTrue(diagnostic.rejectionReasons().isEmpty());
            assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
            assertTrue(controller.memoryCommand("semantic status"));
            var history=controller.loop().snapshot().current().history();String status=history.get(history.size()-1).text();
            assertTrue(status.contains("FAILURE_CODE=PARSER_ERROR"),status);
            assertTrue(status.contains("REJECTION_REASONS=NONE"),status);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status(),"memory extraction failure must not fail chat");
        }
    }

    @Test void rejectionReasonCountsPersistAndMustMatchTotal() throws Exception {
        var temp=Files.createDirectory(root.resolve("diagnostic-store"));
        var entry=new MemoryRunDiagnostics.Entry(UUID.randomUUID().toString(),java.time.Instant.now().toString(),
                "COMPLETED","SEMANTIC_RESULT_VALIDATED",0,1,0,0,1,"NONE",Map.of("INCOMPLETE_CANDIDATE",1));
        var store=new MemoryRunDiagnostics(temp);store.record(entry);
        assertEquals(Map.of("INCOMPLETE_CANDIDATE",1),store.latest().rejectionReasons());
        assertThrows(IllegalArgumentException.class,()->new MemoryRunDiagnostics.Entry(UUID.randomUUID().toString(),
                java.time.Instant.now().toString(),"COMPLETED","test",0,0,0,0,2,"NONE",Map.of("INVALID_SCHEMA",1)));
        Path legacy=Files.createDirectory(root.resolve("legacy-diagnostic-store"));Path file=legacy.resolve(".agent-cli/memory/automatic-memory-diagnostics.json");
        Files.createDirectories(file.getParent());Files.writeString(file,"{\"version\":1,\"entries\":[{\"sessionId\":\""+UUID.randomUUID()+"\",\"at\":\""+java.time.Instant.now()+"\",\"status\":\"COMPLETED\",\"reason\":\"old\",\"ruleCandidates\":0,\"semanticCandidates\":0,\"effective\":0,\"pending\":0,\"rejected\":1,\"errorCategory\":\"NONE\"}]}");
        var legacyEntry=new MemoryRunDiagnostics(legacy).latest();assertEquals(Map.of("UNCLASSIFIED",1),legacyEntry.rejectionReasons());
        assertEquals("NONE",legacyEntry.failureCode());
        assertEquals("UNEXPECTED_ERROR",TuiSessionController.semanticFailureCode(new IllegalStateException("controlled mock exception")));
        assertEquals("PROVIDER_ERROR",TuiSessionController.semanticFailureCode(new IOException("controlled mock I/O failure")));
        assertEquals("PARSER_ERROR",TuiSessionController.semanticFailureCode(new LlmMemoryExtractor.ExtractionFailure("PARSER_ERROR","controlled parser failure")));
    }

    @Test void localTemporaryNoRememberDoesNotSuppressAdjacentDurablePreferencesAndNewSessionGetsOnlyEffectiveMemory() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("mixed")); Path settings = root.resolve("user/mixed-consent.json");
        String text = "以后项目统一使用 Java 17。\r\n以后增加功能前先分析风险。\r\n以后讨论新功能时，我更愿意先看各方案的实际取舍再听推荐理由。今天 Maven 下载慢，只是临时情况，不需要长期记住。";
        String semanticPreference="以后讨论新功能时，我更愿意先看各方案的实际取舍再听推荐理由";
        assertTrue(MemoryQualityPolicy.entailed(semanticPreference,text));
        assertTrue(MemoryQualityPolicy.evidenceAllowed(semanticPreference,text));
        assertTrue(MemoryQualityPolicy.semanticPreferenceAutoEligible(semanticPreference,semanticPreference,text));
        var chats = new AtomicInteger(); var extractions = new AtomicInteger(); var injected = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try {
                     JsonNode payload = body(request);
                     if (extraction(payload)) {
                         extractions.incrementAndGet(); var src = source(payload);
                         assertEquals(text.replace("\r\n", "\n"), src.userText());
                         return extractionResponse(request, List.of(proposal("以后讨论新功能时，我更愿意先看各方案的实际取舍再听推荐理由", src, .99)));
                     }
                     chats.incrementAndGet();
                     String body = payload.toString();
                     if (body.contains("CROSS_SESSION_MEMORY_DATA")) {
                         injected.incrementAndGet(); assertTrue(body.contains("Java 17"));
                         assertFalse(body.contains("Maven 下载慢"));
                     }
                     return runtime.answer(request, "已按当前有效约定回答。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null); var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings); assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CHAT));
            send(controller, adapter, text);
            var entries = new SharedMemoryStore(workspace).read();
            assertEquals(2, entries.size()); assertTrue(entries.stream().anyMatch(m -> m.content().contains("Java 17")));
            assertTrue(entries.stream().anyMatch(m -> m.content().contains("增加功能前先分析风险")));
            assertFalse(entries.stream().anyMatch(m -> m.content().contains("Maven 下载慢") || m.content().contains("不需要长期记住")));
            var state = new CliConversationManager(workspace).load(controller.loop().snapshot().currentSessionId()).snapshot().extraction();
            var diagnostic = new MemoryRunDiagnostics(workspace).latest();
            assertEquals(1, state.candidates().stream().filter(c -> c.reviewStatus().equals("PENDING")).count(),
                    () -> state.candidates().stream().map(c -> c.reviewStatus()+":"+c.candidateContent()).toList()+" diag="+diagnostic+" semanticCalls="+extractions.get());
            assertEquals(2,new SharedMemoryStore(workspace).read().size(),"two deterministic preferences become effective; unresolved relation for the novel preference stays pending");
            assertTrue(controller.memoryCommand("semantic off"));
            assertTrue(controller.createSession()); assertTrue(controller.loop().snapshot().current().memory());
            send(controller, adapter, "请解释项目 Java 17 和新增功能前风险评估约定。");
            assertEquals(1, injected.get()); assertEquals(1, extractions.get()); assertEquals(2, chats.get());
        }
    }

    @Test void explicitNewWeeklyDecisionIsSavedBesideExistingJavaMemoryAndInjectedInNewSession() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("weekly-decision"));
        Path settings = root.resolve("user/weekly-consent.json");
        String content = "周一到周五准备大模型实习，周六周日休息";
        String quote = "我们约定" + content;
        String sourceText = quote + "。";
        String sessionId = UUID.randomUUID().toString();
        new LocalCrossSessionMemory(new SharedMemoryStore(workspace), () -> true, workspace)
                .rememberAuthorized(sessionId, "以后这个项目统一使用 Java 17");
        var extractionCalls = new AtomicInteger();
        var injectedCalls = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try {
                     JsonNode payload = body(request);
                     if (extraction(payload)) {
                         extractionCalls.incrementAndGet();
                         var src = source(payload);
                         var proposal = new LinkedHashMap<>(proposal(content, src, .99));
                         proposal.put("category", "DECISION");
                         proposal.put("evidenceQuote", quote);
                         return extractionResponse(request, List.of(proposal));
                     }
                     String serialized = payload.toString();
                     if (serialized.contains("CROSS_SESSION_MEMORY_DATA")) {
                         injectedCalls.incrementAndGet();
                         assertTrue(serialized.contains(content), "new session must receive the stored schedule");
                     }
                     return runtime.answer(request, "已根据有效的每周安排回答。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings);
            assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CHAT));
            send(controller, adapter, sourceText);

            var store = new SharedMemoryStore(workspace);
            var entries = store.read();
            assertEquals(2, entries.size(), "the prior Java decision remains and the new schedule is added");
            assertTrue(entries.stream().anyMatch(m -> m.content().equals("以后这个项目统一使用 Java 17")));
            var saved = entries.stream().filter(m -> m.content().equals(content)).findFirst().orElseThrow();
            assertEquals("SEMANTIC_EXTRACTED_USER_QUOTE", saved.sourceType());
            var savedSession = new CliConversationManager(workspace).load(controller.loop().snapshot().currentSessionId());
            var candidate = savedSession.snapshot().extraction().candidates().stream()
                    .filter(c -> c.candidateContent().equals(content)).findFirst().orElseThrow();
            assertEquals("DECISION", candidate.category());
            assertEquals("APPROVED", candidate.reviewStatus());
            assertEquals(1, extractionCalls.get());
            assertEquals(1, new MemoryRunDiagnostics(workspace).latest().effective());

            var duplicate = new LongTermMemory(UUID.randomUUID().toString(), content, saved.sourceSessionId(),
                    java.time.Instant.now().toString(), java.time.Instant.now().toString(), saved.sourceType(),
                    saved.sharingAuthorization(), saved.workspaceScope(), saved.sourceMessageReference());
            assertFalse(store.applySemantic(duplicate, sourceText, UUID.randomUUID().toString(), "DECISION", quote));
            String conflictingContent = "周一到周五学习英语，周六周日休息";
            var conflict = new LongTermMemory(UUID.randomUUID().toString(), conflictingContent, saved.sourceSessionId(),
                    java.time.Instant.now().toString(), java.time.Instant.now().toString(), saved.sourceType(),
                    saved.sharingAuthorization(), saved.workspaceScope(), saved.sourceMessageReference());
            assertThrows(IOException.class, () -> store.applySemantic(conflict, "我们约定" + conflictingContent,
                    UUID.randomUUID().toString(), "DECISION", "我们约定" + conflictingContent));
            assertEquals(2, store.read().size(), "duplicate and conflicting schedules cannot alter the effective library");

            assertEquals(ConflictDetector.Relation.DUPLICATE,
                    MemoryQualityPolicy.relation(content, entries));
            var otherSchedule = new LongTermMemory(UUID.randomUUID().toString(), "周一到周五学习英语，周六周日休息",
                    sessionId, java.time.Instant.now().toString(), java.time.Instant.now().toString(),
                    "USER_MEMORY_COMMAND", "EXPLICIT_USER", workspace.toAbsolutePath().normalize().toString(), null);
            assertEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,
                    MemoryQualityPolicy.relation(content, List.of(otherSchedule)));

            assertTrue(controller.createSession());
            send(controller, adapter, "周一到周五准备大模型实习，周六周日休息的安排是什么？");
            assertEquals(1, injectedCalls.get(), "retrieval/injection must work without history-search tools");
            assertTrue(controller.loop().snapshot().current().turn().tools().isEmpty());
        }
    }

    @Test void explicitWeekendActivityPlanAutoSavesWithoutActivityKeywordWhitelist() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("weekend-activity"));
        Path settings = root.resolve("user/weekend-consent.json");
        String content = "周六周日，上午去跑步，下午睡觉";
        String quote = "我们约定，" + content;
        String sessionId = UUID.randomUUID().toString();
        new LocalCrossSessionMemory(new SharedMemoryStore(workspace), () -> true, workspace)
                .rememberAuthorized(sessionId, "以后这个项目统一使用 Java 17");
        var extractionCalls = new AtomicInteger();
        var injectedCalls = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try {
                     JsonNode payload = body(request);
                     if (extraction(payload)) {
                         extractionCalls.incrementAndGet();
                         var src = source(payload);
                         var proposal = new LinkedHashMap<>(proposal(content, src, .99));
                         proposal.put("category", "DECISION");
                         proposal.put("evidenceQuote", quote);
                         return extractionResponse(request, List.of(proposal));
                     }
                     if (payload.toString().contains("CROSS_SESSION_MEMORY_DATA")) {
                         injectedCalls.incrementAndGet();
                         assertTrue(payload.toString().contains(content));
                     }
                     return runtime.answer(request, "已按保存的周末安排回答。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings);
            assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CHAT));
            send(controller, adapter, quote);

            var entries = new SharedMemoryStore(workspace).read();
            assertEquals(2, entries.size());
            assertTrue(entries.stream().anyMatch(m -> m.content().equals("以后这个项目统一使用 Java 17")));
            assertTrue(entries.stream().anyMatch(m -> m.content().equals(content)));
            var diagnostic = new MemoryRunDiagnostics(workspace).latest();
            assertEquals(1, diagnostic.effective());
            assertEquals(0, diagnostic.pending());
            assertEquals("APPROVED", new CliConversationManager(workspace)
                    .load(controller.loop().snapshot().currentSessionId()).snapshot().extraction().candidates().stream()
                    .filter(c -> c.candidateContent().equals(content)).findFirst().orElseThrow().reviewStatus());
            assertEquals(1, extractionCalls.get());

            assertTrue(controller.createSession());
            send(controller, adapter, "周六周日，上午去跑步，下午睡觉的安排是什么？");
            assertEquals(1, injectedCalls.get());
            assertTrue(controller.loop().snapshot().current().turn().tools().isEmpty());
        }
    }

    @Test void wholeMessageOptOutSkipsRulesAndSemanticReservation() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("whole-opt-out")); Path settings = root.resolve("user/whole-consent.json");
        var requests = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> { requests.incrementAndGet(); return runtime.answer(request, "收到。"); });
             var adapter = new DailyAgentAdapter(lease, client, null); var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings); assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.READ));
            send(controller, adapter, "以后项目统一使用 Java 17。整条消息都不要记忆。");
            var diag = new MemoryRunDiagnostics(workspace).latest(); assertNotNull(diag);
            assertEquals("WHOLE_MESSAGE_MEMORY_DISABLED", diag.reason()); assertEquals("SKIPPED", diag.status());
            assertTrue(new SharedMemoryStore(workspace).read().isEmpty()); assertTrue(new SemanticMemoryLedger(workspace).status().contains("semantic reserved requests=0"));
            assertEquals(1, requests.get(), "only the normal chat request is allowed; extraction must not be sent");
        }
    }

    @Test void quotedHypotheticalToolOutputAndCredentialsNeverReachSemanticExtractor() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("unsafe")); Path settings = root.resolve("user/unsafe-consent.json");
        var extractionCalls = new AtomicInteger(); var allBodies = new ArrayList<String>();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(request -> {
                 try { JsonNode payload = body(request); String serialized = payload.toString(); allBodies.add(serialized);
                     if (extraction(payload)) extractionCalls.incrementAndGet();
                     return runtime.answer(request, "已回答。");
                 } catch (Exception failure) { throw new IOException(failure); }
             }); var adapter = new DailyAgentAdapter(lease, client, null); var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.createSession(); authorize(controller, settings); assertTrue(controller.memoryCommand("global on"));
            assertTrue(controller.dailyProfile(CliMode.CHAT));
            for (String text : List.of("假设以后项目使用 Java 21，这只是一个虚构讨论，不代表真实决定。", "工具输出：项目使用 Java 21。", "引用同事的话：项目使用 Java 21。"))
                send(controller, adapter, text);
            controller.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.CLEAR, "");
            controller.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.TEXT, "请记住 API_KEY=FAKE_SECRET_NEVER_SEND_92831");
            assertNull(controller.submit(), "credential-bearing user input is rejected before provider or persistence");
            assertEquals(0, extractionCalls.get());
            assertTrue(allBodies.stream().noneMatch(s -> s.contains("FAKE_SECRET_NEVER_SEND_92831")));
            assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
        }
    }

    @Test void duplicateConflictAndBadEvidenceDoNotChangeExistingVersion() throws Exception {
        String text = "以后这个项目统一使用 Java 17。";
        assertEquals(ConflictDetector.Relation.DUPLICATE, MemoryQualityPolicy.relation("项目使用 Java 17", List.of(
                new LongTermMemory(UUID.randomUUID().toString(), "项目使用 Java 17", UUID.randomUUID().toString(),
                        java.time.Instant.now().toString(), java.time.Instant.now().toString(), "USER_MEMORY_COMMAND", "EXPLICIT_USER", "scope", null))));
        assertFalse(MemoryQualityPolicy.evidenceAllowed("今天的临时情况", text));
        assertFalse(MemoryQualityPolicy.evidenceAllowed("fabricated evidence", text));
        assertTrue(MemoryQualityPolicy.wholeMessageProhibited("一项偏好。整条消息都不要记忆。"));
        assertTrue(MemoryQualityPolicy.evidenceAllowed("以后这个项目统一使用 Java 17", text));
    }
}
