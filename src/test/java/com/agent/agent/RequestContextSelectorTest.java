package com.agent.agent;

import com.agent.llm.Message;
import com.agent.llm.RequestContextMetrics;
import com.agent.llm.ToolDefinition;
import com.agent.llm.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextSelectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void omitsOnlyAnExactDuplicateOldDiscoveryExchangeAndKeepsCallsPaired() throws Exception {
        Message system = Message.system("system policy");
        Message oldUser = Message.user("Find Python files");
        Message oldCall = call("old-call", "find_files", "{\"pattern\":\"*.py\"}");
        Message oldResult = Message.tool("old-call", "{\"files\":[\"src/a.py\"],\"truncated\":false}");
        Message currentUser = Message.user("Find Python files again");
        Message currentCall = call("current-call", "find_files", "{\"pattern\":\"*.py\"}");
        Message currentResult = Message.tool("current-call", "{\"files\":[\"src/a.py\"],\"truncated\":false}");
        List<Message> raw = List.of(system, oldUser, oldCall, oldResult,
                currentUser, currentCall, currentResult);
        int currentTurnStart = 4;

        List<Message> selected = RequestContextSelector.select(raw, currentTurnStart);

        assertEquals(List.of(system, oldUser, currentUser, currentCall, currentResult), selected);
        assertEquals(raw, List.of(system, oldUser, oldCall, oldResult,
                currentUser, currentCall, currentResult), "selection must not mutate source history");
        assertWellPaired(selected);
    }

    @Test
    void neverPrunesCurrentTurnOrUnpairedOrNonDiscoveryToolExchanges() throws Exception {
        Message system = Message.system("system policy");
        Message oldRead = call("read-1", "read_file", "{\"path\":\"A.java\"}");
        Message oldReadResult = Message.tool("read-1", "FULL SOURCE SNAPSHOT");
        Message incompleteCall = call("missing-result", "search_code", "{\"keyword\":\"TODO\"}");
        Message currentUser = Message.user("Continue editing A.java");
        Message currentPatch = call("patch-current", "apply_context_patch", "{\"expectedContent\":\"full\"}");
        Message currentToolResult = Message.tool("patch-current", "PATCH_OK");
        List<Message> raw = List.of(system, oldRead, oldReadResult, incompleteCall,
                currentUser, currentPatch, currentToolResult);

        List<Message> selected = RequestContextSelector.select(raw, 4);

        assertEquals(raw, selected);
        assertTrue(selected.contains(oldReadResult), "read_file snapshot is never summarized or removed");
        assertTrue(selected.stream().anyMatch(message -> message.toolCalls().stream()
                .anyMatch(call -> "missing-result".equals(call.get("id")))),
                "an incomplete group is retained unchanged instead of being partially filtered");
    }

    @Test
    void removesOnlyExactDuplicateInternalContextAndPreservesCurrentOne() {
        Message system = Message.system("root instructions");
        Message context = Message.system("INTERNAL WORKSPACE CONTEXT (private runtime context):\nKnown file: A.java");
        Message user = Message.user("follow up");
        List<Message> raw = List.of(system, context, context, user, context);

        List<Message> selected = RequestContextSelector.select(raw, 3);

        assertEquals(List.of(system, user, context), selected);
        assertEquals(raw.size(), 5, "raw history remains available to session persistence");
    }

    @Test
    void keepsRepeatedFailureObservationsBecauseTheyMayBeRecoveryEvidence() throws Exception {
        List<Message> messages = List.of(Message.system("policy"), Message.user("search"),
                call("f1", "search_code", "{\"keyword\":\"TODO\"}"),
                Message.tool("f1", "{\"success\":false,\"errorCode\":\"WORKSPACE_VIOLATION\"}"),
                Message.user("search again"),
                call("f2", "search_code", "{\"keyword\":\"TODO\"}"),
                Message.tool("f2", "{\"success\":false,\"errorCode\":\"WORKSPACE_VIOLATION\"}"),
                Message.user("explain"));

        assertEquals(messages, RequestContextSelector.select(messages, 7));
    }

    @Test
    void serializedMessagePayloadGetsSmallerWithoutClaimingTokenSavings() throws Exception {
        String largeResult = "{\"matches\":[" + java.util.Collections.nCopies(500, "\"same match\"")
                .stream().collect(java.util.stream.Collectors.joining(",")) + "],\"truncated\":false}";
        List<Message> raw = List.of(
                Message.system("policy"),
                Message.user("search TODO"),
                call("s1", "search_code", "{\"keyword\":\"TODO\"}"),
                Message.tool("s1", largeResult),
                Message.user("search TODO again"),
                call("s2", "search_code", "{\"keyword\":\"TODO\"}"),
                Message.tool("s2", largeResult),
                Message.user("summarize the matches")
        );
        List<Message> selected = RequestContextSelector.select(raw, 7);
        byte[] rawPayload = JSON.writeValueAsBytes(Map.of("messages", raw, "tools", List.of()));
        byte[] selectedPayload = JSON.writeValueAsBytes(Map.of("messages", selected, "tools", List.of()));
        RequestContextMetrics rawMetrics = RequestContextMetrics.of(raw, List.<ToolDefinition>of(), rawPayload.length);
        RequestContextMetrics selectedMetrics = RequestContextMetrics.of(selected, List.<ToolDefinition>of(), selectedPayload.length);

        assertTrue(selectedMetrics.payloadBytes() < rawMetrics.payloadBytes());
        assertTrue(selectedMetrics.messageCount() < rawMetrics.messageCount());
        assertWellPaired(selected);
    }

    @Test
    void longHistoryDropsRepeatedListAndSearchResultsButKeepsFullReadFileSnapshot() throws Exception {
        String listResult = "{\"files\":[" + java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> "{\"path\":\"src/generated/Directory" + index + "/VeryLongName.java\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "],\"truncated\":false}";
        String match = "{\"file\":\"src/A.java\",\"line\":1,\"content\":\"TODO marker\"}";
        String searchResult = "{\"matches\":[" + java.util.Collections.nCopies(120, match).stream()
                .collect(java.util.stream.Collectors.joining(",")) + "],\"truncated\":false}";
        String fullSnapshot = "class A {\n" + "    // source snapshot line\n".repeat(1200) + "}\n";
        List<Message> history = new java.util.ArrayList<>();
        history.add(Message.system("system constraints"));
        for (int index = 0; index < 12; index++) {
            history.add(call("list-" + index, "list_files", "{\"path\":\".\",\"offset\":0}"));
            history.add(Message.tool("list-" + index, listResult));
            history.add(call("search-" + index, "search_code", "{\"keyword\":\"TODO\",\"offset\":0}"));
            history.add(Message.tool("search-" + index, searchResult));
        }
        history.add(call("read-snapshot", "read_file", "{\"path\":\"src/A.java\"}"));
        history.add(Message.tool("read-snapshot", fullSnapshot));
        history.add(Message.user("continue editing src/A.java"));
        List<Message> raw = List.copyOf(history);

        List<Message> selected = RequestContextSelector.select(raw, raw.size());

        assertEquals(1, countCalls(selected, "list_files"));
        assertEquals(1, countCalls(selected, "search_code"));
        assertEquals(1, countCalls(selected, "read_file"));
        assertTrue(selected.stream().anyMatch(message -> message.content().equals(fullSnapshot)),
                "full read_file snapshots are not truncated");
        assertTrue(JSON.writeValueAsBytes(Map.of("messages", selected)).length
                < JSON.writeValueAsBytes(Map.of("messages", raw)).length);
        assertWellPaired(selected);
    }

    @Test
    void defaultConfigurationIsOffAndCanBeEnabledExplicitly() {
        String previous = System.getProperty(RequestContextSelector.ENABLED_PROPERTY);
        try {
            System.setProperty(RequestContextSelector.ENABLED_PROPERTY, "false");
            assertFalse(RequestContextSelector.enabled());
            System.setProperty(RequestContextSelector.ENABLED_PROPERTY, "true");
            assertTrue(RequestContextSelector.enabled());
        } finally {
            if (previous == null) System.clearProperty(RequestContextSelector.ENABLED_PROPERTY);
            else System.setProperty(RequestContextSelector.ENABLED_PROPERTY, previous);
        }
    }

    private static Message call(String id, String name, String arguments) {
        return Message.assistant("", List.of(new ToolCall(id, name, arguments)));
    }

    private static void assertWellPaired(List<Message> messages) {
        java.util.Set<String> calls = new java.util.HashSet<>();
        java.util.Set<String> results = new java.util.HashSet<>();
        for (Message message : messages) {
            if ("assistant".equals(message.role())) {
                message.toolCalls().forEach(call -> calls.add((String) call.get("id")));
            } else if ("tool".equals(message.role())) {
                results.add(message.toolCallId());
            }
        }
        assertEquals(calls, results);
    }

    private static long countCalls(List<Message> messages, String name) {
        return messages.stream().flatMap(message -> message.toolCalls().stream())
                .filter(call -> ((Map<?, ?>) call.get("function")).get("name").equals(name)).count();
    }
}
