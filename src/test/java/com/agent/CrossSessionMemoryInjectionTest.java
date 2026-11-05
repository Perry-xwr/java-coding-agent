package com.agent;

import com.agent.llm.*;
import com.agent.memory.*;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tui.runtime.UiEventLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated Daily request-path proof for authorized cross-session Memory injection. */
class CrossSessionMemoryInjectionTest {
    @TempDir Path root;
    private String priorManaged;
    private static final String PREFERENCE = "我希望以后在这个项目里编写 Python 代码时，函数尽量添加类型注解。这是我长期坚持的开发习惯。";

    @BeforeEach void enableManagedMemory() {
        priorManaged = System.getProperty("agent.memory.agentManaged");
        System.setProperty("agent.memory.agentManaged", "true");
    }

    @AfterEach void restoreManagedMemory() {
        if (priorManaged == null) System.clearProperty("agent.memory.agentManaged");
        else System.setProperty("agent.memory.agentManaged", priorManaged);
    }

    @Test void savedPythonPreferenceEntersNewSessionProviderRequestAndExposureTrace() throws Exception {
        Path workspace = TuiSessionWorkspace.create(root.resolve("workspace"));
        MemoryConsentStore consent = enabledConsent(workspace);
        RecordingProvider provider = new RecordingProvider(PREFERENCE);
        String memoryId;

        try (var adapter = adapter(workspace, provider, consent);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            controller.enableMemoryConsent(consent);
            assertTrue(controller.createSession());
            submit(controller, PREFERENCE + "只讨论，不修改文件。");
            finish(controller, adapter);
            assertEquals("COMPLETED", controller.loop().snapshot().current().turn().status());
            List<LongTermMemory> saved = new SharedMemoryStore(workspace).read();
            assertEquals(1, saved.size());
            assertEquals(PREFERENCE, saved.get(0).content());
            memoryId = saved.get(0).memoryId();

            assertTrue(controller.createSession());
            submit(controller, "我准备写 calculator.py，接下来应该注意什么？只讨论，不修改文件。");
            finish(controller, adapter);
            assertEquals("COMPLETED", controller.loop().snapshot().current().turn().status());

            List<Message> providerMessages = provider.secondSessionMessages;
            assertNotNull(providerMessages, "second-session Provider request was not captured");
            Message injected = providerMessages.stream()
                    .filter(message -> message.content() != null && message.content().contains("CROSS_SESSION_MEMORY_DATA"))
                    .findFirst().orElseThrow(() -> new AssertionError("no Memory block in actual Provider messages"));
            assertTrue(injected.content().contains(memoryId));
            assertTrue(injected.content().contains(PREFERENCE));
            assertFalse(provider.historySearchCalled, "the test must not rely on UX-4 history search");

            var snapshot = new CliConversationManager(workspace)
                    .load(controller.loop().snapshot().currentSessionId()).snapshot();
            var exposures = snapshot.crossSessionMemory().exposures();
            assertEquals(1, exposures.size());
            assertEquals(List.of(memoryId), exposures.get(0).memoryIds());
            assertEquals(CliMode.CODE, exposures.get(0).profile());
        }
    }

