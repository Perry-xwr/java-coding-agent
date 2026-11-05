package com.agent.memory;

import com.agent.*;
import com.agent.agent.*;
import com.agent.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionContextMemoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EARLY = "Project constraint: must use Java 17; do not modify tests. 决定保留现有接口。";
    @TempDir Path workspace;
    private final Map<String, String> oldProperties = new HashMap<>();

    @BeforeEach void configure() {
        for (String property : List.of("agent.sessionMemory.enabled", "agent.sessionMemory.budgetEstimatedTokens", "agent.sessionMemory.recentTurns")) {
            oldProperties.put(property, System.getProperty(property));
        }
        System.setProperty("agent.sessionMemory.enabled", "true");
        System.setProperty("agent.sessionMemory.budgetEstimatedTokens", "2048");
        System.setProperty("agent.sessionMemory.recentTurns", "2");
    }
    @AfterEach void restore() {
        oldProperties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    @Test void shortConversationKeepsAllRawMessagesWithoutMemoryOverhead() {
        List<Message> history = List.of(Message.system("rules"), Message.user("hello"), Message.assistant("answer"), Message.user("continue"));
        var selected = select(history, 3, UUID.randomUUID().toString(), SessionSummary.empty(), 4096, 2);
        assertEquals(history, selected.messages());
        assertFalse(selected.metrics().compacted());
        assertEquals(0, selected.metrics().estimatedSavingRatio());
    }

    @Test void earlyConstraintsSurviveUnrelatedLongDiscussionAndRecentTurnsStayWhole() {
        List<Message> history = longHistory();
        List<Message> original = List.copyOf(history);
        int current = history.size();
        Message user = Message.user("Continue the original project with its original constraints");
        history.add(user);
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 8192, 2);
        assertTrue(selected.metrics().compacted());
        assertTrue(selected.messages().get(1).content().contains(EARLY));
        assertTrue(selected.messages().get(1).content().contains(SessionSummary.digest(EARLY)));
        assertEquals(history.get(0), selected.messages().get(0));
        assertEquals(user, selected.messages().get(selected.messages().size() - 1));
        for (Message message : history.subList(current - 4, current)) assertTrue(selected.messages().contains(message));
        assertEquals(original, history.subList(0, current));
        assertTrue(selected.metrics().selectedHistoryMessages() < selected.metrics().fullHistoryMessages());
        assertTrue(selected.metrics().selectedEstimatedTokens() < selected.metrics().fullEstimatedTokens());
        assertFalse(selected.metrics().softBudgetExceeded());
        System.out.println("M2_SYNTHETIC_LONG_CHAT fullEstimate=" + selected.metrics().fullEstimatedTokens()
                + " selectedEstimate=" + selected.metrics().selectedEstimatedTokens()
                + " fullMessages=" + selected.metrics().fullHistoryMessages()
                + " selectedHistoryMessages=" + selected.metrics().selectedHistoryMessages());
    }

    @Test void compactedRelevantExchangeRecallsPriorAssistantDiscussionWithoutChangingStoredHistory() {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("runtime system safety"));
        history.add(Message.user("Compare cache invalidation strategies for the project."));
        history.add(Message.assistant("We recommended bounded Caffeine caching with five-minute expiry. "
                + "This is the earlier cache recommendation. ".repeat(170)));
        for (int i = 0; i < 16; i++) {
            history.add(Message.user("Unrelated discussion topic " + i));
            history.add(Message.assistant(("unrelated-detail-" + i + " ").repeat(i < 14 ? 250 : 20)));
        }
        int current = history.size();
        history.add(Message.user("Which cache invalidation strategy was recommended?"));
        List<Message> original = List.copyOf(history);

        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 8192, 2);

        Message recalled = selected.messages().stream().filter(m -> m.content().startsWith("SAME_SESSION_RECALL_DATA")).findFirst().orElseThrow();
        assertTrue(recalled.content().contains("bounded Caffeine caching"));
        assertTrue(recalled.content().contains("sourceUserMessageIndex"));
        assertTrue(selected.metrics().injectedKinds().contains("SAME_SESSION_RECALL"));
        assertTrue(selected.messages().get(selected.messages().size() - 1).content().contains("recommended"));
        assertEquals(original, history, "selection must not mutate persisted conversation history");
        assertFalse(selected.metrics().softBudgetExceeded());
    }

    @Test void unrelatedCompactedDiscussionIsNotInjectedAsSameSessionRecall() {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("runtime system safety"));
        history.add(Message.user("Discuss cache invalidation and Caffeine."));
        history.add(Message.assistant("The recommendation was five-minute expiry. ".repeat(170)));
        for (int i = 0; i < 16; i++) {
            history.add(Message.user("Unrelated discussion topic " + i));
            history.add(Message.assistant(("unrelated-detail-" + i + " ").repeat(i < 14 ? 250 : 20)));
        }
        int current = history.size(); history.add(Message.user("帮我解释 Rust 所有权和借用规则"));
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 8192, 2);
        assertFalse(selected.messages().stream().anyMatch(m -> m.content().startsWith("SAME_SESSION_RECALL_DATA")));
    }

    @Test void dailyAgentRequestGetsRelevantPriorAssistantAnswerAfterM2Compaction() throws Exception {
        System.setProperty("agent.sessionMemory.budgetEstimatedTokens", "8192");
        RecordingClient client = new RecordingClient(false);
        CliSessions profiles = profiles(client);
        List<Message> history = new ArrayList<>();
        history.add(profiles.code().history().get(0));
        history.add(Message.user("Compare cache invalidation strategies for the project."));
        history.add(Message.assistant("We recommended bounded Caffeine caching with five-minute expiry. "
                + "This is the earlier cache recommendation. ".repeat(170)));
        for (int i = 0; i < 16; i++) {
            history.add(Message.user("Unrelated discussion topic " + i));
            history.add(Message.assistant(("unrelated-detail-" + i + " ").repeat(i < 14 ? 250 : 20)));
        }
        profiles.code().restoreHistory(history);

        run(cli(profiles, new ByteArrayOutputStream()), "Which cache invalidation strategy was recommended?\n/exit\n");

        assertTrue(client.last().stream().anyMatch(message -> message.content().startsWith("SAME_SESSION_RECALL_DATA")
                && message.content().contains("bounded Caffeine caching")),
                "the actual Daily Agent model request should include the relevant earlier assistant answer");
        assertTrue(client.last().stream().anyMatch(message -> message.content().contains("Which cache invalidation strategy")));
    }

    @Test void historicalToolsAndFullSnapshotsAreNeverSplitOrTrimmed() {
        List<Message> history = longHistory();
        history.add(Message.user("read target"));
        Message call = Message.assistant("", List.of(new ToolCall("read-snapshot", "read_file", "{\"path\":\"App.java\"}")));
        Message result = Message.tool("read-snapshot", "whole-file-snapshot\n".repeat(700));
        history.add(call); history.add(result); history.add(Message.assistant("read"));
        history.add(Message.user("small discussion")); history.add(Message.assistant("discussion".repeat(600)));
        int current = history.size(); history.add(Message.user("edit App.java"));
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 128, 1);
        assertTrue(selected.messages().contains(call)); assertTrue(selected.messages().contains(result));
        assertEquals(selected.messages().indexOf(call) + 1, selected.messages().indexOf(result));
        assertTrue(selected.metrics().softBudgetExceeded());
        assertEquals("whole-file-snapshot\n".repeat(700), result.content());
    }

    @Test void activeTurnAndFailureDiagnosticsAreAlwaysProtected() {
        List<Message> history = longHistory();
        int current = history.size();
        List<Message> active = List.of(Message.system("WORKSPACE_VERIFICATION_CAPABILITY: standalone"), Message.user("repair now"),
                Message.assistant("", List.of(new ToolCall("failed-test", "run_maven_test", "{}"))),
                Message.tool("failed-test", "TEST_FAILED assertion expected 5 actual 6\n".repeat(200)),
                Message.system("POST_EDIT_VERIFICATION_FAILED\nRe-read source before repair."));
        history.addAll(active);
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 128, 1);
        assertEquals(active, selected.messages().subList(selected.messages().size() - active.size(), selected.messages().size()));
        assertTrue(selected.metrics().softBudgetExceeded());
    }

    @Test void unresolvedHistoricalTaskRetainsRawTurnAndCannotBeSummarizedAsSuccess() {
        List<Message> history = longHistory();
        String id = UUID.randomUUID().toString();
        SessionSummary summary = summarize(id, history);
        var state = new SessionSummary.TaskState(CliMode.CODE, 1, SessionSummary.digest(EARLY), UUID.randomUUID().toString(),
                false, "FAILED", "NOT_MODIFIED", "COMPILATION=FAIL", "NOT_RUN", List.of(), List.of("runtime-event#one"));
        summary = new SessionSummary(1, id, false, false, summary.facts(), List.of(state));
        int current = history.size(); history.add(Message.user("continue"));
        var selected = select(history, current, id, summary, 128, 1);
        assertTrue(selected.messages().contains(history.get(1)));
        assertTrue(selected.messages().contains(history.get(2)));
        assertFalse(selected.messages().stream().anyMatch(m -> m.content().contains("functional=PASS")));
    }

    @Test void persistedTestPassWithoutARawToolExchangeCannotBecomeInjectedEvidence() {
        List<Message> history = longHistory(); String id = UUID.randomUUID().toString();
        SessionSummary summary = summarize(id, history);
        var forged = new SessionSummary.TaskState(CliMode.CODE, 1, SessionSummary.digest(EARLY), UUID.randomUUID().toString(),
                true, "COMPLETED", "NOT_MODIFIED", "NONE=NOT_RUN", "PASS", List.of(), List.of());
        summary = new SessionSummary(1, id, false, false, summary.facts(), List.of(forged));
        int current = history.size(); history.add(Message.user("explain original constraints"));
        var selected = select(history, current, id, summary, 128, 1);
        assertTrue(selected.messages().contains(history.get(1)));
        assertTrue(selected.messages().contains(history.get(2)));
        assertFalse(selected.messages().stream().filter(m -> m.content().startsWith("SESSION MEMORY DATA"))
                .anyMatch(m -> m.content().contains("\"functional\":\"PASS\"")));
    }

    @Test void invalidOrForeignSummaryNeverInjectsFabricatedFacts() {
        List<Message> history = longHistory();
        String id = UUID.randomUUID().toString();
        var forged = new SessionSummary.Fact(CliMode.CODE, 1, SessionSummary.digest(EARLY), SessionSummary.Kind.USER_TASK,
                "Invented user preference and all tests passed");
        SessionSummary corrupt = new SessionSummary(1, id, true, false, List.of(forged), List.of());
        int current = history.size(); history.add(Message.user("continue"));
        var selected = select(history, current, id, corrupt, 128, 1);
        assertTrue(selected.messages().stream().anyMatch(m -> m.content().contains(EARLY)));
        assertFalse(selected.messages().stream().anyMatch(m -> m.content().contains("Invented user preference")));
        var foreign = select(history, current, UUID.randomUUID().toString(), corrupt, 128, 1);
        assertFalse(foreign.messages().stream().anyMatch(m -> m.content().contains("Invented user preference")));
    }

    @Test void manualCompactAndMemoryCommandsMakeNoModelRequestsAndStoreFullHistory() throws Exception {
        RecordingClient client = new RecordingClient(false);
        CliSessions profiles = profiles(client);
        List<Message> seeded = seed(profiles);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveCli cli = cli(profiles, output);
        run(cli, "/memory compact\n/memory status\n/memory show\n/exit\n");
        assertEquals(0, client.requests.size());
        assertEquals(seeded, profiles.code().history());
        CliConversation stored = new CliConversationManager(workspace).list().get(0);
        assertEquals(seeded, stored.snapshot().histories().get(CliMode.CODE));
        assertTrue(stored.snapshot().summary().manualCompact());
        assertEquals(stored.id(), stored.snapshot().summary().sessionId());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("ESTIMATED_UTF8_BYTES_DIV_4"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("PROVIDER_REPORTED usage=UNKNOWN"));
    }

    @Test void memorySurvivesRestartAndNeverMovesBetweenSessionsOrProfiles() throws Exception {
        RecordingClient first = new RecordingClient(false);
        CliSessions profiles = profiles(first);
        seed(profiles);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "/memory compact\n/exit\n");
        CliConversation a = new CliConversationManager(workspace).list().get(0);
        run(cli, "/new\nOnly-session-B\n/memory show\n/exit\n");
        assertFalse(first.last().stream().anyMatch(m -> m.content().contains(EARLY)));
        CliConversation b = new CliConversationManager(workspace).list().stream().filter(c -> !c.id().equals(a.id())).findFirst().orElseThrow();
        assertFalse(b.snapshot().summary().facts().stream().anyMatch(f -> f.text().contains(EARLY)));
        RecordingClient restarted = new RecordingClient(false);
        CliSessions fresh = profiles(restarted);
        run(cli(fresh, new ByteArrayOutputStream()), "/switch " + a.id() + "\ncontinue original\n/read\nexplain read mode\n/exit\n");
        assertTrue(restarted.requests.get(0).stream().anyMatch(m -> m.content().contains(EARLY)));
        assertFalse(restarted.requests.get(0).stream().anyMatch(m -> m.content().contains("Only-session-B")));
        assertFalse(restarted.last().stream().anyMatch(m -> m.content().contains(EARLY)));
        assertFalse(new CliConversationManager(workspace).crossSessionMemory().enabled());
        assertEquals(a.id(), new CliConversationManager(workspace).load(a.id()).snapshot().summary().sessionId());
    }

    @Test void oldAndCorruptSummaryDocumentsRestoreOriginalHistory() throws Exception {
        CliSessions profiles = profiles(new RecordingClient(false));
        List<Message> seeded = seed(profiles);
        CliSessionSnapshot snapshot = new CliSessionSnapshot(1, java.time.Instant.now().toString(), CliMode.CODE, null,
                profiles.histories(), new CliWorkingContext().snapshot());
        CliSessionStore store = new CliSessionStore(workspace);
        store.save("old", snapshot);
        Path file = workspace.resolve(".agent-cli/sessions/old.json");
        ObjectNode json = (ObjectNode) JSON.readTree(Files.readString(file)); json.remove("summary");
        Files.writeString(file, JSON.writeValueAsString(json));
        assertEquals(seeded, store.load("old").histories().get(CliMode.CODE));
        json.put("summary", "invalid summary with wrong shape");
        Files.writeString(file, JSON.writeValueAsString(json));
        CliSessionSnapshot loaded = store.load("old");
        assertEquals(seeded, loaded.histories().get(CliMode.CODE));
        assertEquals(SessionSummary.empty(), loaded.summary());
        var selection = select(new ArrayList<>(seeded), seeded.size(), UUID.randomUUID().toString(), loaded.summary(), 128, 1);
        assertTrue(selection.messages().stream().anyMatch(m -> m.content().contains(EARLY)));
    }

    @Test void providerUsageStaysIndependentOfEstimatedContextAndFakeUsageIsUnknownWhenAbsent() throws Exception {
        String task = "Explain the original constraints without changing any file.";
        RecordingClient baseline = new RecordingClient(true);
        CliSessions baselineProfiles = profiles(baseline); seed(baselineProfiles);
        var fullResult = baselineProfiles.code().runWithTrajectory(task);
        RecordingClient client = new RecordingClient(true);
        CliSessions profiles = profiles(client); seed(profiles);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        run(cli(profiles, output), task + "\n/memory status\n/exit\n");
        assertEquals(1, baseline.requests.size());
        assertEquals(baseline.requests.size(), client.requests.size());
        assertEquals(9, fullResult.usageSummary().totalTokens());
        assertTrue(JSON.writeValueAsBytes(client.last()).length < JSON.writeValueAsBytes(baseline.last()).length);
        assertTrue(profiles.code().sessionContextMetrics().get(0).compacted());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("ESTIMATED_UTF8_BYTES_DIV_4"));
        assertTrue(text.contains("input=7, output=2, total=9"));
        assertTrue(text.contains("PROVIDER_REPORTED request="));
        var stored = new CliConversationManager(workspace).list().get(0).snapshot();
        assertEquals("NOT_RUN", stored.summary().tasks().get(stored.summary().tasks().size() - 1).functional());
    }

    @Test void hugeUserMessagesAndAmbiguousFollowUpDecisionsAreNotCutOrGuessed() {
        List<Message> history = longHistory();
        Message huge = Message.user("must keep the full specification ".repeat(200));
        history.add(huge); history.add(Message.assistant("response".repeat(1000)));
        Message proposal = Message.assistant("Proposed option Z, needs user decision.");
        history.add(Message.user("Choose a library")); history.add(proposal);
        history.add(Message.user("Yes, use that")); history.add(Message.assistant("acknowledged"));
        history.add(Message.user("other discussion")); history.add(Message.assistant("other".repeat(1000)));
        int current = history.size(); history.add(Message.user("explain constraints"));
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 128, 1);
        assertTrue(selected.messages().contains(huge));
        assertTrue(selected.messages().contains(proposal));
        assertTrue(selected.messages().contains(Message.user("Yes, use that")));
    }

    @Test void summaryCapacityOverflowKeepsFullContextInsteadOfDroppingSources() {
        List<Message> history = new ArrayList<>(); history.add(Message.system("rules"));
        for (int i = 0; i < 260; i++) { history.add(Message.user("question " + i)); history.add(Message.assistant("answer".repeat(40))); }
        int current = history.size(); history.add(Message.user("latest"));
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 128, 1);
        assertEquals(history, selected.messages());
        assertEquals("SUMMARY_CAPACITY_EXCEEDED_KEEP_FULL", selected.metrics().reason());
    }

    @Test void explicitHistoryRecallUsesRawHistoryRatherThanInventingAnAnswerFromSummary() {
        List<Message> history = longHistory(); int current = history.size();
        history.add(Message.user("Repeat your first answer exactly"));
        var selected = select(history, current, UUID.randomUUID().toString(), SessionSummary.empty(), 128, 1);
        assertEquals(history, selected.messages());
        assertEquals("EXPLICIT_HISTORY_REFERENCE_KEEP_FULL", selected.metrics().reason());
    }

    @Test void manualCompactionWatermarkDoesNotForceCompactionOfFutureTurns() {
        List<Message> history = longHistory(); String id = UUID.randomUUID().toString();
        SessionSummary manual = summarize(id, history).compactManually(Map.of(CliMode.CODE, history));
        Message futureAnswer = Message.assistant("future-answer-kept-whole ".repeat(800));
        history.add(Message.user("future discussion")); history.add(futureAnswer);
        history.add(Message.user("most recent discussion")); history.add(Message.assistant("recent"));
        int current = history.size(); history.add(Message.user("current"));
        var selected = select(history, current, id, manual, 1_000_000, 1);
        assertTrue(selected.metrics().compacted());
        assertTrue(selected.messages().contains(futureAnswer));
        assertFalse(selected.messages().contains(history.get(2)));
    }

    @Test void taskStateUsesRealToolEvidenceAndIgnoresAssistantSuccessClaims() throws Exception {
        List<LLMResponse> responses = List.of(tool("missing", "read_file", Map.of("path", "missing.txt")),
                new LLMResponse("All tests passed and everything ran successfully.", List.of()));
        RecordingClient client = new RecordingClient(false) {
            int index;
            public LLMResponse chat(List<Message> messages) { requests.add(List.copyOf(messages)); return responses.get(index++); }
        };
        CliSessions profiles = profiles(client);
        run(cli(profiles, new ByteArrayOutputStream()), "Read missing.txt and explain; do not modify files.\n/exit\n");
        var summary = new CliConversationManager(workspace).list().get(0).snapshot().summary();
        assertEquals("NOT_RUN", summary.tasks().get(0).functional());
        assertTrue(summary.tasks().get(0).evidenceSources().stream().anyMatch(source -> source.contains("read_file#missing=FAIL")));
        assertFalse(summary.facts().stream().anyMatch(fact -> fact.text().contains("All tests passed")));
    }

    @Test void disabledSelectorKeepsFullHistoryAndBudgetConfigurationRejectsInvalidValues() throws Exception {
        System.setProperty("agent.sessionMemory.enabled", "false");
        RecordingClient client = new RecordingClient(false); CliSessions profiles = profiles(client);
        List<Message> initial = seed(profiles);
        run(cli(profiles, new ByteArrayOutputStream()), "continue\n/exit\n");
        assertTrue(client.last().containsAll(initial));
        assertFalse(client.last().stream().anyMatch(m -> m.content().startsWith("SESSION MEMORY DATA")));
        assertThrows(IllegalArgumentException.class, () -> new SessionContextConfig(true, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SessionContextConfig(true, 128, 0));
    }

    @Test void activeReadPatchAndStaleVersionChecksRemainRealUnderCompaction() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "alpha\n");
        List<LLMResponse> answers = List.of(
                tool("m2-read", "read_file", Map.of("path", "note.txt")),
                tool("m2-patch", "apply_context_patch", Map.of("path", "note.txt", "expectedContent", "alpha\n",
                        "edits", List.of(Map.of("search", "alpha", "replace", "beta")))),
                new LLMResponse("edited", List.of()));
        RecordingClient client = new RecordingClient(false) {
            int index;
            public LLMResponse chat(List<Message> messages) {
                requests.add(List.copyOf(messages)); return answers.get(index++);
            }
        };
        CliSessions profiles = profiles(client); seed(profiles);
        run(cli(profiles, new ByteArrayOutputStream()), "修改 note.txt，将 alpha 改为 beta\n/exit\n");
        assertEquals("beta\n", Files.readString(workspace.resolve("note.txt")));
        assertEquals(3, client.requests.size());
        assertTrue(client.requests.get(1).stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("alpha")));
        assertTrue(profiles.code().sessionContextMetrics().stream().allMatch(SessionContextMetrics::compacted));
        var stale = com.agent.tool.ToolRegistry.withCliCodingTools(workspace, new com.agent.tool.execution.DefaultProcessRunner(), workspace.resolve(".m2/repository"))
                .execute("apply_context_patch", JSON.writeValueAsString(Map.of("path", "note.txt", "expectedContent", "alpha\n",
                        "edits", List.of(Map.of("search", "alpha", "replace", "gamma")))));
        assertEquals(com.agent.tool.ToolErrorCode.STALE_EDIT_CONTEXT, stale.errorCode());
        assertEquals("beta\n", Files.readString(workspace.resolve("note.txt")));
    }

    private RequestContextSelector.SessionSelection select(List<Message> source, int current, String id, SessionSummary summary, int budget, int recent) {
        return RequestContextSelector.selectSession(source, current, source.size(), List.of(), new SessionRequestContext(id, CliMode.CODE, summary,
                new SessionContextConfig(true, budget, recent)));
    }
    private SessionSummary summarize(String id, List<Message> history) {
        return new ExtractiveSessionSummarizer().summarize(id, Map.of(CliMode.CODE, history), SessionSummary.empty());
    }
    private List<Message> longHistory() {
        List<Message> history = new ArrayList<>(); history.add(Message.system("runtime system safety"));
        history.add(Message.user(EARLY)); history.add(Message.assistant("old-discussion-detail ".repeat(900)));
        for (int i = 0; i < 16; i++) { history.add(Message.user("Unrelated discussion " + i)); history.add(Message.assistant(("chatter-" + i + " ").repeat(900))); }
        return history;
    }
    private List<Message> seed(CliSessions profiles) {
        List<Message> history = longHistory(); history.set(0, profiles.code().history().get(0));
        profiles.code().restoreHistory(history); return List.copyOf(history);
    }
    private CliSessions profiles(LLMClient client) {
        return new CliSessions(CliAgentFactory.createChat(client, workspace, AgentEventListener.NO_OP),
                CliAgentFactory.createReadOnly(client, workspace, AgentEventListener.NO_OP),
                CliAgentFactory.createCoding(client, workspace, AgentEventListener.NO_OP));
    }
    private InteractiveCli cli(CliSessions profiles, ByteArrayOutputStream output) {
        return new InteractiveCli(profiles, new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8)), workspace);
    }
    private void run(InteractiveCli cli, String commands) throws Exception { cli.run(new BufferedReader(new StringReader(commands))); }
    private static LLMResponse tool(String id, String name, Map<String, ?> arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, name, JSON.writeValueAsString(arguments))));
    }
    private static class RecordingClient implements LLMClient {
        final List<List<Message>> requests = new ArrayList<>(); final boolean reportedUsage;
        RecordingClient(boolean reportedUsage) { this.reportedUsage = reportedUsage; }
        public LLMResponse chat(List<Message> messages) {
            requests.add(List.copyOf(messages)); return new LLMResponse("offline answer", List.of());
        }
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            if (reportedUsage) {
                var handle = ModelRequestTelemetry.beginRequest("OfflineMock", "mock-model", "https://mock.invalid/chat", messages, tools,
                        JSON.writeValueAsBytes(Map.of("messages", messages, "tools", tools)).length);
                ModelRequestTelemetry.complete(handle, 200, new ProviderTokenUsage(7, 2, 9, 3, 4, true));
            }
            return chat(messages);
        }
        List<Message> last() { return requests.get(requests.size() - 1); }
    }
}
