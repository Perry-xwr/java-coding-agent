package com.agent.tool;

import com.agent.CliAgentFactory;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentStep;
import com.agent.agent.PlanningMode;
import com.agent.agent.TaskEvidence;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.execution.secure.ApprovalDecision;
import com.agent.execution.secure.ExecutionApprovalPrompt;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.execution.ProcessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline gate for the production Unified CODE flow with test-only approval and process seams. */
class UnifiedCalculatorOfflineGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "package sample;\n"
            + "public final class Calculator {\n"
            + "    public int add(int a, int b) { return a - b; }\n"
            + "}\n";
    private static final String FIXED_SOURCE = SOURCE.replace("a - b", "a + b");
    private static final String TEST_SOURCE = "package sample;\n"
            + "final class CalculatorTest { /* add(2, 3) should equal 5 */ }\n";
    private static final String POM = "<project><modelVersion>4.0.0</modelVersion></project>\n";
    private static final String TASK = "请检查当前 Java Maven 项目中的 Calculator.add(int,int) 功能。先阅读相关源码和测试，"
            + "通过已有受控测试工具确认失败原因，再修复实现代码。不要修改、删除或跳过测试，不要改变 Maven 配置。"
            + "修复完成后通过测试验证，并如实报告结果。";

    @TempDir
    Path workspace;

    @Test
    void unifiedCalculatorTaskReadsFailsFreshReadsRepairsAndReapprovesForPass() throws Exception {
        Path source = writeFixture();
        Path test = workspace.resolve("src/test/java/sample/CalculatorTest.java");
        Path pom = workspace.resolve("pom.xml");
        List<ExecutionApprovalPrompt> approvals = new ArrayList<>();
        List<List<String>> commands = new ArrayList<>();
        int[] processCalls = {0};
        Function<ExecutionApprovalPrompt, ApprovalDecision> approver = prompt -> {
            approvals.add(prompt);
            return approved(prompt);
        };
        var registry = ToolRegistry.withCliCodingToolsForTesting(workspace,
                (command, directory, timeout, maxOutput) -> {
                    processCalls[0]++;
                    commands.add(List.copyOf(command));
                    assertEquals(workspace.toAbsolutePath().normalize(), directory);
                    assertEquals(Duration.ofSeconds(120), timeout);
                    if (processCalls[0] == 1) {
                        return new ProcessExecutionResult(1, false,
                                "[ERROR] sample.CalculatorTest.adds -- Time elapsed: 0.01 s <<< FAILURE!\n"
                                        + "org.opentest4j.AssertionFailedError: expected: <5> but was: < -1 >\n"
                                        + "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0",
                                false, 12);
                    }
                    return new ProcessExecutionResult(0, false,
                            "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",
                            false, 10);
                }, workspace.resolve(".m2/repository"), approver);
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, registry,
                new VerifierRegistry(List.of()));
        ScriptClient client = new ScriptClient(
                tool("find-calculator-files", "find_files", Map.of("pattern", "**/Calculator*.java")),
                tool("read-source", "read_file", Map.of("path", "src/main/java/sample/Calculator.java")),
                tool("read-test", "read_file", Map.of("path", "src/test/java/sample/CalculatorTest.java")),
                tool("maven-fail", "run_maven_test", Map.of()),
                tool("fresh-read", "read_file", Map.of("path", "src/main/java/sample/Calculator.java")),
                tool("repair", "apply_context_patch", Map.of(
                        "path", "src/main/java/sample/Calculator.java",
                        "expectedContent", SOURCE,
                        "edits", List.of(Map.of("search", "return a - b;", "replace", "return a + b;")))),
                tool("maven-pass", "run_maven_test", Map.of()),
                new LLMResponse("修复完成，模拟 Maven 测试通过。", List.of())
        );
        AgentRunResult result = CliAgentFactory.createCoding(client, environment,
                AgentEventListener.NO_OP, PlanningMode.REACTIVE).runWithTrajectory(TASK);

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().toString());
        assertEquals(FIXED_SOURCE, Files.readString(source));
        assertEquals(TEST_SOURCE, Files.readString(test));
        assertEquals(POM, Files.readString(pom));
        assertEquals(2, processCalls[0]);
        assertEquals(2, approvals.size());
        assertEquals(2, commands.size());
        assertTrue(commands.stream().allMatch(command -> command.get(command.size() - 1).equals("test")));
        assertNotEquals(approvals.get(0).challenge(), approvals.get(1).challenge());
        assertNotEquals(approvals.get(0).requestDigest(), approvals.get(1).requestDigest());
        assertTrue(approvals.stream().allMatch(prompt -> prompt.workspaceCopy()
                .contains(workspace.toAbsolutePath().normalize().toString())));
        assertTrue(approvals.stream().allMatch(prompt -> prompt.riskNotice()
                .contains("current Windows user permissions")));

        List<AgentStep> steps = result.trajectory().steps();
        assertEquals(List.of("find_files", "read_file", "read_file", "run_maven_test", "read_file",
                        "apply_context_patch", "read_file", "run_maven_test"),
                steps.stream().filter(step -> step.actionType() == AgentActionType.TOOL_CALL
                                || step.actionType() == AgentActionType.AUTO_REREAD)
                        .map(AgentStep::toolName).toList());
        assertTrue(steps.stream().anyMatch(step -> step.toolCallId() != null
                && "run_maven_test".equals(step.toolName())
                && "ASSERTION".equals(step.toolResult().metadata().get("diagnosticType"))
                && step.toolResult().output().contains("expected: <5>")));
        assertTrue(client.observedMessages.stream().anyMatch(messages -> messages.stream()
                .anyMatch(message -> message.content().contains("expected: <5>")
                        && message.content().contains("MAVEN_RESULT"))));

        TaskEvidence evidence = result.evidence();
        assertEquals(TaskEvidence.ExecutionStatus.COMPLETED, evidence.executionStatus());
        assertEquals(TaskEvidence.MutationStatus.SUCCESS, evidence.mutationStatus());
        assertEquals(TaskEvidence.EvidenceStatus.PASS, evidence.functionalVerification());
        assertEquals(TaskEvidence.CompletionAssessment.FUNCTIONAL_TEST_PASS_EVIDENCE,
                evidence.completionAssessment());
        assertTrue(evidence.evidenceSources().stream().filter(item ->
                "MAVEN_APPROVAL".equals(item.eventType())).count() == 2);
        assertTrue(evidence.evidenceSources().stream().filter(item ->
                "MAVEN_PROCESS_EXECUTION".equals(item.eventType())).count() == 2);
        assertTrue(result.changeReview().changedFiles().stream().anyMatch(change ->
                change.path().equals("src/main/java/sample/Calculator.java")));
        assertFalse(result.changeReview().changedFiles().stream().anyMatch(change ->
                change.path().equals("src/test/java/sample/CalculatorTest.java")
                        || change.path().equals("pom.xml")));
    }

    @Test
    void deniedMavenApprovalDoesNotStartProcessOrAllowRepair() throws Exception {
        Path source = writeFixture();
        int[] processCalls = {0};
        int[] approvalCalls = {0};
        var registry = ToolRegistry.withCliCodingToolsForTesting(workspace,
                (command, directory, timeout, maxOutput) -> {
                    processCalls[0]++;
                    return new ProcessExecutionResult(0, false,
                            "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",
                            false, 1);
                }, workspace.resolve(".m2/repository"), prompt -> {
                    approvalCalls[0]++;
                    return new ApprovalDecision(ApprovalDecision.Status.DENIED,
                            prompt.requestDigest(), "");
                });
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, registry,
                new VerifierRegistry(List.of()));
        ScriptClient client = new ScriptClient(
                tool("find-calculator-files", "find_files", Map.of("pattern", "**/Calculator*.java")),
                tool("read-source", "read_file", Map.of("path", "src/main/java/sample/Calculator.java")),
                tool("read-test", "read_file", Map.of("path", "src/test/java/sample/CalculatorTest.java")),
                tool("maven-denied", "run_maven_test", Map.of()),
                new LLMResponse("测试没有获批，因此我不会声称测试通过。", List.of()),
                new LLMResponse("未获得用户授权，测试没有运行；当前请求的修复也没有完成。", List.of())
        );

        AgentRunResult result = CliAgentFactory.createCoding(client, environment,
                AgentEventListener.NO_OP, PlanningMode.REACTIVE).runWithTrajectory(TASK);

        assertEquals(1, approvalCalls[0]);
        assertEquals(0, processCalls[0]);
        assertEquals(SOURCE, Files.readString(source));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                "run_maven_test".equals(step.toolName())
                        && step.toolResult().errorCode() == ToolErrorCode.APPROVAL_DENIED));
        assertEquals(TaskEvidence.EvidenceStatus.NOT_RUN, result.evidence().functionalVerification());
        assertFalse(result.evidence().evidenceSources().stream().anyMatch(item ->
                "MAVEN_PROCESS_EXECUTION".equals(item.eventType())));
    }

    private Path writeFixture() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/sample"));
        Files.createDirectories(workspace.resolve("src/test/java/sample"));
        Path source = workspace.resolve("src/main/java/sample/Calculator.java");
        Files.writeString(source, SOURCE);
        Files.writeString(workspace.resolve("src/test/java/sample/CalculatorTest.java"), TEST_SOURCE);
        Files.writeString(workspace.resolve("pom.xml"), POM);
        return source;
    }

    private static ApprovalDecision approved(ExecutionApprovalPrompt prompt) {
        return new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(),
                UUID.randomUUID().toString());
    }

    private static LLMResponse tool(String id, String name, Object arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, name, JSON.writeValueAsString(arguments))));
    }

    private static final class ScriptClient implements LLMClient {
        private final ArrayDeque<LLMResponse> responses;
        private final List<List<Message>> observedMessages = new ArrayList<>();

        private ScriptClient(LLMResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return chat(messages, List.of());
        }

        @Override
        public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            observedMessages.add(List.copyOf(messages));
            assertTrue(tools.stream().anyMatch(tool -> "run_maven_test".equals(tool.name())));
            if (responses.isEmpty()) throw new AssertionError("Fake response script exhausted");
            return responses.removeFirst();
        }
    }
}
