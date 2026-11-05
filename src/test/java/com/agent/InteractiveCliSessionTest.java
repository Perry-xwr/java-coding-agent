package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveCliSessionTest {
    @TempDir
    Path workspace;

    @Test
    void savedHistoryAndModeAreRestoredIntoFreshCliInstance() throws Exception {
        CliSessions firstSessions = sessions(new AtomicInteger());
        InteractiveCli firstCli = cli(firstSessions, new ByteArrayOutputStream());
        firstCli.run(new BufferedReader(new StringReader(
                "/read\n读取 README.md\n/save classroom\n/exit\n")));

        CliSessions restartedSessions = sessions(new AtomicInteger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli(restartedSessions, output).run(new BufferedReader(new StringReader("/load classroom\n/exit\n")));

        assertTrue(restartedSessions.read().history().stream()
                .anyMatch(message -> "user".equals(message.role()) && message.content().contains("README.md")));
        assertEquals(1, restartedSessions.read().history().stream()
                .filter(message -> "assistant".equals(message.role())).count());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Session loaded: classroom"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("[READ] You >"));
    }

    @Test
    void restartRestoresAllProfilesAutoContinuationAndWorkingContext() throws Exception {
        CliSessions source = sessions(new AtomicInteger());
        cli(source, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/chat\nExplain interfaces\n/read\n读取 README.md\n/code\n修改 Calculator.java\n"
                        + "/auto\nJava 和 C++ 有什么区别\n/save all-profiles\n/exit\n")));

        CliSessions restored = sessions(new AtomicInteger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli(restored, output).run(new BufferedReader(new StringReader(
                "/load all-profiles\n继续\n/exit\n")));

        assertTrue(hasUserMessage(restored.chat(), "Explain interfaces"));
        assertTrue(hasUserMessage(restored.read(), "读取 README.md"));
        assertTrue(hasUserMessage(restored.code(), "修改 Calculator.java"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("AUTO -> CHAT"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("[AUTO] You >"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("retains legacy AUTO routing"));
    }

    @Test
    void restoredWorkspaceContextStillResolvesContextualFollowUp() throws Exception {
        CliSessions source = sessions(new AtomicInteger());
        CliWorkingContext.Snapshot context = new CliWorkingContext.Snapshot(
                WorkingMemoryMode.STRUCTURED_MEMORY, "Find README.md", List.of(), List.of("README.md"),
                "README.md", List.of("README.md"),
                List.of(new CliWorkingContext.VerifiedFileFact(
                        CliWorkingContext.VerifiedFileFactType.FILE_EXISTS, "README.md")),
                List.of(), null);
        new CliSessionStore(workspace).save("context", new CliSessionSnapshot(
                CliSessionSnapshot.CURRENT_VERSION, "2026-10-16T00:00:00Z", CliMode.AUTO, CliMode.CHAT,
                source.histories(), context));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CliSessions restarted = sessions(new AtomicInteger());

        cli(restarted, output).run(new BufferedReader(new StringReader(
                "/load context\n读取这个文件\n/exit\n")));

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("AUTO -> READ"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("retains legacy AUTO routing"));
    }

    @Test
    void affirmativeLoadReplacesCurrentHistoryInsteadOfMerging() throws Exception {
        CliSessions original = sessions(new AtomicInteger());
        cli(original, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/chat\nsaved conversation\n/save saved\n/exit\n")));

        CliSessions current = sessions(new AtomicInteger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli(current, output).run(new BufferedReader(new StringReader(
                "/chat\nunsaved conversation\n/load saved\ny\n/exit\n")));

        List<String> userMessages = current.chat().history().stream()
                .filter(message -> "user".equals(message.role())).map(Message::content).toList();
        assertTrue(userMessages.contains("saved conversation"));
        assertFalse(userMessages.contains("unsaved conversation"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Session loaded: saved"));
    }

    @Test
    void unsavedChangesDefaultToCancelAndKeepCurrentHistory() throws Exception {
        CliSessions original = sessions(new AtomicInteger());
        cli(original, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/chat\nsaved conversation\n/save saved\n/exit\n")));

        CliSessions current = sessions(new AtomicInteger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli(current, output).run(new BufferedReader(new StringReader(
                "/chat\nkeep me\n/load saved\n\n/exit\n")));

        assertTrue(current.chat().history().stream()
                .anyMatch(message -> "user".equals(message.role()) && message.content().equals("keep me")));
        assertFalse(current.chat().history().stream()
                .anyMatch(message -> "user".equals(message.role()) && message.content().equals("saved conversation")));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Load cancelled"));
    }

    @Test
    void missingOrCorruptLoadLeavesCurrentHistoryUnchanged() throws Exception {
        CliSessions current = sessions(new AtomicInteger());
        cli(current, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/chat\nkeep me\n/exit\n")));
        List<Message> before = current.chat().history();
        FilesSupport.writeCorruptSession(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        cli(current, output).run(new BufferedReader(new StringReader(
                "/load absent\ny\n/load broken\ny\n/exit\n")));

        assertEquals(before, current.chat().history());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Session load failed"));
        assertFalse(text.contains("Session loaded"));
    }

    @Test
    void invalidProfileHistoryDoesNotPartiallyReplaceOtherProfiles() throws Exception {
        CliSessions saved = sessions(new AtomicInteger());
        java.util.Map<CliMode, List<Message>> invalidHistories = new java.util.EnumMap<>(CliMode.class);
        invalidHistories.putAll(saved.histories());
        invalidHistories.put(CliMode.CODE, List.of(Message.system("not the CODE profile prompt")));
        new CliSessionStore(workspace).save("invalid-profile", new CliSessionSnapshot(
                CliSessionSnapshot.CURRENT_VERSION, "2026-10-16T00:00:00Z", CliMode.AUTO, null,
                invalidHistories, new CliWorkingContext().snapshot()));

        CliSessions current = sessions(new AtomicInteger());
        cli(current, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/read\nkeep read\n/code\nkeep code\n/exit\n")));
        List<Message> chatBefore = current.chat().history();
        List<Message> readBefore = current.read().history();
        List<Message> codeBefore = current.code().history();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        cli(current, output).run(new BufferedReader(new StringReader(
                "/load invalid-profile\ny\n/exit\n")));

        assertEquals(chatBefore, current.chat().history());
        assertEquals(readBefore, current.read().history());
        assertEquals(codeBefore, current.code().history());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Session load failed"));
    }

    @Test
    void loadingHistoricalToolMessagesDoesNotExecuteTools() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CliSessions source = sessions(calls);
        source.chat().restoreHistory(List.of(
                source.chat().history().get(0),
                Message.user("old request"),
                Message.assistant("call", List.of(new com.agent.llm.ToolCall("read-1", "read_file",
                        "{\"path\":\"README.md\"}"))),
                Message.tool("read-1", "old result"),
                Message.assistant("old final")
        ));
        CliSessionSnapshot snapshot = new CliSessionSnapshot(CliSessionSnapshot.CURRENT_VERSION,
                "2026-10-16T00:00:00Z", CliMode.CHAT, null, source.histories(),
                new CliWorkingContext().snapshot());
        new CliSessionStore(workspace).save("with-tool", snapshot);
        CliSessions fresh = sessions(calls);

        cli(fresh, new ByteArrayOutputStream()).run(new BufferedReader(new StringReader(
                "/load with-tool\n/exit\n")));

        assertEquals(0, calls.get());
        assertEquals("read-1", fresh.chat().history().get(3).toolCallId());
    }

    private InteractiveCli cli(CliSessions sessions, ByteArrayOutputStream output) {
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        return new InteractiveCli(sessions, ui, workspace);
    }

    private CliSessions sessions(AtomicInteger calls) {
        LLMClient llm = messages -> {
            calls.incrementAndGet();
            return new LLMResponse("answer", List.of());
        };
        return CliAgentFactory.createProfiles(llm, workspace, AgentEventListener.NO_OP);
    }

    private static boolean hasUserMessage(com.agent.agent.Agent agent, String content) {
        return agent.history().stream().anyMatch(message -> "user".equals(message.role())
                && content.equals(message.content()));
    }

    private static final class FilesSupport {
        private static void writeCorruptSession(Path workspace) throws Exception {
            Path dir = workspace.resolve(".agent-cli/sessions");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("broken.json"), "{broken");
        }
    }
}
