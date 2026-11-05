package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ManagedConversationTest {
    @TempDir Path workspace;

    @Test
    void twoSessionsRemainIsolatedAcrossSwitchAndRestartIncludingWorkingContext() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "/new\nExplain Alpha.java alpha-only\n/exit\n");
        CliConversation a = only();
        run(cli, "/new\nExplain Beta.java beta-only\n/exit\n");
        CliConversation b = manager().list().stream().filter(c -> !c.id().equals(a.id())).findFirst().orElseThrow();
        assertEquals(2, manager().list().size());
        run(cli, "/switch " + a.id() + "\nfollow-up alpha\n/exit\n");
        assertHas(client.last(), "alpha-only");
        assertAbsent(client.last(), "beta-only");
        assertEquals(List.of("Alpha.java"), manager().load(a.id()).snapshot().workingContext().explicitTargetFiles());

        RecordingClient restartedClient = new RecordingClient();
        CliSessions restarted = profiles(restartedClient);
        InteractiveCli freshCli = cli(restarted, new ByteArrayOutputStream());
        run(freshCli, "/switch " + b.id() + "\ncontinue beta\n/exit\n");
        assertHas(restartedClient.last(), "beta-only");
        assertAbsent(restartedClient.last(), "alpha-only");
        assertTrue(restarted.chat().history().size() == 1 && restarted.read().history().size() == 1);
        assertEquals(2, manager().list().size());
        run(freshCli, "/switch " + a.id() + "\n/exit\n");
        assertTrue(restarted.code().history().stream().anyMatch(m -> m.content().contains("follow-up alpha")));
    }

    @Test
    void newSessionHasOnlyItsSystemPromptsAndUnifiedToolsStayAvailable() throws Exception {
        // Tool availability intentionally exposes Maven only for a workspace with a root POM.
        // This file is never built or executed in this test.
        Files.writeString(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "remember private-alpha\n/new\n/exit\n");
        assertTrue(profiles.histories().values().stream().allMatch(h -> h.size() == 1));
        run(cli, "hello fresh\n/exit\n");
        assertAbsent(client.last(), "private-alpha");
        assertTrue(client.tools.containsAll(List.of("read_file", "find_files", "search_code", "create_file",
                "apply_context_patch", "run_maven_test")));
    }

    @Test
    void renameAndListAndHistoryMakeNoModelRequestsAndPersistUnicodeTitle() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveCli cli = cli(profiles, output);
        run(cli, "/new\n/exit\n");
        String id = only().id();
        run(cli, "/rename " + id + " 算法 会话\n/sessions\n/history\n/exit\n");
        assertEquals(0, client.requests.size());
        assertEquals("算法 会话", new CliConversationManager(workspace).load(id).title());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("算法 会话"));
        run(cli, "alpha-visible\n/history\n/exit\n");
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("user: alpha-visible"));
        assertFalse(text.contains("system: WORKSPACE_VERIFICATION_CAPABILITY"));
    }

    @Test
    void switchingRestoresReadPermissionWithoutUpgradingIt() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "/new\n/read\nexplain read-only\n/exit\n");
        String readId = only().id();
        run(cli, "/new\nhello unified\n/switch " + readId + "\nexplain again\n/exit\n");
        assertTrue(client.tools.contains("read_file"));
        assertFalse(client.tools.contains("create_file"));
        assertFalse(client.tools.contains("run_maven_test"));
        assertEquals(CliMode.READ, manager().load(readId).snapshot().activeMode());
    }

    @Test
    void missingCorruptOrInvalidSwitchDoesNotChangeCurrentHistory() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveCli cli = cli(profiles, output);
        run(cli, "keep-current\n/exit\n");
        List<Message> before = profiles.code().history();
        String badId = UUID.randomUUID().toString();
        Files.writeString(workspace.resolve(".agent-cli/conversations/" + badId + ".json"), "{bad");
        run(cli, "/switch ../escape\n/switch " + badId + "\n/switch " + UUID.randomUUID() + "\n/exit\n");
        assertEquals(before, profiles.code().history());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("current history retained"));
    }

    @Test
    void failedCheckpointDoesNotDiscardHistoryOnNewOrSwitch() throws Exception {
        Files.createDirectories(workspace.resolve(".agent-cli"));
        Files.writeString(workspace.resolve(".agent-cli/conversations"), "not a directory");
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveCli cli = cli(profiles, output);
        run(cli, "must-remain\n/new\n/exit\n");
        assertHas(profiles.code().history(), "must-remain");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Session not persisted"));
    }

    @Test
    void managedRestorePreservesToolPairsWithoutExecutingThem() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        List<Message> code = List.of(profiles.code().history().get(0), Message.user("old user"),
                Message.assistant("", List.of(new ToolCall("old-call", "create_file", "{}"))),
                Message.tool("old-call", "old result"), Message.assistant("old final"));
        Map<CliMode, List<Message>> histories = new EnumMap<>(profiles.histories());
        histories.put(CliMode.CODE, code);
        CliConversation saved = manager().create(new CliSessionSnapshot(1, java.time.Instant.now().toString(),
                CliMode.CODE, null, histories, new CliWorkingContext().snapshot()));
        run(cli(profiles, new ByteArrayOutputStream()), "/switch " + saved.id() + "\n/exit\n");
        assertEquals(code, profiles.code().history());
        assertEquals(0, client.requests.size());
        try (var files = Files.list(workspace)) {
            assertEquals(0, files.filter(p -> !p.getFileName().toString().equals(".agent-cli")).count());
        }
    }

    @Test
    void crossSessionMemoryIsDisabledAndNeverPersistsSharedFacts() {
        CrossSessionMemory memory = manager().crossSessionMemory();
        assertFalse(memory.enabled());
        assertEquals(List.of(), memory.retrieve("A", "anything"));
        assertThrows(IllegalStateException.class, () -> memory.saveAuthorized("A", "shared fact"));
        assertFalse(Files.exists(workspace.resolve(".agent-cli")));
    }

    @Test
    void rejectsInvalidTitlesAndCredentialLikeHistoryWithoutWritingDocument() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        CliConversationManager manager = manager();
        CliSessionSnapshot snapshot = new CliSessionSnapshot(1, java.time.Instant.now().toString(), CliMode.CODE,
                null, profiles.histories(), new CliWorkingContext().snapshot());
        CliConversation first = manager.create(snapshot);
        assertThrows(IllegalArgumentException.class, () -> manager.rename(first.id(), "bad\ntitle"));
        assertEquals("New session", manager.load(first.id()).title());
        Map<CliMode, List<Message>> secret = new EnumMap<>(profiles.histories());
        secret.put(CliMode.CODE, List.of(profiles.code().history().get(0),
                Message.user("Authorization: Bearer syntheticCredentialForOfflineTest")));
        assertThrows(IOException.class, () -> manager.checkpoint(new CliSessionSnapshot(1,
                java.time.Instant.now().toString(), CliMode.CODE, null, secret, new CliWorkingContext().snapshot())));
        assertEquals(snapshot, manager.load(first.id()).snapshot());
    }

    @Test
    void invalidProfileSnapshotCannotPartiallyReplaceActiveConversation() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "keep-active\n/exit\n");
        Map<CliMode, List<Message>> before = profiles.histories();
        Map<CliMode, List<Message>> malformed = new EnumMap<>(before);
        malformed.put(CliMode.READ, List.of(Message.system("wrong profile prompt")));
        CliConversation invalid = manager().create(new CliSessionSnapshot(1, java.time.Instant.now().toString(),
                CliMode.READ, null, malformed, new CliWorkingContext().snapshot()));
        run(cli, "/switch " + invalid.id() + "\n/exit\n");
        assertEquals(before, profiles.histories());
    }

    @Test
    void legacyLoadKeepsManagedConversationAndImportsWithoutMerging() throws Exception {
        RecordingClient client = new RecordingClient();
        CliSessions profiles = profiles(client);
        InteractiveCli cli = cli(profiles, new ByteArrayOutputStream());
        run(cli, "legacy-alpha\n/save alpha\n/new\nmanaged-beta\n/exit\n");
        String betaId = manager().list().stream()
                .filter(c -> c.snapshot().histories().get(CliMode.CODE).stream()
                        .anyMatch(m -> m.content().equals("managed-beta"))).findFirst().orElseThrow().id();
        run(cli, "/load alpha\ny\ncontinue alpha\n/exit\n");
        assertHas(client.last(), "legacy-alpha");
        assertAbsent(client.last(), "managed-beta");
        assertTrue(manager().load(betaId).snapshot().histories().get(CliMode.CODE).stream()
                .anyMatch(m -> m.content().equals("managed-beta")));
        assertEquals(3, manager().list().size());
    }

    private CliConversationManager manager() { return new CliConversationManager(workspace); }
    private CliConversation only() throws IOException { return manager().list().get(0); }
    private CliSessions profiles(LLMClient llm) {
        return CliAgentFactory.createProfiles(llm, workspace, AgentEventListener.NO_OP);
    }
    private InteractiveCli cli(CliSessions profiles, ByteArrayOutputStream output) {
        return new InteractiveCli(profiles, new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8)), workspace);
    }
    private void run(InteractiveCli cli, String input) throws IOException {
        cli.run(new BufferedReader(new StringReader(input)));
    }
    private static void assertHas(List<Message> messages, String text) {
        assertTrue(messages.stream().anyMatch(m -> m.content().contains(text)), text);
    }
    private static void assertAbsent(List<Message> messages, String text) {
        assertFalse(messages.stream().anyMatch(m -> m.content().contains(text)), text);
    }
    private static class RecordingClient implements LLMClient {
        final List<List<Message>> requests = new ArrayList<>();
        List<String> tools = List.of();
        public LLMResponse chat(List<Message> messages) {
            requests.add(List.copyOf(messages));
            return new LLMResponse("offline answer", List.of());
        }
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> definitions) {
            tools = definitions.stream().map(ToolDefinition::name).toList();
            return chat(messages);
        }
        List<Message> last() { return requests.get(requests.size() - 1); }
    }
}