    @Test void retrievalRemainsConsentAndWorkspaceScopedAndReadsOnlyEffectiveLibrary() throws Exception {
        Path workspaceA = TuiSessionWorkspace.create(root.resolve("workspace-a"));
        Path workspaceB = TuiSessionWorkspace.create(root.resolve("workspace-b"));
        String sessionId = UUID.randomUUID().toString();
        LongTermMemory effective = new LongTermMemory(UUID.randomUUID().toString(), PREFERENCE, sessionId,
                java.time.Instant.now().toString(), java.time.Instant.now().toString(),
                "AGENT_MANAGED_USER_QUOTE", "EXPLICIT_WORKSPACE_AUTO_POLICY",
                workspaceA.toAbsolutePath().normalize().toString(),
                "CODE:2:" + SessionSummary.digest(PREFERENCE) + ":policy=1");
        new SharedMemoryStore(workspaceA).append(effective);
        String query = "calculator.py";

        String conflicting = "我希望以后在这个项目里编写 Python 代码时，函数不要添加类型注解。";
        var pendingTool = new com.agent.tool.MemoryProposeTool(workspaceA, UUID.randomUUID().toString(), "CODE:2",
                conflicting, 1, () -> true, ignored -> { });
        assertTrue(pendingTool.execute("{\"memoryContent\":\"" + conflicting + "\",\"category\":\"PREFERENCE\"}")
                .output().startsWith("PENDING_REVIEW"));
        String temporary = "今天电脑风扇声音比较大";
        var rejectedTool = new com.agent.tool.MemoryProposeTool(workspaceA, UUID.randomUUID().toString(), "CODE:2",
                temporary, 1, () -> true, ignored -> { });
        assertTrue(rejectedTool.execute("{\"memoryContent\":\"" + temporary + "\",\"category\":\"PREFERENCE\"}")
                .output().startsWith("REJECTED_POLICY"));

        var enabledA = new LocalCrossSessionMemory(new SharedMemoryStore(workspaceA), () -> true, workspaceA);
        assertEquals(List.of(effective.memoryId()), enabledA.retrieveAuthorized(sessionId, query).stream()
                .map(hit -> hit.memory().memoryId()).toList());
        var disabledA = new LocalCrossSessionMemory(new SharedMemoryStore(workspaceA), () -> false, workspaceA);
        assertTrue(disabledA.retrieveAuthorized(sessionId, query).isEmpty());
        var otherWorkspace = new LocalCrossSessionMemory(new SharedMemoryStore(workspaceA), () -> true, workspaceB);
        assertTrue(otherWorkspace.retrieveAuthorized(sessionId, query).isEmpty());
        assertEquals(1, new SharedMemoryStore(workspaceA).read().size(),
                "only effective shared-library entries are eligible for retrieval; session review candidates are separate");
    }

    private static MemoryConsentStore enabledConsent(Path workspace) throws IOException {
        MemoryConsentStore consent = new MemoryConsentStore(workspace.resolve("isolated-consent.json"));
        consent.update(MemoryConsentStore.Settings.off(), true, true, false);
        return consent;
    }

    private static RealAgentAdapter adapter(Path workspace, LLMClient provider, MemoryConsentStore consent) throws IOException {
        return new RealAgentAdapter(workspace, provider, true, null, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"), path -> path, true, consent);
    }

    private static void submit(TuiSessionController controller, String text) {
        controller.loop().edit(UiEventLoop.Edit.CLEAR, "");
        controller.loop().edit(UiEventLoop.Edit.TEXT, text);
        assertNotNull(controller.submit());
    }

    private static void finish(TuiSessionController controller, RealAgentAdapter adapter) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((controller.loop().snapshot().current().turn() == null
                || controller.loop().snapshot().current().turn().running() || !adapter.available())
                && System.nanoTime() < deadline) {
            controller.pump();
            Thread.sleep(3);
        }
        controller.pump();
        assertTrue(adapter.available(), "Mock Agent did not finish");
    }

    private static final class RecordingProvider implements StreamingLlmClient {
        private final String preference;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Message> requestMessages = new ArrayList<>();
        private List<Message> secondSessionMessages;
        private boolean historySearchCalled;

        RecordingProvider(String preference) { this.preference = preference; }

        @Override public LLMResponse chat(List<Message> messages) throws IOException {
            return stream(messages, List.of(), ignored -> { });
        }

        @Override public LLMResponse stream(List<Message> messages, List<ToolDefinition> tools, LlmStreamListener listener) {
            int call = calls.incrementAndGet();
            requestMessages.addAll(messages);
            if (call == 1) return new LLMResponse("", List.of(new ToolCall("memory-proposal", "memory_propose",
                    "{\"memoryContent\":\"" + preference + "\",\"category\":\"PREFERENCE\"}")));
            if (call == 2) return completed(listener, "已保存这条授权的长期偏好。");
            if (call == 3) {
                secondSessionMessages = List.copyOf(messages);
                historySearchCalled = messages.stream().anyMatch(message -> message.toolCalls().stream()
                        .anyMatch(callRecord -> callRecord.toString().contains("search_sessions")));
                return completed(listener, "建议使用 def add(a: int, b: int) -> int。");
            }
            throw new AssertionError("unexpected model request #" + call);
        }

        private static LLMResponse completed(LlmStreamListener listener, String text) {
            listener.onTextDelta(text);
            return new LLMResponse(text, List.of());
        }
    }
}
