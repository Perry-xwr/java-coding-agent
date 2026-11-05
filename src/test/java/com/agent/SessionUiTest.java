package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionUiTest {
    @TempDir Path workspace;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    CliConversationManager manager() { return new CliConversationManager(workspace); }
    InteractiveCli cli(LLMClient client) {
        return new InteractiveCli(CliAgentFactory.createProfiles(client, workspace, AgentEventListener.NO_OP),
                new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8)), workspace);
    }
    void run(InteractiveCli cli, String text) throws Exception {
        cli.run(new BufferedReader(new StringReader(text)) {
            @Override public boolean ready() { return false; }
        });
    }
    LLMClient fake() { return messages -> new LLMResponse("offline reply", List.of()); }

    @Test void queriesDoNotCreateButFirstTaskCreatesBeforeModelAndReusesIt() throws Exception {
        var cli = cli(messages -> {
            try {
                var stored = manager().list();
                assertEquals(1, stored.size());
                assertEquals("分析 Java 项目架构", stored.get(0).title());
                assertFalse(stored.get(0).snapshot().crossSessionMemory().enabled());
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return new LLMResponse("offline", List.of());
        });
        run(cli, "/help\n/sessions\n/memory status\n/exit\n");
        assertTrue(manager().list().isEmpty());
        run(cli, "帮我分析一下这个 Java 项目的整体架构\n/exit\n");
        String id = manager().list().get(0).id();
        run(cli, "继续解释\n/exit\n");
        assertEquals(id, manager().list().get(0).id());
        assertEquals(1, manager().list().size());
    }

    @Test void manualRenameEvenToDefaultIsNeverOverwrittenAndOldTitlesSurviveRestart() throws Exception {
        var cli = cli(fake());
        run(cli, "/new\n/exit\n");
        String id = manager().list().get(0).id();
        run(cli, "/rename " + id + " New session\n解释代码\n/exit\n");
        assertEquals("New session", manager().load(id).title());
        var restarted = cli(fake());
        run(restarted, "/switch " + id + "\n另一个问题\n/exit\n");
        assertEquals("New session", manager().load(id).title());
        assertTrue(manager().load(id).snapshot().histories().get(CliMode.CODE).stream()
                .anyMatch(m -> m.content().equals("解释代码")));
    }

    @Test void switchesRestoreMemoryPermissionAndPreviewOnlySelectedConversation() throws Exception {
        var cli = cli(fake());
        run(cli, "/new\n/memory global on\n/read\nA-only\n/exit\n");
        String a = manager().list().get(0).id();
        run(cli, "/new\nB-only\n/exit\n");
        var b = manager().list().stream().filter(c -> !c.id().equals(a)).findFirst().orElseThrow();
        assertFalse(b.snapshot().crossSessionMemory().enabled());
        output.reset();
        run(cli, "/switch " + a + "\n/exit\n");
        var text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Preview user: A-only"));
        // Startup now intentionally shows the current B title. Only selected A may be previewed.
        assertFalse(text.contains("Preview user: B-only"));
        assertFalse(text.contains("Preview assistant: B-only"));
        assertTrue(text.contains("permissions=READ; cross-session memory ON"));
        output.reset();
        run(cli, "/switch " + b.id() + "\n/exit\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("cross-session memory OFF"));
    }

    @Test void titleAndPreviewSanitizeControlsCredentialsAndBoundUnicode() {
        assertEquals("New session", SessionUiText.title("请保存 API_KEY=onlySyntheticSecret"));
        assertEquals("New session", SessionUiText.title("sk-fakeSyntheticCredential"));
        assertFalse(SessionUiText.safe("a\u001b[31m\nb", 20).contains("\u001b"));
        assertEquals("[potentially sensitive text hidden]", SessionUiText.safe("password=fake", 120));
        String title = SessionUiText.title("😀".repeat(100));
        assertTrue(title.codePointCount(0, title.length()) <= 24);
    }

    List<CliConversation> items() throws Exception {
        var profiles = CliAgentFactory.createProfiles(fake(), workspace, AgentEventListener.NO_OP);
        var snapshot = new CliSessionSnapshot(1, "2026-11-01T00:00:00Z", CliMode.CODE, null,
                profiles.histories(), new CliWorkingContext().snapshot());
        var result = new ArrayList<CliConversation>();
        for (int i = 0; i < 12; i++) result.add(new CliConversation(1, UUID.randomUUID().toString(), "session " + i,
                "2026-11-01T00:00:00Z", String.format("2026-11-01T00:%02d:00Z", i), snapshot));
        return result;
    }

    @Test void selectionSortsMovesAndPagesWithoutMutatingBackend() throws Exception {
        var items = items();
        var picker = new SessionPicker(items, null);
        assertEquals(items.get(11).id(), picker.selected());
        picker.up(); assertEquals(items.get(11).id(), picker.selected());
        for (int i = 0; i < 9; i++) picker.down();
        assertEquals(items.get(2).id(), picker.selected());
        assertTrue(picker.display(null).contains("10/12"));
        assertFalse(picker.display(null).contains("session 11 |"));
        assertNull(new SessionPicker(List.of(), null).selected());
    }

    DumbTerminal terminal(String input) throws IOException {
        return new DumbTerminal("test", "xterm", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
    }

    @Test void realJlineBindingsAssemblePickerEventsAndEnterSelectsWithoutSubmittingTask() throws Exception {
        var items = items();
        var selected = new AtomicReference<String>();
        String keys;
        try (var term = terminal("")) { keys = KeyMap.key(term, Capability.key_left)
                + KeyMap.key(term, Capability.key_down) + "\rhello\r"; }
        try (var term = terminal(keys); var input = new TerminalSessionInput(term, () -> "> ",
                () -> items, () -> null, selected::set)) {
            input.topLevel(true);
            assertEquals("hello", input.reader().readLine());
            assertEquals(items.get(10).id(), selected.get());
        }
    }

    @Test void escapeCancelsAndNonemptyLeftOnlyMovesCursorWhileHistoryBindingsRemainUnchanged() throws Exception {
        var items = items();
        var selected = new AtomicReference<String>();
        String keys;
        try (var term = terminal("")) { keys = KeyMap.key(term, Capability.key_left)
                + KeyMap.esc() + "\r" + "中文 abc" + KeyMap.key(term, Capability.key_left) + "X\r"
                + KeyMap.key(term, Capability.key_up) + "\r"; }
        try (var term = terminal(keys); var input = new TerminalSessionInput(term, () -> "> ",
                () -> items, () -> null, selected::set)) {
            input.topLevel(true);
            var up = input.line.getKeyMaps().get(LineReader.MAIN).getBound(KeyMap.key(term, Capability.key_up));
            var down = input.line.getKeyMaps().get(LineReader.MAIN).getBound(KeyMap.key(term, Capability.key_down));
            assertEquals("", input.reader().readLine());
            assertNull(selected.get());
            assertEquals(up, input.line.getKeys().getBound(KeyMap.key(term, Capability.key_up)));
            assertEquals(down, input.line.getKeys().getBound(KeyMap.key(term, Capability.key_down)));
            assertEquals("中文 abXc", input.reader().readLine());
            assertEquals("中文 abXc", input.reader().readLine());
        }
    }
}
