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
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Java Coding Agent"));
        assertTrue(text.contains("Mode: CHAT"));
        assertTrue(text.contains("[CHAT] You > [CHAT] You > "));
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

    private record CapturedCli(InteractiveCli cli, ByteArrayOutputStream output) {
        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
