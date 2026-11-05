package com.agent.tool;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.agent.tool.execution.ProcessEnvironmentProfile;
import com.agent.execution.secure.ApprovalDecision;
import com.agent.execution.secure.ExecutionApprovalPrompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RunMavenTestToolTest {
    @TempDir
    Path workspace;

    @Test
    void runsAllTestsWithFixedGoalAndWorkspace() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(0, false, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", false, 20)
        );
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertTrue(result.success());
        assertTrue(runner.command().get(1).startsWith("-Dmaven.repo.local="));
        assertEquals("test", runner.command().get(2));
        assertEquals(workspace.toAbsolutePath().normalize(), runner.workingDirectory());
        assertEquals(Duration.ofSeconds(120), runner.timeout());
        assertEquals(100 * 1024, runner.maxOutputBytes());
        assertEquals(0, result.metadata().get("exitCode"));
    }

    @Test
    void requestsMavenEnvironmentProfileWithoutChangingFixedCommand() {
        ProfileRecordingRunner runner = new ProfileRecordingRunner();
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertTrue(result.success());
        assertEquals(ProcessEnvironmentProfile.MAVEN, runner.profile);
        assertEquals("test", runner.command.get(2));
        assertTrue(runner.command.get(1).startsWith("-Dmaven.repo.local="));
    }

    @Test
    void runsOneValidatedTestClass() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{\"testClass\":\"com.agent.AgentTest\"}");

        assertTrue(result.success());
        assertEquals("-Dtest=com.agent.AgentTest", runner.command().get(2));
        assertEquals("test", runner.command().get(3));
    }

    @Test
    void runsOneValidatedTestMethod() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute(
                "{\"testClass\":\"AgentTest\",\"testMethod\":\"respondsToHello\"}"
        );

        assertTrue(result.success());
        assertEquals("-Dtest=AgentTest#respondsToHello", runner.command().get(2));
    }

    @Test
    void rejectsControlCharactersWithoutStartingProcess() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{\"testClass\":\"AgentTest;whoami\"}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
        assertEquals(0, runner.callCount());
    }

    @Test
    void returnsRecoverableTestFailureWithOutput() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(1, false, "assertion failed", false, 30)
        );
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TEST_SUMMARY_UNAVAILABLE, result.errorCode());
        assertEquals("assertion failed", result.output());
        assertEquals(1, result.metadata().get("exitCode"));
        assertEquals(false, result.metadata().get("timedOut"));
        assertEquals("UNKNOWN", result.metadata().get("diagnosticType"));
        assertEquals("Maven test execution failed", result.metadata().get("diagnosticSummary"));
    }

    @Test
    void summarizesMissingSymbolCompilationFailure() {
        String output = """
                [ERROR] C:\\workspace\\TokenMatcher.java:[2,70] cannot find symbol
                [ERROR]   symbol:   variable Objects
                [ERROR]   location: class bench.TokenMatcher
                """;
        RunMavenTestTool tool = approvedTool(
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5)));

        ToolResult result = tool.execute("{}");

        assertEquals("COMPILATION", result.metadata().get("diagnosticType"));
        assertTrue(result.metadata().get("diagnosticSummary").toString().contains("cannot find symbol"));
        assertEquals("variable Objects", result.metadata().get("diagnosticSymbol"));
        assertEquals(output, result.output());
    }

    @Test
    void summarizesSyntaxFailure() {
        String output = "[ERROR] C:\\workspace\\RangeSum.java:[3,82] illegal start of expression";
        RunMavenTestTool tool = approvedTool(
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5)));

        ToolResult result = tool.execute("{}");

        assertEquals("SYNTAX", result.metadata().get("diagnosticType"));
        assertEquals(3, result.metadata().get("diagnosticLine"));
        assertTrue(result.metadata().get("diagnosticSummary").toString()
                .contains("illegal start of expression"));
    }

    @Test
    void summarizesAssertionFailure() {
        String output = """
                [ERROR] bench.CalculatorTest.adds -- Time elapsed: 0.01 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <5> but was: <4>
                """;
        RunMavenTestTool tool = approvedTool(
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5)));

        ToolResult result = tool.execute("{}");

        assertEquals("ASSERTION", result.metadata().get("diagnosticType"));
        assertEquals("bench.CalculatorTest", result.metadata().get("diagnosticTestClass"));
        assertEquals("adds", result.metadata().get("diagnosticTestMethod"));
        assertEquals("5", result.metadata().get("diagnosticExpected"));
        assertEquals("4", result.metadata().get("diagnosticActual"));
    }

    @Test
    void retainsBoundedFailedTestIdentifiersWhenAvailable() {
        String output = "[ERROR] bench.CalculatorTest.adds -- Time elapsed: 0.01 s <<< FAILURE!\n"
                + "[ERROR] bench.CalculatorTest.subtracts -- Time elapsed: 0.01 s <<< FAILURE!\n"
                + "Tests run: 2, Failures: 2, Errors: 0, Skipped: 0";
        ToolResult result = approvedTool(new FakeProcessRunner(
                new ProcessExecutionResult(1, false, output, false, 1))).execute("{}");

        assertEquals(ToolErrorCode.TEST_FAILED, result.errorCode());
        assertEquals(List.of("bench.CalculatorTest.adds", "bench.CalculatorTest.subtracts"),
                result.metadata().get("failedTests"));
    }

    @Test
    void returnsTimeoutWithCapturedOutput() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(-1, true, "partial", false, 120_000)
        );
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.PROCESS_TIMEOUT, result.errorCode());
        assertEquals("partial", result.output());
        assertEquals(true, result.metadata().get("timedOut"));
    }

    @Test
    void propagatesOutputTruncationMetadata() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(0, false, "limited", true, 15)
        );
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertEquals(ToolErrorCode.TEST_SUMMARY_UNAVAILABLE, result.errorCode());
        assertEquals(true, result.metadata().get("outputTruncated"));
    }

    @Test
    void classifiesProcessStartFailure() {
        ProcessRunner runner = (command, directory, timeout, maxOutput) -> {
            throw new IOException("maven unavailable");
        };
        RunMavenTestTool tool = approvedTool(runner);

        ToolResult result = tool.execute("{}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.PROCESS_START_FAILED, result.errorCode());
        assertEquals(false, result.metadata().get("executed"));
    }

    @Test
    void cancellationIsDistinctFromTimeout() throws Exception {
        ProcessRunner runner = (command, directory, timeout, maxOutput) -> {
            throw new InterruptedException("cancelled");
        };
        ToolResult result = approvedTool(runner).execute("{}");

        assertEquals(ToolErrorCode.PROCESS_CANCELLED, result.errorCode());
        assertEquals("CANCELLED", result.metadata().get("testStatus"));
        assertEquals(true, result.metadata().get("executed"));
        Thread.interrupted(); // clear the interrupt flag for the remainder of this JUnit worker
    }

    @Test
    void deniedUnavailableAndMismatchedApprovalNeverLaunchProcess() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool denied = tool(runner, prompt -> new ApprovalDecision(
                ApprovalDecision.Status.DENIED, prompt.requestDigest(), ""));
        assertEquals(ToolErrorCode.APPROVAL_DENIED, denied.execute("{}").errorCode());
        assertEquals(0, runner.callCount());

        RunMavenTestTool unavailable = tool(runner, prompt -> new ApprovalDecision(
                ApprovalDecision.Status.UNAVAILABLE, prompt.requestDigest(), ""));
        assertEquals(ToolErrorCode.APPROVAL_UNAVAILABLE, unavailable.execute("{}").errorCode());
        assertEquals(0, runner.callCount());

        RunMavenTestTool mismatched = tool(runner, prompt -> new ApprovalDecision(
                ApprovalDecision.Status.APPROVED, "different-request", UUID.randomUUID().toString()));
        assertEquals(ToolErrorCode.APPROVAL_DENIED, mismatched.execute("{}").errorCode());
        assertEquals(0, runner.callCount());
    }

    @Test
    void workspaceChangeAfterApprovalInvalidatesTheApproval() throws Exception {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = tool(runner, prompt -> {
            try {
                java.nio.file.Files.writeString(workspace.resolve("source.txt"), "changed after approval");
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return approve(prompt);
        });

        ToolResult result = tool.execute("{}");

        assertEquals(ToolErrorCode.APPROVAL_DENIED, result.errorCode());
        assertFalse(Boolean.TRUE.equals(result.metadata().get("executed")));
        assertEquals(0, runner.callCount());
    }

    @Test
    void eachInvocationRequiresANewOneTimeApproval() {
        FakeProcessRunner runner = new FakeProcessRunner(new ProcessExecutionResult(0, false,
                "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", false, 1));
        List<ExecutionApprovalPrompt> prompts = new ArrayList<>();
        RunMavenTestTool tool = tool(runner, prompt -> {
            prompts.add(prompt);
            return approve(prompt);
        });

        tool.execute("{}");
        tool.execute("{}");

        assertEquals(2, prompts.size());
        assertNotEquals(prompts.get(0).challenge(), prompts.get(1).challenge());
        assertEquals(2, runner.callCount());
        assertEquals(com.agent.execution.secure.ExecutionRequest.Action.MAVEN_TEST,
                prompts.get(0).action());
        assertTrue(prompts.get(0).workspaceCopy().contains(workspace.toAbsolutePath().normalize().toString()));
        assertTrue(prompts.get(0).riskNotice().contains("current Windows user permissions"));
        assertTrue(prompts.get(0).isolationSummary().contains("no OS sandbox"));
    }

    @Test
    void approvalForOneTestSelectionCannotAuthorizeAnotherSelection() {
        FakeProcessRunner runner = successRunner();
        ApprovalDecision[] first = new ApprovalDecision[1];
        RunMavenTestTool tool = tool(runner, prompt -> {
            if (first[0] == null) {
                first[0] = approve(prompt);
                return first[0];
            }
            return first[0];
        });

        assertTrue(tool.execute("{}").success());
        ToolResult changedSelection = tool.execute("{\"testClass\":\"safe.Test\"}");

        assertEquals(ToolErrorCode.APPROVAL_DENIED, changedSelection.errorCode());
        assertEquals(1, runner.callCount());
    }

    @Test
    void testSummaryControlsPassAndSeparatesSkippedBuildAndAssertionFailure() {
        ToolResult passing = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(0, false,
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 1\nBUILD SUCCESS", true, 1))).execute("{}");
        assertFalse(passing.success());
        assertEquals(ToolErrorCode.TEST_SUMMARY_UNAVAILABLE, passing.errorCode());
        assertEquals(2, passing.metadata().get("testsPassed"));
        assertEquals(true, passing.metadata().get("outputTruncated"));

        ToolResult completeSummary = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(0, false,
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 1\nBUILD SUCCESS", false, 1))).execute("{}");
        assertTrue(completeSummary.success());
        assertEquals("TEST_PASSED", completeSummary.metadata().get("testStatus"));

        ToolResult skipped = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(0, false,
                "Tests run: 2, Failures: 0, Errors: 0, Skipped: 2", false, 1))).execute("{}");
        assertEquals(ToolErrorCode.TESTS_SKIPPED, skipped.errorCode());

        ToolResult assertion = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(1, false,
                "Tests run: 2, Failures: 2, Errors: 0, Skipped: 0\n" +
                        "org.opentest4j.AssertionFailedError: expected: <1> but was: <0>", false, 1))).execute("{}");
        assertEquals(ToolErrorCode.TEST_FAILED, assertion.errorCode());
        assertEquals(2, assertion.metadata().get("testsFailed"));
        assertEquals(1, assertion.metadata().get("exitCode"));

        ToolResult build = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(1, false,
                "[ERROR] COMPILATION ERROR", false, 1))).execute("{}");
        assertEquals(ToolErrorCode.BUILD_FAILED, build.errorCode());
    }

    @Test
    void separatesDependencyConfigurationAndTestRuntimeFailuresFromSourceAssertions() {
        ToolResult dependency = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(1, false,
                "[ERROR] Could not resolve dependencies for project x\nCould not transfer artifact a:b:1",
                false, 1))).execute("{}");
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, dependency.errorCode());
        assertEquals("DEPENDENCY", dependency.metadata().get("diagnosticType"));

        ToolResult configuration = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(1, false,
                "[ERROR] Unknown lifecycle phase \"testt\"", false, 1))).execute("{}");
        assertEquals(ToolErrorCode.BUILD_CONFIGURATION_FAILURE, configuration.errorCode());
        assertEquals("BUILD_CONFIGURATION", configuration.metadata().get("diagnosticType"));

        ToolResult runtime = approvedTool(new FakeProcessRunner(new ProcessExecutionResult(1, false,
                "Tests run: 1, Failures: 0, Errors: 1, Skipped: 0\n"
                        + "[ERROR] sample.ExampleTest.runs -- NullPointerException", false, 1))).execute("{}");
        assertEquals(ToolErrorCode.TEST_RUNTIME_ERROR, runtime.errorCode());
        assertEquals("TEST_RUNTIME_ERROR", runtime.metadata().get("testStatus"));
        assertEquals("TEST_RUNTIME", runtime.metadata().get("diagnosticType"));
        assertEquals(1, runtime.metadata().get("testsErrors"));
    }

    private RunMavenTestTool approvedTool(ProcessRunner runner) {
        return tool(runner, RunMavenTestToolTest::approve);
    }

    private RunMavenTestTool tool(ProcessRunner runner,
                                  java.util.function.Function<ExecutionApprovalPrompt, ApprovalDecision> approver) {
        return new RunMavenTestTool(workspace, runner, workspace.resolve(".m2/repository"),
                new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(120),
                RunMavenTestTool.DEFAULT_MAX_OUTPUT_BYTES, approver);
    }

    private static ApprovalDecision approve(ExecutionApprovalPrompt prompt) {
        return new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(),
                UUID.randomUUID().toString());
    }

    private static FakeProcessRunner successRunner() {
        return new FakeProcessRunner(
                new ProcessExecutionResult(0, false,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", false, 10)
        );
    }

    private static final class FakeProcessRunner implements ProcessRunner {
        private final ProcessExecutionResult result;
        private List<String> command;
        private Path workingDirectory;
        private Duration timeout;
        private int maxOutputBytes;
        private int callCount;

        private FakeProcessRunner(ProcessExecutionResult result) {
            this.result = result;
        }

        @Override
        public ProcessExecutionResult run(
                List<String> command,
                Path workingDirectory,
                Duration timeout,
                int maxOutputBytes
        ) {
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.timeout = timeout;
            this.maxOutputBytes = maxOutputBytes;
            callCount++;
            return result;
        }

        private List<String> command() {
            return command;
        }

        private Path workingDirectory() {
            return workingDirectory;
        }

        private Duration timeout() {
            return timeout;
        }

        private int maxOutputBytes() {
            return maxOutputBytes;
        }

        private int callCount() {
            return callCount;
        }
    }

    private static final class ProfileRecordingRunner implements ProcessRunner {
        private ProcessEnvironmentProfile profile;
        private List<String> command;

        @Override
        public ProcessExecutionResult run(List<String> command, Path workingDirectory,
                                          Duration timeout, int maxOutputBytes) {
            this.command = command;
            return new ProcessExecutionResult(0, false,
                    "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", false, 1);
        }

        @Override
        public ProcessExecutionResult run(List<String> command, Path workingDirectory,
                                          Duration timeout, int maxOutputBytes,
                                          Map<String, String> overrides,
                                          ProcessEnvironmentProfile profile) {
            this.profile = profile;
            return run(command, workingDirectory, timeout, maxOutputBytes);
        }
    }
}
