package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import com.agent.tool.*;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Ui2InteractionTest {
    @TempDir Path workspace;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    void run(InteractiveCli cli, String input) throws Exception {
        cli.run(new BufferedReader(new StringReader(input)) { @Override public boolean ready() { return false; } });
    }
    InteractiveCli cli(AtomicInteger calls, CliMode initial) {
        var ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        LLMClient fake = messages -> { calls.incrementAndGet(); return new LLMResponse("[FAKE] offline answer", List.of()); };
        return new InteractiveCli(CliAgentFactory.createProfiles(fake, workspace, ui), ui, workspace, initial);
    }
    @Test void quietByDefaultAndExplicitStatusDoesNotRequestModel() throws Exception {
        var calls = new AtomicInteger(); var cli = cli(calls, CliMode.CHAT);
        run(cli, "/help\n你好\n/exit\n");
        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, calls.get());
        assertEquals(1, text.split("\\[FAKE\\] offline answer", -1).length - 1);
        assertFalse(text.contains("System > Model usage:"));
        assertFalse(text.contains("System > Task evidence:"));
        assertFalse(text.contains("System > Memory context (last request)"));
        assertFalse(text.contains("No managed sessions"));
        output.reset();
        run(cli, "/status\n/verbose on\n再次问好\n/verbose off\n/verbose status\n/exit\n");
        text = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, calls.get());
        assertTrue(text.contains("Model usage:"));
        assertTrue(text.contains("Task evidence: execution=COMPLETED"));
        assertTrue(text.contains("Memory context (last request)"));
        assertTrue(text.contains("Verbose: OFF"));
        output.reset();
        run(cli, "clear\n/status\n/exit\n");
        assertEquals(2, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("No task diagnostics"));
    }
    @Test void fakeDemoChatSessionsDoNotEnterMutationRecoveryAndKeepConsent() throws Exception {
        var calls = new AtomicInteger(); var cli = cli(calls, CliMode.CHAT);
        run(cli, "这个项目使用 Java 17 和 Maven\n/memory global on\n/new\n你好\n/exit\n");
        assertEquals(2, calls.get(), "one actual chat decision per task, no hidden second response");
        var stored = new CliConversationManager(workspace).list();
        assertEquals(2, stored.size());
        assertTrue(stored.stream().anyMatch(s -> s.snapshot().crossSessionMemory().enabled()));
        assertTrue(stored.stream().anyMatch(s -> !s.snapshot().crossSessionMemory().enabled()));
        assertTrue(stored.stream().allMatch(s -> s.snapshot().activeMode() == CliMode.CHAT));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("EXECUTION_INCOMPLETE"));
    }
    @Test void incompleteUnifiedExecutionIsStillVisibleInQuietMode() throws Exception {
        var calls = new AtomicInteger(); var cli = cli(calls, CliMode.CODE);
        run(cli, "创建一个 Java 文件\n/exit\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Task evidence:"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("EXECUTION_INCOMPLETE"));
    }
    @Test void unifiedGreetingCompletesInOneDecisionWithoutWeakeningModificationGuard() throws Exception {
        var calls = new AtomicInteger(); var cli = cli(calls, CliMode.CODE);
        run(cli, "你好！\n/status\n/exit\n");
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Task evidence: execution=COMPLETED"));
        var detector = new com.agent.agent.WorkspaceChangeRequirementDetector();
        assertEquals(com.agent.agent.WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("你好，请创建一个 Java 文件"));
    }
    @Test void localTimeConversionDoesNotModifyStoredInstant() {
        String instant = "2026-11-01T01:02:03Z";
        assertEquals("2026-11-01 09:02", SessionUiText.localTime(instant, ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-11-01 01:02", SessionUiText.localTime(instant, ZoneId.of("UTC")));
        assertEquals("2026-11-01T01:02:03Z", instant);
    }
    @Test void mavenResultsRemainVisibleWithoutVerboseAndNeverPrintOutputBodies() {
        var ui = new ConsoleUi(new PrintStream(output));
        ui.toolFinished("run_maven_test", ToolResult.success("do not print raw log", Map.of(
                "testStatus", "TEST_PASSED", "executed", true, "testsRun", 1, "testsFailed", 0,
                "testsErrors", 0, "testsSkipped", 0)));
        assertTrue(output.toString().contains("result=TEST_PASSED, executed=true, tests=1"));
        assertFalse(output.toString().contains("do not print raw log"));
    }
    @Test void menuNavigationUsesSingleReaderPostAreaAndCleansUpOnEscape() throws Exception {
        var ui = new ConsoleUi(new PrintStream(output));
        var profiles = CliAgentFactory.createProfiles(messages -> new LLMResponse("", List.of()), workspace, ui);
        var snapshot = new CliSessionSnapshot(1, "2026-11-01T00:00:00Z", CliMode.CODE, null,
                profiles.histories(), new CliWorkingContext().snapshot());
        var items = List.of(new CliConversation(1, UUID.randomUUID().toString(), "first", "2026-11-01T00:00:00Z",
                "2026-11-01T00:00:00Z", snapshot), new CliConversation(1, UUID.randomUUID().toString(), "second",
                "2026-11-01T00:00:00Z", "2026-11-01T00:01:00Z", snapshot));
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (var caps = new DumbTerminal("caps", "xterm", InputStream.nullInputStream(), OutputStream.nullOutputStream(), StandardCharsets.UTF_8)) {
            String left = KeyMap.key(caps, Capability.key_left);
            String down = KeyMap.key(caps, Capability.key_down);
            String keys = left + left + down + down + KeyMap.esc() + "\r中文 ab" + left + "X\r";
            try (var terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), screen, StandardCharsets.UTF_8)) {
                terminal.setSize(new Size(100, 24));
                try (var input = new TerminalSessionInput(terminal, () -> "> ", () -> items, () -> null,
                        id -> fail("Esc must not switch"))) {
                    input.topLevel(true);
                    assertEquals("", input.reader().readLine());
                    assertFalse(input.line.menuActive());
                    assertEquals("中文 aXb", input.reader().readLine());
                    assertFalse(input.line.menuActive());
                }
            }
        }
        String rendered = screen.toString(StandardCharsets.UTF_8);
        assertEquals(1, rendered.split("Sessions:", -1).length - 1,
                "identical menu rows must not be appended on each navigation key");
        assertFalse(rendered.contains("\u001b[2J"), "navigation must not clear chat scrollback");
    }
    @Test void emptyPickerDoesNotReopenForRepeatedLeftOrCreateSessions() throws Exception {
        var screen = new ByteArrayOutputStream();
        try (var caps = new DumbTerminal("caps", "xterm", InputStream.nullInputStream(), OutputStream.nullOutputStream(), StandardCharsets.UTF_8)) {
            String left = KeyMap.key(caps, Capability.key_left);
            try (var terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream((left + left + left + "\r\r").getBytes(StandardCharsets.UTF_8)), screen, StandardCharsets.UTF_8)) {
                terminal.setSize(new Size(100, 24));
                try (var input = new TerminalSessionInput(terminal, () -> "> ", List::of, () -> null, id -> fail())) {
                    input.topLevel(true);
                    assertEquals("", input.reader().readLine());
                    assertFalse(input.line.menuActive());
                }
            }
        }
        assertEquals(1, screen.toString(StandardCharsets.UTF_8).split("No managed sessions", -1).length - 1);
    }
}
