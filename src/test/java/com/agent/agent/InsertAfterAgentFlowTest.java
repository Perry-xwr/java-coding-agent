package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.execution.ProcessExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsertAfterAgentFlowTest {
    @TempDir
    Path workspace;

    @Test
    void readsInsertsAndRereadsTheSpecifiedFileBeforeFinalAnswer() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), """
                #include <iostream>
                using namespace std;

                int main() {
                    return 0;
                }
                """);
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("insert", "insert_after", insertArguments())),
                response("", call("read-after", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Added and confirmed the sum function.")
        ));
        Agent agent = new Agent(
                client,
                ToolRegistry.withCliCodingTools(
                        workspace,
                        (command, directory, timeout, maxOutput) ->
                                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1),
                        workspace.resolve(".m2/repository")
                ),
                "coding",
                8,
                TaskMode.CODE_MODIFICATION,
                true,
                false
        );

        assertEquals("Added and confirmed the sum function.", agent.run("在 hello.cpp 中添加 sum 函数"));
        assertEquals(List.of("read_file", "insert_after", "read_file"), calledToolNames(agent.history()));
        assertTrue(Files.readString(workspace.resolve("hello.cpp")).contains("int sum(int a, int b)"));
    }

    @Test
    void singleDifferenceInsertionConvergesAfterTheRequiredReread() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), "using namespace std;\nint main() {}\n");
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("insert", "insert_after", differenceArguments())),
                response("", call("read-after", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Added and confirmed difference.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory("在 hello.cpp 中添加 difference 函数");

        assertTrue(result.trajectory().completed());
        assertEquals("Added and confirmed difference.", result.finalAnswer());
        assertEquals(List.of("read_file", "insert_after", "read_file"), calledToolNames(result));
        assertEquals(1, toolCount(result, "insert_after"));
        assertEquals(0, toolCount(result, "apply_patch"));
        assertTrue(Files.readString(workspace.resolve("hello.cpp")).contains("int difference(int a, int b)"));
    }

    @Test
    void topLevelSubtractUsesACompleteStableAnchorBeforeMain() throws Exception {
        String original = """
                #include <iostream>
                using namespace std;

                int main() {
                    cout << "hello" << endl;
                    return 0;
                }
                """;
        Files.writeString(workspace.resolve("hello.cpp"), original);
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("insert", "insert_after", subtractArguments())),
                response("", call("read-after", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Added and confirmed subtract.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory(
                "在 hello.cpp 中添加 subtract(int a, int b) 函数"
        );
        String expected = """
                #include <iostream>
                using namespace std;

                int subtract(int a, int b) {
                    return a - b;
                }

                int main() {
                    cout << "hello" << endl;
                    return 0;
                }
                """;

        assertTrue(result.trajectory().completed());
        assertEquals(expected, Files.readString(workspace.resolve("hello.cpp")));
        assertEquals(List.of("read_file", "insert_after", "read_file"), calledToolNames(result));
        assertEquals(0, toolCount(result, "apply_patch"));
        assertTrue(Files.readString(workspace.resolve("hello.cpp")).indexOf("int subtract")
                < Files.readString(workspace.resolve("hello.cpp")).indexOf("int main()"));
        assertTrue(result.trajectory().steps().stream()
                .filter(step -> "insert".equals(step.toolCallId()))
                .noneMatch(step -> step.rawArguments().contains("\"anchor\":\"int main\"")));
    }

    @Test
    void topLevelSubtractCanUseInsertBeforeMainWithoutEnteringItsBody() throws Exception {
        String original = """
                #include <iostream>
                using namespace std;

                int main() {
                    cout << "hello" << endl;
                    return 0;
                }
                """;
        Files.writeString(workspace.resolve("hello.cpp"), original);
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("insert", "insert_before", insertBeforeMainArguments())),
                response("", call("read-after", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Added and confirmed subtract.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory(
                "在 hello.cpp 中添加 subtract(int a, int b) 函数"
        );
        String expected = """
                #include <iostream>
                using namespace std;

                int subtract(int a, int b) {
                    return a - b;
                }

                int main() {
                    cout << "hello" << endl;
                    return 0;
                }
                """;

        String updated = Files.readString(workspace.resolve("hello.cpp"));
        assertTrue(result.trajectory().completed());
        assertEquals(expected, updated);
        assertEquals(List.of("read_file", "insert_before", "read_file"), calledToolNames(result));
        assertEquals(0, toolCount(result, "apply_patch"));
        assertTrue(updated.indexOf("int subtract") < updated.indexOf("int main()"));
        assertFalse(updated.substring(updated.indexOf("int main()"), updated.length()).contains("int subtract"));
    }

    @Test
    void javaMethodUsesACompleteExistingMethodBlockAsTheAnchor() throws Exception {
        String original = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Files.writeString(workspace.resolve("Calculator.java"), original);
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"Calculator.java\"}")),
                response("", call("insert", "insert_after", javaSubtractArguments())),
                response("", call("read-after", "read_file", "{\"path\":\"Calculator.java\"}")),
                response("", call("verify", "run_maven_test", "{}")),
                response("Added and verified subtract.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory("添加 subtract 方法");
        String updated = Files.readString(workspace.resolve("Calculator.java"));

        assertTrue(result.trajectory().completed());
        assertTrue(updated.contains("public int add(int a, int b) {\n        return a + b;\n    }"));
        assertTrue(updated.contains("public int subtract(int a, int b)"));
        assertTrue(updated.indexOf("public int subtract") > updated.indexOf("return a + b;"));
        assertTrue(updated.lastIndexOf('}') > updated.indexOf("public int subtract"));
        assertEquals(1, toolCount(result, "run_maven_test"));
    }

    @Test
    void multipleRequestedEditsRemainAllowedAfterAnIntermediateReread() throws Exception {
        Files.writeString(workspace.resolve("changes.txt"), "foo one");
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"changes.txt\"}")),
                response("", call("patch-one", "apply_patch",
                        "{\"path\":\"changes.txt\",\"oldText\":\"foo\",\"newText\":\"bar\"}")),
                response("", call("read-middle", "read_file", "{\"path\":\"changes.txt\"}")),
                response("", call("patch-two", "apply_patch",
                        "{\"path\":\"changes.txt\",\"oldText\":\"one\",\"newText\":\"two\"}")),
                response("", call("read-after", "read_file", "{\"path\":\"changes.txt\"}")),
                response("Both requested changes are complete.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory("把 foo 改成 bar，并把 one 改成 two");

        assertTrue(result.trajectory().completed());
        assertEquals("bar two", Files.readString(workspace.resolve("changes.txt")));
        assertEquals(2, toolCount(result, "apply_patch"));
    }

    @Test
    void blocksSameFileMutationUntilTheSuccessfulWriteHasBeenReread() throws Exception {
        Files.writeString(workspace.resolve("changes.txt"), "foo one");
        QueueClient client = new QueueClient(List.of(
                response("", call("read-before", "read_file", "{\"path\":\"changes.txt\"}")),
                response("", call("patch-one", "apply_patch",
                        "{\"path\":\"changes.txt\",\"oldText\":\"foo\",\"newText\":\"bar\"}")),
                response("", call("stale-patch", "apply_patch",
                        "{\"path\":\"changes.txt\",\"oldText\":\"one\",\"newText\":\"two\"}")),
                response("", call("fresh-read", "read_file", "{\"path\":\"changes.txt\"}")),
                response("", call("patch-two", "apply_patch",
                        "{\"path\":\"changes.txt\",\"oldText\":\"one\",\"newText\":\"two\"}")),
                response("", call("read-after", "read_file", "{\"path\":\"changes.txt\"}")),
                response("Both requested changes are complete.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory("把 foo 改成 bar，并把 one 改成 two");

        assertTrue(result.trajectory().completed());
        assertEquals("bar two", Files.readString(workspace.resolve("changes.txt")));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                "stale-patch".equals(step.toolCallId())
                        && step.toolResult().errorCode() == ToolErrorCode.STALE_EDIT_CONTEXT));
        assertEquals(3, toolCount(result, "apply_patch"));
    }

    @Test
    void insertBeforeAlsoRequiresAFreshReadBeforeAnotherSameFileMutation() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), "int main() {}\n");
        QueueClient client = new QueueClient(List.of(
                response("", call("first", "insert_before", insertBeforeMainArguments())),
                response("", call("stale", "insert_before",
                        "{\"path\":\"hello.cpp\",\"anchor\":\"int main() {\",\"content\":\"// second\\n\"}")),
                response("", call("fresh", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("second", "insert_before",
                        "{\"path\":\"hello.cpp\",\"anchor\":\"int main() {\",\"content\":\"// second\\n\"}")),
                response("", call("verify", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Completed both requested insertions.")
        ));

        AgentRunResult result = codingAgent(client).runWithTrajectory("Add two declarations before main");

        assertTrue(result.trajectory().completed());
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                "stale".equals(step.toolCallId())
                        && step.toolResult().errorCode() == ToolErrorCode.STALE_EDIT_CONTEXT));
        assertEquals(3, toolCount(result, "insert_before"));
    }

    @Test
    void missingAnchorRereadsTheSameFileBeforeARecoveryInsert() throws Exception {
        Files.writeString(workspace.resolve("hello.cpp"), "using namespace std;\nint main() {}\n");
        QueueClient client = new QueueClient(List.of(
                response("", call("missing", "insert_after",
                        "{\"path\":\"hello.cpp\",\"anchor\":\"missing\",\"content\":\"\\nint sum() {}\"}")),
                response("", call("reread", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("", call("insert", "insert_after", insertArguments())),
                response("", call("verify", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("Recovered and confirmed.")
        ));
        Agent agent = codingAgent(client);

        assertEquals("Recovered and confirmed.", agent.run("Add sum to hello.cpp"));
        assertEquals(List.of("insert_after", "read_file", "insert_after", "read_file"),
                calledToolNames(agent.history()));
        assertTrue(Files.readString(workspace.resolve("hello.cpp")).contains("int sum(int a, int b)"));
    }

    @Test
    void ambiguousAnchorReturnsObservationWithoutChoosingAnInsertionPoint() throws Exception {
        String original = "anchor\nanchor\n";
        Files.writeString(workspace.resolve("hello.cpp"), original);
        QueueClient client = new QueueClient(List.of(
                response("", call("ambiguous", "insert_after",
                        "{\"path\":\"hello.cpp\",\"anchor\":\"anchor\",\"content\":\"\\ncontent\"}")),
                response("", call("reread", "read_file", "{\"path\":\"hello.cpp\"}")),
                response("The anchor is ambiguous; no change was made."),
                response("The anchor is ambiguous; no change was made.")
        ));
        Agent agent = codingAgent(client);

        assertEquals("Workspace modification failed: no write operation succeeded "
                + "and no current file read confirmed that a change was unnecessary.",
                agent.run("Insert content in hello.cpp"));
        assertEquals(original, Files.readString(workspace.resolve("hello.cpp")));
        assertTrue(agent.history().stream().anyMatch(message ->
                message.role().equals("tool") && message.content().contains("AMBIGUOUS_MATCH")));
    }

    private Agent codingAgent(LLMClient client) {
        return new Agent(
                client,
                ToolRegistry.withCliCodingTools(
                        workspace,
                        (command, directory, timeout, maxOutput) ->
                                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1),
                        workspace.resolve(".m2/repository")
                ),
                "coding",
                8,
                TaskMode.CODE_MODIFICATION,
                true,
                false
        );
    }

    private static String insertArguments() {
        return "{\"path\":\"hello.cpp\",\"anchor\":\"using namespace std;\",\"content\":\"\\n\\nint sum(int a, int b) {\\n    return a + b;\\n}\"}";
    }

    private static String differenceArguments() {
        return "{\"path\":\"hello.cpp\",\"anchor\":\"using namespace std;\",\"content\":\"\\n\\nint difference(int a, int b) {\\n    return a - b;\\n}\"}";
    }

    private static String subtractArguments() {
        return "{\"path\":\"hello.cpp\",\"anchor\":\"using namespace std;\",\"content\":\"\\n\\nint subtract(int a, int b) {\\n    return a - b;\\n}\"}";
    }

    private static String insertBeforeMainArguments() {
        return "{\"path\":\"hello.cpp\",\"anchor\":\"int main() {\",\"content\":\"int subtract(int a, int b) {\\n    return a - b;\\n}\\n\\n\"}";
    }

    private static String javaSubtractArguments() {
        return "{\"path\":\"Calculator.java\",\"anchor\":\"    public int add(int a, int b) {\\n        return a + b;\\n    }\",\"content\":\"\\n\\n    public int subtract(int a, int b) {\\n        return a - b;\\n    }\"}";
    }

    private static LLMResponse response(String content, ToolCall... calls) {
        return new LLMResponse(content, List.of(calls));
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    private static List<String> calledToolNames(List<Message> history) {
        return history.stream().flatMap(message -> message.toolCalls().stream())
                .map(call -> (Map<?, ?>) call.get("function"))
                .map(function -> function.get("name").toString())
                .toList();
    }

    private static List<String> calledToolNames(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .map(AgentStep::toolName)
                .toList();
    }

    private static long toolCount(AgentRunResult result, String toolName) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .filter(step -> toolName.equals(step.toolName()))
                .count();
    }

    private static final class QueueClient implements LLMClient {
        private final ArrayDeque<LLMResponse> responses;

        private QueueClient(List<LLMResponse> scriptedResponses) {
            this.responses = new ArrayDeque<>(scriptedResponses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return responses.removeFirst();
        }
    }
}
