package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveCliTest {
    @TempDir
    Path workspace;

    @Test
    void emptyInputDoesNotCallLlmAndExitPrintsGoodbye() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                calls.incrementAndGet();
                return new LLMResponse("unexpected", List.of());
            }
        };
        CliSessions sessions = CliAgentFactory.createProfiles(llm, workspace, AgentEventListener.NO_OP);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        InteractiveCli cli = new InteractiveCli(sessions, ui, workspace);

        cli.run(new BufferedReader(new StringReader("   \nexit\n")));

        assertEquals(0, calls.get());
        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("Java Coding Agent"));
        assertTrue(text.contains("Mode: CHAT"));
        assertFalse(text.contains("[CHAT] You > [CHAT] You > "));
        assertTrue(text.contains("[CHAT] You > \n[CHAT] You > "));
        assertTrue(text.contains("System > Goodbye."));
    }

    @Test
    void modeAndHelpCommandsDoNotCallLlm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = countingClient(calls);
        CapturedCli captured = capturedCli(llm);

        captured.cli().run(new BufferedReader(new StringReader(
                "/chat\n/read\n/code\n/auto\n/help\n/exit\n"
        )));

        assertEquals(0, calls.get());
        String text = captured.text();
        assertTrue(text.contains("System > Switched to CHAT mode."));
        assertTrue(text.contains("System > Switched to READ mode."));
        assertTrue(text.contains("System > Switched to CODE mode."));
        assertTrue(text.contains("AUTO routing is reserved for a later release"));
        assertTrue(text.contains("/chat   General conversation"));
    }

    @Test
    void promptsFollowChatReadCodeAndBackToChat() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturedCli captured = capturedCli(countingClient(calls));

        captured.cli().run(new BufferedReader(new StringReader(
                "/read\n/code\n/chat\nhello\n/exit\n"
        )));

        assertEquals(1, calls.get());
        String text = captured.text();
        assertTrue(text.contains("[CHAT] You >"));
        assertTrue(text.contains("[READ] You >"));
        assertTrue(text.contains("[CODE] You >"));
        assertTrue(text.contains("Agent >"));
        assertTrue(text.contains("answer"));
    }

    @Test
    void normalSingleLineSubmitsExactlyOneUserTurn() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);

        captured.cli().run(new BufferedReader(new StringReader("hello\n/exit\n")));

        assertEquals(1, llm.calls.get());
        assertEquals(List.of("hello"), llm.userTasks());
    }

    @Test
    void consecutivePasteLinesBecomeOneTurnWithBlankLinesAndIndentation() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);
        String newline = System.lineSeparator();

        captured.cli().run(new BufferedReader(new StringReader(
                "请创建 test_create.py，内容如下：\n\ndef add(a, b):\n    return a + b\n"
        )));

        assertEquals(1, llm.calls.get());
        assertEquals(List.of("请创建 test_create.py，内容如下：" + newline
                + newline + "def add(a, b):" + newline + "    return a + b"), llm.userTasks());
    }

    @Test
    void delayedFinalPasteLineResetsTheSilentWindowAndStaysInOneTurn() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);
        String newline = System.lineSeparator();
        BufferedReader reader = new DelayedBufferedReader(
                List.of(
                        "请创建 x.py，内容如下：",
                        "",
                        "def divide(a, b):",
                        "    return a / b"
                ),
                List.of(0L, 0L, 0L, 150L)
        );

        captured.cli().run(reader);

        assertEquals(1, llm.calls.get());
        assertEquals(List.of("请创建 x.py，内容如下：" + newline
                + newline + "def divide(a, b):" + newline + "    return a / b"), llm.userTasks());
    }

    @Test
    void unclosedMarkdownFenceContinuesUntilMatchingClosingFence() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);
        String newline = System.lineSeparator();

        captured.cli().run(new BufferedReader(new StringReader(
                "请创建 fenced_test.py：\n```python\ndef multiply(a, b):\n    return a * b\n```\n"
        )));

        assertEquals(1, llm.calls.get());
        assertEquals(List.of("请创建 fenced_test.py：" + newline
                + "```python" + newline
                + "def multiply(a, b):" + newline
                + "    return a * b" + newline
                + "```"), llm.userTasks());
    }

    @Test
    void explicitMultilineKeepsCommandsAsTextAndSubmitsOnce() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);
        String newline = System.lineSeparator();

        captured.cli().run(new BufferedReader(new StringReader(
                "/begin\nfirst line\n/read\n    indented\n/end\n/exit\n"
        )));

        assertEquals(1, llm.calls.get());
        assertEquals(List.of("first line" + newline + "/read" + newline + "    indented"), llm.userTasks());
        assertFalse(captured.text().contains("Switched to READ mode."));
    }

    @Test
    void standaloneEmptyEnterDoesNotCallLlmOrJoinPrompts() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);

        captured.cli().run(new BufferedReader(new StringReader("\n/exit\n")));

        assertEquals(0, llm.calls.get());
        assertFalse(captured.text().contains("[CHAT] You > [CHAT] You > "));
    }

    private CapturedCli capturedCli(LLMClient llm) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        CliSessions sessions = CliAgentFactory.createProfiles(llm, workspace, ui);
        return new CapturedCli(new InteractiveCli(sessions, ui, workspace), output);
    }

    private static LLMClient countingClient(AtomicInteger calls) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) {
                calls.incrementAndGet();
                return new LLMResponse("answer", List.of());
            }
        };
    }

    private static final class CapturingClient implements LLMClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<List<String>> latestUserTasks = new AtomicReference<>(List.of());

        @Override
        public LLMResponse chat(List<Message> messages) {
            calls.incrementAndGet();
            latestUserTasks.set(messages.stream()
                    .filter(message -> message.role().equals("user"))
                    .map(Message::content)
                    .toList());
            return new LLMResponse("answer", List.of());
        }

        private List<String> userTasks() {
            return latestUserTasks.get();
        }
    }

    private record CapturedCli(InteractiveCli cli, ByteArrayOutputStream output) {
        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class DelayedBufferedReader extends BufferedReader {
        private final List<String> lines;
        private final List<Long> delaysMillis;
        private final long startedAtNanos = System.nanoTime();
        private int index;

        private DelayedBufferedReader(List<String> lines, List<Long> delaysMillis) {
            super(new StringReader(""));
            this.lines = List.copyOf(lines);
            this.delaysMillis = List.copyOf(delaysMillis);
            if (this.lines.size() != this.delaysMillis.size()) {
                throw new IllegalArgumentException("lines and delays must have the same size");
            }
        }

        @Override
        public String readLine() throws IOException {
            if (index >= lines.size()) {
                return null;
            }
            if (index > 0 && !ready()) {
                throw new IOException("readLine called before the scheduled line was available");
            }
            return lines.get(index++);
        }

        @Override
        public boolean ready() {
            return index < lines.size()
                    && System.nanoTime() - startedAtNanos >= delaysMillis.get(index) * 1_000_000L;
        }
    }
}
