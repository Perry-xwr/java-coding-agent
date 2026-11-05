package com.agent.agent;

import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.verification.CodeVerifier;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostEditVerificationAgentTest {
    @TempDir
    Path workspace;

    @Test
    void brokenHanoiStyleMutationCannotBeReportedAsSuccessful() throws Exception {
        Files.writeString(workspace.resolve("HanoiTowerSolver.cpp"), "int main() {\n  return 0;\n}\n");
        Script script = new Script(
                call("read_file", Map.of("path", "HanoiTowerSolver.cpp")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "int main() {", "newText", "() {")),
                finalAnswer("done"),
                finalAnswer("still done"));
        Agent agent = agent(workspace, alwaysFailingVerifier("HanoiTowerSolver.cpp"), script);

        AgentRunResult result = agent.runWithTrajectory("Repair HanoiTowerSolver.cpp");

        assertFalse(result.trajectory().completed());
        assertEquals(1, count(result, AgentActionType.POST_EDIT_VERIFICATION, VerificationStatus.FAIL));
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("expected ')'")
                || messages.contains("syntax")));
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("POST_EDIT_VERIFICATION_FAILED")));
    }

    @Test
    void verificationFailureFeedsDiagnosticsThenRepairPasses() throws Exception {
        Files.writeString(workspace.resolve("HanoiTowerSolver.cpp"), "int main() {\n  return 0;\n}\n");
        Script script = new Script(
                call("read_file", Map.of("path", "HanoiTowerSolver.cpp")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "int main() {", "newText", "() {")),
                call("read_file", Map.of("path", "HanoiTowerSolver.cpp")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "() {", "newText", "int main() {")),
                finalAnswer("Repaired and syntax verified."));
        Agent agent = agent(workspace, contentVerifier("HanoiTowerSolver.cpp", "int main() {"), script);

        AgentRunResult result = agent.runWithTrajectory("Repair HanoiTowerSolver.cpp");

        assertTrue(result.trajectory().completed());
        assertEquals("int main() {\n  return 0;\n}\n", Files.readString(workspace.resolve("HanoiTowerSolver.cpp")));
        assertEquals(List.of(VerificationStatus.FAIL, VerificationStatus.PASS), statuses(result));
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("POST_EDIT_VERIFICATION")
                && messages.contains("synthetic syntax diagnostic")));
        assertTrue(result.trajectory().steps().stream().anyMatch(step -> step.toolResult() != null
                && step.toolResult().errorCode() == com.agent.tool.ToolErrorCode.REPAIR_REQUIRES_FRESH_READ) == false);
        assertEquals(1, result.trajectory().steps().stream().filter(step ->
                step.actionType() == AgentActionType.VERIFICATION_REPAIR
                        && "REPAIR_RECOVERED".equals(step.arguments().get("event"))).count());
    }

    @Test
    void eachLatestMutationInvalidatesPreviousPassAndMultiFileFailureBlocksFinal() throws Exception {
        Files.writeString(workspace.resolve("A.py"), "value = 1\n");
        Files.writeString(workspace.resolve("B.py"), "value = 1\n");
        Script script = new Script(
                call("read_file", Map.of("path", "A.py")),
                call("apply_patch", Map.of("path", "A.py", "oldText", "1", "newText", "2")),
                call("read_file", Map.of("path", "B.py")),
                call("apply_patch", Map.of("path", "B.py", "oldText", "1", "newText", "broken")),
                finalAnswer("finished"),
                call("read_file", Map.of("path", "B.py")),
                call("apply_patch", Map.of("path", "B.py", "oldText", "broken", "newText", "3")),
                finalAnswer("finished"));
        Agent agent = agent(workspace, verifierByContent(), script);

        AgentRunResult result = agent.runWithTrajectory("Update A.py and B.py");

        assertTrue(result.trajectory().completed());
        assertEquals(List.of(VerificationStatus.PASS, VerificationStatus.FAIL,
                VerificationStatus.PASS), statuses(result));
        assertTrue(Files.readString(workspace.resolve("A.py")).contains("2"));
        assertTrue(Files.readString(workspace.resolve("B.py")).contains("3"));
    }

    @Test
    void rejectsImmediateRepairUntilFreshReadThenAllowsRepair() throws Exception {
        Files.writeString(workspace.resolve("HanoiTowerSolver.cpp"), "int main() { return 0; }\n");
        Script script = new Script(
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "int main", "newText", "broken")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "broken", "newText", "repaired")),
                call("read_file", Map.of("path", "HanoiTowerSolver.cpp")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "broken", "newText", "int main")),
                finalAnswer("repaired"));
        AgentRunResult result = agent(workspace, contentVerifier("HanoiTowerSolver.cpp", "int main"), script)
                .runWithTrajectory("repair file");
        assertTrue(result.trajectory().completed());
        assertEquals(1, result.trajectory().steps().stream().filter(step -> step.toolResult() != null
                && step.toolResult().errorCode() == com.agent.tool.ToolErrorCode.REPAIR_REQUIRES_FRESH_READ).count());
        assertEquals(1, result.trajectory().steps().stream().filter(step -> step.actionType()
                == AgentActionType.VERIFICATION_REPAIR && "REPAIR_READ_SATISFIED".equals(step.arguments().get("event"))).count());
    }

    @Test
    void repeatedFailureCreatesNewContextAndReactivatesReadRequirement() throws Exception {
        Files.writeString(workspace.resolve("HanoiTowerSolver.cpp"), "int main() { return 0; }\n");
        Script script = new Script(
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "int main", "newText", "broken")),
                call("read_file", Map.of("path", "HanoiTowerSolver.cpp")),
                call("apply_patch", Map.of("path", "HanoiTowerSolver.cpp", "oldText", "broken", "newText", "still broken")),
                finalAnswer("done"), finalAnswer("done"));
        AgentRunResult result = agent(workspace, alwaysFailingVerifier("HanoiTowerSolver.cpp"), script)
                .runWithTrajectory("repair file");
        assertFalse(result.trajectory().completed());
        assertEquals(2, result.trajectory().steps().stream().filter(step -> step.actionType()
                == AgentActionType.VERIFICATION_REPAIR && "REPAIR_REQUIRED".equals(step.arguments().get("event"))).count());
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("Repeated verification failure")));
    }

    @Test
    void repairOfOneFailedFileDoesNotClearAnotherFileFailure() throws Exception {
        Files.writeString(workspace.resolve("A.py"), "value = 1\n");
        Files.writeString(workspace.resolve("B.py"), "value = 1\n");
        Script script = new Script(
                call("apply_patch", Map.of("path", "A.py", "oldText", "1", "newText", "broken")),
                call("apply_patch", Map.of("path", "B.py", "oldText", "1", "newText", "broken")),
                finalAnswer("done"),
                call("read_file", Map.of("path", "A.py")),
                call("apply_patch", Map.of("path", "A.py", "oldText", "broken", "newText", "2")),
                finalAnswer("done"),
                call("read_file", Map.of("path", "B.py")),
                call("apply_patch", Map.of("path", "B.py", "oldText", "broken", "newText", "2")),
                finalAnswer("done"));
        AgentRunResult result = agent(workspace, verifierByContent(), script).runWithTrajectory("repair two files");
        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason() + " "
                + result.trajectory().steps().stream().map(step -> step.actionType() + ":" + step.toolName()
                        + ":" + (step.toolResult() == null ? "" : step.toolResult().errorCode())
                        + ":" + step.arguments().get("event")).toList());
        assertEquals(List.of(VerificationStatus.FAIL, VerificationStatus.FAIL, VerificationStatus.PASS,
                VerificationStatus.PASS), statuses(result));
    }

    @Test
    void unavailableVerifierIsRecordedButDoesNotBlockCompletion() throws Exception {
        Files.writeString(workspace.resolve("tool.py"), "print('ok')\n");
        Script script = new Script(
                call("read_file", Map.of("path", "tool.py")),
                call("apply_patch", Map.of("path", "tool.py", "oldText", "ok", "newText", "fine")),
                finalAnswer("Tests passed."),
                finalAnswer("Tests passed."));
        Agent agent = agent(workspace, unavailableVerifier(".py"), script);

        AgentRunResult result = agent.runWithTrajectory("Update tool.py");

        assertTrue(result.trajectory().completed());
        assertEquals(List.of(VerificationStatus.UNAVAILABLE), statuses(result));
        assertFalse(result.finalAnswer().contains("Tests passed"));
        assertTrue(result.finalAnswer().contains("UNAVAILABLE"));
        assertEquals(0, result.trajectory().steps().stream().filter(step -> step.actionType()
                == AgentActionType.VERIFICATION_REPAIR).count());
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("UNAVAILABLE")
                && messages.contains("not installed")));
    }

    @Test
    void MavenWorkspaceUsesExistingProjectVerificationWithoutSecondCompilerPass() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
        Path app = workspace.resolve("src/main/java/App.java");
        Files.createDirectories(app.getParent());
        Files.writeString(app, "class App { String value = \"old\"; }\n");
        List<List<String>> commands = new ArrayList<>();
        ProcessRunner fakeProcess = (command, cwd, timeout, outputLimit) -> {
            commands.add(command);
            return new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
        };
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, fakeProcess, workspace.resolve(".m2"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools);
        Script script = new Script(
                call("apply_patch", Map.of("path", "src/main/java/App.java", "oldText", "old", "newText", "new")),
                call("run_maven_test", Map.of()),
                finalAnswer("Maven tests passed."),
                finalAnswer("Maven tests passed."));
        Agent agent = new Agent(script, environment, "test", 6, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);

        AgentRunResult result = agent.runWithTrajectory("Update App.java");

        assertTrue(result.trajectory().completed());
        assertEquals(0, commands.size(), "headless test process must fail closed before Maven starts");
        assertEquals("MAVEN", environment.verificationCapability("App.java").workspaceKind().name());
        assertEquals("maven", environment.verificationCapability("App.java").recommendedVerifier());
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "NOT_APPLICABLE".equals(step.arguments().get("status"))
                        && "java-maven-project".equals(step.arguments().get("verifier"))));
        assertEquals(1, result.trajectory().steps().stream().filter(step ->
                "run_maven_test".equals(step.toolName())).count());
        assertEquals(ToolErrorCode.APPROVAL_UNAVAILABLE, result.trajectory().steps().stream()
                .filter(step -> "run_maven_test".equals(step.toolName()))
                .findFirst().orElseThrow().toolResult().errorCode());
        assertFalse(result.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("tests passed"));
        assertTrue(result.finalAnswer().contains("APPROVAL_UNAVAILABLE"));
    }

    @Test
    void experimentalReplaceLinesMutationAlsoTriggersRereadAndVerification() throws Exception {
        Files.writeString(workspace.resolve("sample.py"), "value = 1\n");
        ToolRegistry tools = ToolRegistry.withPreciseEditCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools,
                new VerifierRegistry(List.of(alwaysPassingVerifier(".py"))));
        Script script = new Script(
                call("replace_lines", Map.of("path", "sample.py", "startLine", 1, "endLine", 1,
                        "expectedText", "value = 1", "newText", "value = 2")),
                finalAnswer("Updated."));
        Agent agent = new Agent(script, environment, "test", 4, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);

        AgentRunResult result = agent.runWithTrajectory("Update sample.py");

        assertTrue(result.trajectory().completed());
        assertEquals("value = 2\n", Files.readString(workspace.resolve("sample.py")));
        assertEquals(1, result.trajectory().steps().stream().filter(step ->
                step.actionType() == AgentActionType.AUTO_REREAD).count());
        assertEquals(List.of(VerificationStatus.PASS), statuses(result));
        assertEquals(0, result.trajectory().steps().stream().filter(step -> step.actionType()
                == AgentActionType.VERIFICATION_REPAIR).count());
    }

    @Test
    void standaloneJavaUsesJavacVerifierInsteadOfMavenTool() throws Exception {
        Files.writeString(workspace.resolve("Standalone.java"),
                "class Standalone { String value = \"old\"; }\n");
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools,
                new VerifierRegistry(List.of(alwaysPassingVerifier(".java"))));
        Script script = new Script(
                call("apply_patch", Map.of("path", "Standalone.java", "oldText", "old", "newText", "new")),
                finalAnswer("Updated and syntax checked."));
        Agent agent = new Agent(script, environment, "test", 4, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);

        AgentRunResult result = agent.runWithTrajectory("Update Standalone.java");

        assertTrue(result.trajectory().completed());
        assertEquals(0, result.trajectory().steps().stream().filter(step ->
                "run_maven_test".equals(step.toolName())).count());
        assertEquals("PASS", result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION)
                .findFirst().orElseThrow().arguments().get("status"));
        assertEquals("STANDALONE", environment.verificationCapability("Standalone.java").workspaceKind().name());
        assertEquals("javac", environment.verificationCapability("Standalone.java").recommendedVerifier());
        assertTrue(script.observedMessages.stream().anyMatch(messages -> messages.contains("workspace_kind=STANDALONE")
                && messages.contains("project verifier=javac") && messages.contains("Maven project not detected")));
    }

    @Test
    void readOnlyCompilationRequestRunsJavacAndCanCompleteWithoutMutationWhenAlreadyValid() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.writeString(workspace.resolve("HelloWorld.java"),
                "public class HelloWorld { public static void main(String[] args) { System.out.println(\"Hello World\"); } }\n");
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools,
                BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
        Script script = new Script(
                call("read_file", Map.of("path", "HelloWorld.java")),
                finalAnswer("javac compiled HelloWorld.java successfully."));

        AgentRunResult result = new Agent(script, environment, "test", 5, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false).runWithTrajectory(
                "检查 HelloWorld.java 是否有编译错误");

        assertTrue(result.trajectory().completed());
        assertEquals("public class HelloWorld { public static void main(String[] args) { System.out.println(\"Hello World\"); } }\n",
                Files.readString(workspace.resolve("HelloWorld.java")));
        assertEquals(0, result.trajectory().steps().stream()
                .filter(step -> step.toolName() != null && step.toolName().equals("apply_patch")).count());
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && Boolean.TRUE.equals(step.arguments().get("requested"))
                        && "PASS".equals(step.arguments().get("status"))
                        && "java-javac".equals(step.arguments().get("verifier"))));
    }

    @Test
    void failedRequestedCompilationFeedsJavacDiagnosticThenRepairsAndReverifies() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.writeString(workspace.resolve("HelloWorld.java"),
                "public class HelloWorld { public static void main(String[] args) { system.out.println(\"Hello World\"); } }\n");
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools,
                BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
        Script script = new Script(
                call("read_file", Map.of("path", "HelloWorld.java")),
                finalAnswer("There are no compilation errors."),
                call("apply_patch", Map.of("path", "HelloWorld.java",
                        "oldText", "system.out.println", "newText", "System.out.println")),
                finalAnswer("javac compilation passed after repair."));

        AgentRunResult result = new Agent(script, environment, "test", 8, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false).runWithTrajectory(
                "检查 HelloWorld.java 是否有编译错误。如果存在，请自动修复并重新读取确认。");

        assertTrue(result.trajectory().completed(), result.trajectory().terminationReason().name());
        assertTrue(Files.readString(workspace.resolve("HelloWorld.java")).contains("System.out.println"));
        List<AgentStep> requested = result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && Boolean.TRUE.equals(step.arguments().get("requested"))).toList();
        assertEquals(List.of("FAIL", "PASS"), requested.stream()
                .map(step -> step.arguments().get("status")).toList());
        assertTrue(requested.get(0).errorMessage().contains("system.out.println"));
        assertTrue(script.observedMessages.stream().anyMatch(messages ->
                messages.contains("REQUESTED_COMPILATION_FAILED") && messages.contains("system")));
    }

    @Test
    void unavailableRequestedCompilerCannotBeReportedAsPass() throws Exception {
        Files.writeString(workspace.resolve("HelloWorld.java"), "class HelloWorld {}\n");
        ToolRegistry tools = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        CodeVerifier unavailable = new CodeVerifier() {
            @Override public String id() { return "java-javac"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(".java"); }
            @Override public VerificationResult verify(Path file, Path root, long sequence) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, root.relativize(file), id(), "",
                        "javac is unavailable", sequence);
            }
        };
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(workspace, tools,
                new VerifierRegistry(List.of(unavailable)));
        Script script = new Script(call("read_file", Map.of("path", "HelloWorld.java")),
                finalAnswer("Compilation passed; there are no errors."));

        AgentRunResult result = new Agent(script, environment, "test", 4, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false).runWithTrajectory(
                "检查 HelloWorld.java 是否有编译错误");

        assertTrue(result.trajectory().completed());
        assertTrue(result.finalAnswer().contains("could not verify compilation"));
        assertTrue(result.finalAnswer().contains("not a compilation PASS"));
        assertTrue(result.trajectory().steps().stream().anyMatch(step ->
                step.actionType() == AgentActionType.POST_EDIT_VERIFICATION
                        && "UNAVAILABLE".equals(step.arguments().get("status"))));
    }

    private static Agent agent(Path root, CodeVerifier verifier, Script script) {
        ToolRegistry tools = ToolRegistry.withCliCodingTools(root, new DefaultProcessRunner(), root.resolve(".m2"));
        LocalWorkspaceEnvironment environment = new LocalWorkspaceEnvironment(root, tools,
                new VerifierRegistry(List.of(verifier)));
        return new Agent(script, environment, "test", 10, TaskMode.CODE_MODIFICATION,
                true, false, AgentEventListener.NO_OP, false);
    }

    private static CodeVerifier alwaysFailingVerifier(String name) {
        return verifier(name, (file, seq) -> new VerificationResult(VerificationStatus.FAIL,
                Path.of(name), "fake-cpp", "expected ')' before token (synthetic syntax diagnostic)", "", seq));
    }

    private static CodeVerifier alwaysPassingVerifier(String extension) {
        return new CodeVerifier() {
            @Override public String id() { return extension.equals(".java") ? "java-javac" : "fake-pass"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(extension); }
            @Override public VerificationResult verify(Path file, Path root, long seq) {
                return new VerificationResult(VerificationStatus.PASS, root.relativize(file), id(), "", "", seq);
            }
        };
    }

    private static CodeVerifier contentVerifier(String name, String validFragment) {
        return verifier(name, (file, seq) -> {
            boolean valid = read(file).contains(validFragment);
            return new VerificationResult(valid ? VerificationStatus.PASS : VerificationStatus.FAIL,
                    Path.of(name), "fake-cpp", valid ? "" : "synthetic syntax diagnostic", "", seq);
        });
    }

    private static CodeVerifier verifierByContent() {
        return new CodeVerifier() {
            @Override public String id() { return "fake-python"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(".py"); }
            @Override public VerificationResult verify(Path file, Path root, long seq) {
                boolean valid = !read(file).contains("broken");
                return new VerificationResult(valid ? VerificationStatus.PASS : VerificationStatus.FAIL,
                        root.relativize(file), id(), valid ? "" : "syntax error", "", seq);
            }
        };
    }

    private static CodeVerifier unavailableVerifier(String extension) {
        return new CodeVerifier() {
            @Override public String id() { return "missing-python"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(extension); }
            @Override public VerificationResult verify(Path file, Path root, long seq) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, root.relativize(file), id(), "",
                        "not installed", seq);
            }
        };
    }

    private static CodeVerifier verifier(String name, VerificationFunction function) {
        return new CodeVerifier() {
            @Override public String id() { return "fake"; }
            @Override public boolean supports(Path file, Path root) { return file.getFileName().toString().equals(name); }
            @Override public VerificationResult verify(Path file, Path root, long seq) {
                return function.verify(file, seq);
            }
        };
    }

    @FunctionalInterface private interface VerificationFunction {
        VerificationResult verify(Path file, long sequence);
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static int count(AgentRunResult result, AgentActionType type, VerificationStatus status) {
        return (int) result.trajectory().steps().stream().filter(step -> step.actionType() == type
                && status.name().equals(step.arguments().get("status"))).count();
    }

    private static List<VerificationStatus> statuses(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(step -> step.actionType() == AgentActionType.POST_EDIT_VERIFICATION)
                .map(step -> VerificationStatus.valueOf((String) step.arguments().get("status")))
                .toList();
    }

    private static LLMResponse call(String name, Map<String, Object> args) {
        try {
            return new LLMResponse("", List.of(new ToolCall(name + "-id", name,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args))));
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static LLMResponse finalAnswer(String text) { return new LLMResponse(text, List.of()); }

    private static final class Script implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<String> observedMessages = new ArrayList<>();
        private Script(LLMResponse... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
        @Override public LLMResponse chat(List<Message> messages) {
            observedMessages.add(messages.stream().map(Message::content).reduce("", (a, b) -> a + "\n" + b));
            if (responses.isEmpty()) throw new AssertionError("Fake LLM response script exhausted");
            return responses.removeFirst();
        }
    }
}
