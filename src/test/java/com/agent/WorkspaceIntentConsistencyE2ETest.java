package com.agent;

import com.agent.llm.DailyDeepSeekClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests through the Daily Controller, Agent, Tool Dispatch and completion guard. */
class WorkspaceIntentConsistencyE2ETest {
    @TempDir Path root;
    private final DailyRuntimeTest helper = new DailyRuntimeTest();

    @Test void explicitChatOnlyHypotheticalCodeAnswerCompletesWithoutWorkspaceIo() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("chat-only"));
        AtomicInteger calls = new AtomicInteger();
        List<Set<String>> offeredTools = new ArrayList<>();
        String request = "假设我有一个名为 `math_helpers.py` 的新模块，需要实现两个整数相加的函数。"
                + "请只在聊天中给出函数签名和简短实现，不要读取、创建或修改任何文件。";
        String answer = "```python\ndef add(a: int, b: int) -> int:\n    return a + b\n```";

        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(raw -> {
                 int call = calls.incrementAndGet();
                 try {
                     var bytes = new okio.Buffer();
                     raw.body().writeTo(bytes);
                     var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes.readByteArray());
                     var names = new LinkedHashSet<String>();
                     body.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
                     offeredTools.add(names);
                     return helper.answer(raw, answer);
                 } catch (Exception failure) { throw new java.io.IOException(failure); }
             });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            helper.send(controller, adapter, request);
            assertEquals("COMPLETED", controller.loop().snapshot().current().turn().status(),
                    controller.loop().snapshot().notice());
            assertEquals(1, calls.get(), "a valid chat-only answer should not trigger a mutation completion retry");
            assertFalse(offeredTools.get(0).stream().anyMatch(WorkspaceIntentConsistencyE2ETest::workspaceIoTool),
                    "the explicit chat-only boundary must suppress workspace I/O tools");
            assertFalse(Files.exists(workspace.resolve("math_helpers.py")));
        }
    }

    @Test void sameSessionReadCreateEditReadEditAndPreferenceTurnsUseTheirOwnDecision() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("turn-switching"));
        Map<String, AtomicInteger> steps = new HashMap<>();
        List<String> fileIoSchemas = new ArrayList<>();

        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(raw -> {
                 try {
                     var bytes = new okio.Buffer(); raw.body().writeTo(bytes);
                     var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes.readByteArray());
                     String task = "";
                     for (var message : body.path("messages"))
                         if ("user".equals(message.path("role").asText())) task = message.path("content").asText();
                     Set<String> offered = new HashSet<>();
                     body.path("tools").forEach(tool -> offered.add(tool.path("function").path("name").asText()));
                     if (task.contains("只在聊天中")) {
                         fileIoSchemas.add(String.join(",", offered.stream().filter(WorkspaceIntentConsistencyE2ETest::workspaceIoTool).sorted().toList()));
                         return helper.answer(raw, "只在聊天中回答；没有访问或修改文件。\n");
                     }
                     if (task.contains("长期偏好")) return helper.answer(raw, "已理解这个未来偏好；本轮没有修改文件。\n");
                     int step = steps.computeIfAbsent(task, ignored -> new AtomicInteger()).incrementAndGet();
                     if (task.contains("创建 flow.py")) {
                         if (step == 1) return helper.reply(raw, helper.call("create_file", "{\"path\":\"flow.py\",\"content\":\"value = 0\\n\"}"));
                         return helper.answer(raw, "已创建 flow.py。\n");
                     }
                     if (task.contains("修改 flow.py")) {
                         int old = task.contains("从 0 改为 1") ? 0 : 1;
                         if (step == 1) return helper.reply(raw, helper.call("read_file", "{\"path\":\"flow.py\"}"));
                         if (step == 2) return helper.reply(raw, helper.call("apply_context_patch", "{\"path\":\"flow.py\",\"expectedContent\":\"value = " + old + "\\n\",\"edits\":[{\"search\":\"value = " + old + "\",\"replace\":\"value = " + (old + 1) + "\"}]}"));
                         if (step == 3) return helper.reply(raw, helper.call("read_file", "{\"path\":\"flow.py\"}"));
                         return helper.answer(raw, "已修改并重新读取确认。\n");
                     }
                     if (task.contains("创建 mixed.py")) {
                         if (step == 1) return helper.reply(raw, helper.call("create_file", "{\"path\":\"mixed.py\",\"content\":\"def add(a: int, b: int) -> int:\\n    return a + b\\n\"}"));
                         return helper.answer(raw, "已创建 mixed.py。\n");
                     }
                     throw new AssertionError("unexpected turn: " + task);
                 } catch (Exception failure) { throw new java.io.IOException(failure); }
             });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            helper.send(controller, adapter, "只在聊天中解释 Python 函数的类型注解，不要读取、创建或修改任何文件。");
            assertCompleted(controller);
            String session = controller.loop().snapshot().currentSessionId();
            helper.send(controller, adapter, "请创建 flow.py，内容为 value = 0。");
            assertCompleted(controller);
            assertEquals("value = 0\n", Files.readString(workspace.resolve("flow.py")));
            helper.send(controller, adapter, "请修改 flow.py，把值从 0 改为 1。");
            assertCompleted(controller);
            assertEquals("value = 1\n", Files.readString(workspace.resolve("flow.py")));
            helper.send(controller, adapter, "只在聊天中说明 flow.py 的值，不要读取、创建或修改任何文件。");
            assertCompleted(controller);
            helper.send(controller, adapter, "请修改 flow.py，把值从 1 改为 2。");
            assertCompleted(controller);
            assertEquals("value = 2\n", Files.readString(workspace.resolve("flow.py")));
            helper.send(controller, adapter, "以后写 Python 时函数添加类型注解，这是长期偏好。只讨论，不修改文件。");
            assertCompleted(controller);
            helper.send(controller, adapter, "以后 Python 函数添加类型注解；现在请创建 mixed.py。");
            assertCompleted(controller);
            assertTrue(Files.exists(workspace.resolve("mixed.py")));
            assertEquals(session, controller.loop().snapshot().currentSessionId());
            assertTrue(fileIoSchemas.stream().allMatch(String::isEmpty),
                    "chat-only turns should not expose file tools");
        }
    }

    @Test void deniedReadOnlyWriteIsBlockedAndRequiredWriteStillFailsWithoutMutation() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("denial"));
        AtomicInteger calls = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(workspace);
             var client = DailyDeepSeekClient.offline(raw -> {
                 try {
                     if (calls.incrementAndGet() == 1)
                         return helper.reply(raw, helper.call("create_file", "{\"path\":\"unsolicited.py\",\"content\":\"bad\"}"));
                     return helper.answer(raw, "该写入没有授权，文件未创建。\n");
                 } catch (Exception failure) { throw new java.io.IOException(failure); }
             });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(workspace, true, adapter)) {
            helper.send(controller, adapter, "只在聊天中给出代码，不要读取、创建或修改任何文件。");
            assertCompleted(controller);
            assertFalse(Files.exists(workspace.resolve("unsolicited.py")));
            assertTrue(controller.loop().snapshot().current().turn().tools().stream()
                    .anyMatch(tool -> tool.name().equals("create_file") && tool.status().equals("FAIL")));
            assertEquals(2, calls.get());
        }

        Path required = Files.createDirectory(root.resolve("required-but-not-done"));
        AtomicInteger requiredCalls = new AtomicInteger();
        try (var lease = DailyWorkspace.acquire(required);
             var client = DailyDeepSeekClient.offline(raw -> {
                 requiredCalls.incrementAndGet();
                 return helper.answer(raw, "已经创建了 missing.py。\n");
             });
             var adapter = new DailyAgentAdapter(lease, client, null);
             var controller = new TuiSessionController(required, true, adapter)) {
            helper.send(controller, adapter, "请在当前工作区创建 missing.py。");
            assertEquals("FAILED", controller.loop().snapshot().current().turn().status());
            assertTrue(controller.loop().snapshot().notice().contains("WORKSPACE_CHANGE_NOT_COMPLETED"));
            assertEquals(2, requiredCalls.get());
            assertFalse(Files.exists(required.resolve("missing.py")));
        }
    }

    private void assertCompleted(TuiSessionController controller) {
        assertEquals("COMPLETED", controller.loop().snapshot().current().turn().status(),
                controller.loop().snapshot().notice());
    }

    private static boolean workspaceIoTool(String name) {
        return Set.of("list_files", "find_files", "search_code", "read_file", "create_file", "apply_patch",
                "apply_context_patch", "insert_before", "insert_after", "delete_file", "run_process", "run_maven_test")
                .contains(name);
    }
}
