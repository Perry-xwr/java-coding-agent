package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.TaskMode;
import com.agent.agent.PlanningMode;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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
        assertTrue(text.contains("Mode: UNIFIED"));
        assertFalse(text.contains("[UNIFIED] You > [UNIFIED] You > "));
        assertTrue(text.contains("[UNIFIED] You > \n[UNIFIED] You > "));
        assertTrue(text.contains("System > Goodbye."));
    }

    @Test
    void diffCommandIsAvailableWithoutStartingAnAgentTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturedCli captured = capturedCli(countingClient(calls));
        captured.cli().run(new BufferedReader(new StringReader("/diff\n/exit\n")));
        assertEquals(0, calls.get());
        assertTrue(captured.text().contains("No captured file changes in the latest task."));
    }

    @Test
    void providerFailureShowsActualSanitizedLlmErrorInsteadOfGenericMessage() throws Exception {
        LLMClient llm = new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) throws IOException {
                throw new IOException("GLM HTTP provider error 429");
            }
        };
        CliSessions sessions = CliAgentFactory.createProfiles(llm, workspace, AgentEventListener.NO_OP);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveCli cli = new InteractiveCli(sessions,
                new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8)), workspace);

        cli.run(new BufferedReader(new StringReader("Java 和 C++ 有什么区别\n/exit\n")));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("LLM streaming failed: GLM HTTP provider error 429"), text);
        assertFalse(text.contains("LLM streaming failed: LLM request failed"), text);
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
        assertTrue(text.contains("System > Switched to UNIFIED mode (/code compatibility alias)."));
        assertTrue(text.contains("System > Switched to AUTO mode."));
        assertTrue(text.contains("[AUTO] You >"));
        assertTrue(text.contains("/auto     Legacy per-task CHAT/READ/CODE routing"));
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
        assertTrue(text.contains("[UNIFIED] You >"));
        assertTrue(text.contains("Agent >"));
        assertTrue(text.contains("answer"));
    }

    @Test
    void autoRoutesEachTaskToItsExistingProfileSession() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        CliSessions sessions = new CliSessions(
                simpleAgent(countingClient(chatCalls)),
                simpleAgent(countingClient(readCalls)),
                simpleAgent(countingClient(codeCalls))
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        InteractiveCli cli = new InteractiveCli(sessions, ui, workspace);

        cli.run(new BufferedReader(new StringReader(
                "/auto\n修改 Calculator.java 的 add 方法\n/chat\nJava 和 C++ 有什么区别\n"
                        + "/auto\n读取 README.md 并总结\n/exit\n"
        )));

        assertEquals(1, codeCalls.get());
        assertEquals(1, chatCalls.get());
        assertEquals(1, readCalls.get());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("System > AUTO -> CODE"));
        assertTrue(text.contains("System > AUTO -> READ"));
    }

    @Test
    void defaultUnifiedAgentAnswersGeneralQuestionWithoutRoutingOrTools() throws Exception {
        ToolCapturingClient client = new ToolCapturingClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        InteractiveCli cli = new InteractiveCli(CliAgentFactory.createProfiles(client, workspace, ui), ui, workspace);

        cli.run(new BufferedReader(new StringReader("Java 和 C++ 有什么区别\n/exit\n")));

        assertTrue(client.calls > 0);
        assertTrue(client.toolNames.contains("read_file"));
        assertTrue(client.toolNames.contains("apply_context_patch"));
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Mode: UNIFIED"));
        assertTrue(text.contains("[UNIFIED] You >"));
        assertFalse(text.contains("AUTO ->"));
        assertFalse(text.contains("Tool >"), "a direct question need not call tools or scan the workspace");
        assertFalse(text.contains("System > Task evidence:"), "successful normal chat is quiet by default");
    }

    @Test
    void originalCalculatorRepairRequestEntersUnifiedProfileWithReadEditAndApprovedTestTools() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        ToolCapturingClient client = new ToolCapturingClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        CliSessions sessions = CliAgentFactory.createProfiles(client, workspace, ui);
        InteractiveCli cli = new InteractiveCli(sessions, ui, workspace);
        String task = "请检查当前 Java Maven 项目中的 Calculator.add(int,int) 功能。先阅读相关源码和测试，"
                + "通过已有受控测试工具确认失败原因，再修复实现代码。不要修改、删除或跳过测试，"
                + "不要改变 Maven 配置。修复完成后通过测试验证，并如实报告结果。";

        cli.run(new BufferedReader(new StringReader(task + "\n/exit\n")));

        ToolCapturingClient codeAliasClient = new ToolCapturingClient();
        ConsoleUi aliasUi = new ConsoleUi(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        InteractiveCli codeAlias = new InteractiveCli(
                CliAgentFactory.createProfiles(codeAliasClient, workspace, aliasUi), aliasUi, workspace);
        codeAlias.run(new BufferedReader(new StringReader("/code\n" + task + "\n/exit\n")));

        assertEquals(codeAliasClient.calls, client.calls,
                "default path adds no routing/classification request relative to the /code alias");
        assertTrue(client.toolNames.contains("read_file"));
        assertTrue(client.toolNames.contains("apply_context_patch"));
        assertTrue(client.toolNames.contains("run_maven_test"));
        assertTrue(client.messages.stream().anyMatch(message -> "user".equals(message.role())
                && task.equals(message.content())));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("AUTO ->"));
    }

    @Test
    void liveTestAllowsExactlyTwoCompletedTurnsOnlyWhenExplicitlyEnabledAndKeepsHistory() throws Exception {
        String previousLiveMode = System.getProperty("agent.live-test-mode");
        String previousDiagnostics = System.getProperty("agent.live-test-diagnostics");
        String previousTwoTurnMode = System.getProperty("agent.live-test-two-turn-mode");
        System.setProperty("agent.live-test-mode", "true");
        System.setProperty("agent.live-test-diagnostics", "false");
        System.clearProperty("agent.live-test-two-turn-mode");
        try {
            CapturingClient client = new CapturingClient();
            CapturedCli singleTurn = capturedCli(client);
            singleTurn.cli().run(new DelayedBufferedReader(
                    List.of("/chat", "What is Java?", "Tell me more about it", "/exit"),
                    List.of(0L, 250L, 550L, 850L)
            ));
            assertEquals(1, client.calls.get(), "default live-test mode remains single-turn");

            System.setProperty("agent.live-test-two-turn-mode", "true");
            CapturedCli twoTurn = capturedCli(client);
            twoTurn.cli().run(new DelayedBufferedReader(
                    List.of("/chat", "What is Java?", "Tell me more about it", "/code",
                            "This third task must not run", "/exit"),
                    List.of(0L, 250L, 550L, 850L, 1_100L, 1_400L)
            ));

            assertEquals(3, client.calls.get(), "the opt-in process admits exactly two additional turns");
            assertEquals(List.of("What is Java?", "Tell me more about it"),
                    client.historySnapshots.get(2), "second turn sees first-turn conversation history");
            assertTrue(twoTurn.text().contains("LIVE_TEST_TURN_1_COMPLETE"));
        } finally {
            restoreSystemProperty("agent.live-test-mode", previousLiveMode);
            restoreSystemProperty("agent.live-test-diagnostics", previousDiagnostics);
            restoreSystemProperty("agent.live-test-two-turn-mode", previousTwoTurnMode);
        }
    }

    @Test
    void adaptivePlanningConfigurationIsAvailableInVerboseDetails() throws Exception {
        LLMClient llm = countingClient(new AtomicInteger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        CliSessions sessions = CliAgentFactory.createProfiles(
                llm, workspace, ui, PlanningMode.ADAPTIVE);

        new InteractiveCli(sessions, ui, workspace).run(new BufferedReader(new StringReader("/verbose on\nWhat is Java?\n/exit\n")));

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Planning: ADAPTIVE"));
    }

    @Test
    void defaultUnifiedAgentRoutesGenericCppMutationToExistingCodeProfile() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        InteractiveCli cli = cliWithIndependentSessions(chatCalls, readCalls, codeCalls);

        cli.run(new BufferedReader(new StringReader(
                "修改hello.cpp文件，将里面的内容修改为，打印hello from agent\n/exit\n"
        )));

        assertEquals(0, chatCalls.get());
        assertEquals(0, readCalls.get());
        assertEquals(1, codeCalls.get());
    }

    @Test
    void autoReusesPreviousRouteOnlyForShortContextualFollowUps() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        InteractiveCli cli = cliWithIndependentSessions(chatCalls, readCalls, codeCalls);

        cli.run(new DelayedBufferedReader(
                List.of(
                        "/auto",
                        "读取当前目录的 Python 文件",
                        "是的",
                        "读你刚刚找到的Python文件",
                        "把这些文件打开看看",
                        "Java 和 C++ 有什么区别",
                        "继续讲",
                        "修改 Parser.java",
                        "继续处理",
                        "Calculator.java 有点问题",
                        "修改 agent_manual_test.py",
                        "读取 README.md 并总结",
                        "介绍 Java",
                        "继续讲",
                        "/exit"
                ),
                List.of(
                        0L, 250L, 500L, 750L, 1_000L, 1_250L, 1_500L, 1_750L,
                        2_000L, 2_250L, 2_500L, 2_750L, 3_000L, 3_250L, 3_500L
                )
        ));

        assertEquals(4, chatCalls.get(), "two new CHAT requests and two LOW-confidence continuations");
        assertEquals(6, readCalls.get(), "READ request, continuations, and independent READ requests");
        assertEquals(3, codeCalls.get(), "two new CODE requests plus LOW-confidence continuation");
    }

    @Test
    void reenteringAutoClearsPreviousRouteForPredictableManualOverride() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        InteractiveCli cli = cliWithIndependentSessions(chatCalls, readCalls, codeCalls);

        cli.run(new BufferedReader(new StringReader(
                "/auto\n读取 README.md\n/chat\n是的\n/auto\n继续\n/exit\n"
        )));

        assertEquals(1, readCalls.get());
        assertEquals(2, chatCalls.get());
        assertEquals(0, codeCalls.get());
    }

    @Test
    void autoHandsOffAUniqueFindFilesResultFromReadToCodeWithoutCopyingHistory() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), "int main() {}\n");
        QueueClient readClient = new QueueClient(List.of(
                response("", toolCall("find", "find_files", "{\"pattern\":\"*.cpp\"}")),
                response("found hello.cpp")
        ));
        CapturingClient codeClient = new CapturingClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleUi ui = new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8));
        CliSessions sessions = new CliSessions(simpleAgent(countingClient(new AtomicInteger())),
                readAgent(readClient), simpleAgent(codeClient));
        InteractiveCli cli = new InteractiveCli(sessions, ui, workspace);

        cli.run(new DelayedBufferedReader(
                List.of("/auto", "找所有 cpp 文件", "在这个 cpp 文件里添加 subtract 函数", "/exit"),
                List.of(0L, 250L, 500L, 750L)
        ));

        String task = codeClient.userTasks().get(0);
        assertEquals("在这个 cpp 文件里添加 subtract 函数", task);
        assertTrue(codeClient.internalContext().contains("Working memory:"), codeClient.internalContext());
        assertTrue(codeClient.internalContext().contains("Current candidates: hello.cpp"));
        assertFalse(task.contains("Working memory:"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("Working memory:"));
    }

    @Test
    void autoHandsOffAUniqueFindFilesResultBetweenReadRequests() throws Exception {
        Files.writeString(workspace.resolve("a.py"), "print('a')\n");
        QueueClient readClient = new QueueClient(List.of(
                response("", toolCall("find", "find_files", "{\"pattern\":\"*.py\"}")),
                response("found a.py"),
                response("read a.py")
        ));
        InteractiveCli cli = cli(readAgent(readClient), simpleAgent(countingClient(new AtomicInteger())),
                simpleAgent(countingClient(new AtomicInteger())));

        cli.run(new DelayedBufferedReader(
                List.of("/auto", "找所有 Python 文件", "读这个文件", "/exit"),
                List.of(0L, 250L, 500L, 750L)
        ));

        assertEquals("找所有 Python 文件", readClient.userTasks().get(0));
        String handoff = readClient.userTasks().get(1);
        assertEquals("读这个文件", handoff);
        assertTrue(readClient.internalContext().contains("Working memory:"));
        assertTrue(readClient.internalContext().contains("Current candidates: a.py"));
        assertFalse(handoff.contains("Working memory:"));
    }

    @Test
    void multipleFindResultsArePassedAsAmbiguousCandidatesInsteadOfSelectingOne() throws Exception {
        Files.writeString(workspace.resolve("a.cpp"), "int a;\n");
        Files.writeString(workspace.resolve("b.cpp"), "int b;\n");
        QueueClient readClient = new QueueClient(List.of(
                response("", toolCall("find", "find_files", "{\"pattern\":\"*.cpp\"}")),
                response("found candidates")
        ));
        CapturingClient codeClient = new CapturingClient();
        InteractiveCli cli = cli(readAgent(readClient), simpleAgent(countingClient(new AtomicInteger())),
                simpleAgent(codeClient));

        cli.run(new DelayedBufferedReader(
                List.of("/auto", "找所有 cpp 文件", "修改这个文件", "/exit"),
                List.of(0L, 250L, 500L, 750L)
        ));

        String task = codeClient.userTasks().get(0);
        assertEquals("修改这个文件", task);
        assertTrue(codeClient.internalContext().contains("Current candidates: a.cpp, b.cpp"));
        assertTrue(codeClient.internalContext().contains("Do not choose a candidate arbitrarily"));
        assertFalse(task.contains("resolved the referenced file to"));
    }

    @Test
    void explicitFileAndGeneralKnowledgeDoNotGetHijackedByWorkingContext() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), "int main() {}\n");
        QueueClient readClient = new QueueClient(List.of(
                response("", toolCall("find", "find_files", "{\"pattern\":\"*.cpp\"}")),
                response("found hello.cpp")
        ));
        CapturingClient codeClient = new CapturingClient();
        AtomicInteger chatCalls = new AtomicInteger();
        InteractiveCli cli = cli(readAgent(readClient), simpleAgent(countingClient(chatCalls)), simpleAgent(codeClient));

        cli.run(new DelayedBufferedReader(
                List.of("/auto", "找所有 cpp 文件", "修改 README.md", "Java 和 C++ 有什么区别", "/exit"),
                List.of(0L, 250L, 500L, 750L, 1_000L)
        ));

        String codeTask = codeClient.userTasks().get(0);
        assertEquals("修改 README.md", codeTask);
        assertTrue(codeClient.internalContext().contains("Explicit target: README.md"));
        assertEquals(1, chatCalls.get());
    }

    @Test
    void normalSingleLineSubmitsExactlyOneUserTurn() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);

        captured.cli().run(new BufferedReader(new StringReader("hello\n/exit\n")));

        assertEquals(List.of("hello"), llm.userTasks(), "the line is submitted as one user turn");
        assertEquals(List.of("hello"), llm.userTasks());
    }

    @Test
    void consecutivePasteLinesBecomeOneTurnWithBlankLinesAndIndentation() throws Exception {
        CapturingClient llm = new CapturingClient();
        CapturedCli captured = capturedCli(llm);
        String newline = System.lineSeparator();

        captured.cli().run(new BufferedReader(new StringReader(
                "/chat\n请创建 test_create.py，内容如下：\n\ndef add(a, b):\n    return a + b\n"
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
                        "/chat",
                        "请创建 x.py，内容如下：",
                        "",
                        "def divide(a, b):",
                        "    return a / b"
                ),
                List.of(0L, 0L, 0L, 0L, 150L)
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
                "/chat\n请创建 fenced_test.py：\n```python\ndef multiply(a, b):\n    return a * b\n```\n"
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
        assertFalse(captured.text().contains("[UNIFIED] You > [UNIFIED] You > "));
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

    private static Agent simpleAgent(LLMClient llm) {
        return new Agent(llm, new ToolRegistry(), "test", 2);
    }

    private Agent readAgent(LLMClient llm) {
        return new Agent(llm, ToolRegistry.withFileTools(workspace), "read", 4, TaskMode.READ_ONLY);
    }

    private InteractiveCli cli(Agent read, Agent chat, Agent code) {
        return new InteractiveCli(new CliSessions(chat, read, code), new ConsoleUi(System.out), workspace);
    }

    private static LLMResponse response(String content, ToolCall... calls) {
        return new LLMResponse(content, List.of(calls));
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    private InteractiveCli cliWithIndependentSessions(
            AtomicInteger chatCalls,
            AtomicInteger readCalls,
            AtomicInteger codeCalls
    ) {
        CliSessions sessions = new CliSessions(
                simpleAgent(countingClient(chatCalls)),
                simpleAgent(countingClient(readCalls)),
                simpleAgent(countingClient(codeCalls))
        );
        return new InteractiveCli(sessions, new ConsoleUi(System.out), workspace);
    }

    private static final class CapturingClient implements LLMClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<List<String>> latestUserTasks = new AtomicReference<>(List.of());
        private final AtomicReference<String> latestInternalContext = new AtomicReference<>("");
        private final List<List<String>> historySnapshots = new java.util.ArrayList<>();

        @Override
        public LLMResponse chat(List<Message> messages) {
            calls.incrementAndGet();
            List<String> userTasks = messages.stream()
                    .filter(message -> message.role().equals("user"))
                    .map(Message::content)
                    .toList();
            latestUserTasks.set(userTasks);
            historySnapshots.add(userTasks);
            latestInternalContext.set(messages.stream()
                    .filter(message -> message.role().equals("system"))
                    .map(Message::content)
                    .filter(content -> content.startsWith("INTERNAL WORKSPACE CONTEXT"))
                    .reduce((left, right) -> right).orElse(""));
            return new LLMResponse("answer", List.of());
        }

        private List<String> userTasks() {
            return latestUserTasks.get();
        }

        private String internalContext() {
            return latestInternalContext.get();
        }
    }

    private static final class ToolCapturingClient implements LLMClient {
        private int calls;
        private List<String> toolNames = List.of();
        private List<Message> messages = List.of();

        @Override
        public LLMResponse chat(List<Message> messages) {
            throw new AssertionError("Agent should provide tool definitions to the unified profile");
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<com.agent.llm.ToolDefinition> tools) {
            calls++;
            this.messages = List.copyOf(messages);
            toolNames = tools.stream().map(com.agent.llm.ToolDefinition::name).toList();
            return new LLMResponse("Java and C++ are both programming languages.", List.of());
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static final class QueueClient implements LLMClient {
        private final ArrayDeque<LLMResponse> responses;
        private final List<String> userTasks = new java.util.ArrayList<>();
        private String internalContext = "";

        private QueueClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            userTasks.clear();
            userTasks.addAll(messages.stream().filter(message -> message.role().equals("user"))
                    .map(Message::content).toList());
            internalContext = messages.stream().filter(message -> message.role().equals("system"))
                    .map(Message::content)
                    .filter(content -> content.startsWith("INTERNAL WORKSPACE CONTEXT"))
                    .reduce((left, right) -> right).orElse("");
            return responses.removeFirst();
        }

        private List<String> userTasks() {
            return List.copyOf(userTasks);
        }

        private String internalContext() {
            return internalContext;
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
            while (!ready()) {
                LockSupport.parkNanos(1_000_000L);
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
