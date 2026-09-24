package com.agent.tool;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunMavenTestToolTest {
    @TempDir
    Path workspace;

    @Test
    void runsAllTestsWithFixedGoalAndWorkspace() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 20)
        );
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

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
    void runsOneValidatedTestClass() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

        ToolResult result = tool.execute("{\"testClass\":\"com.agent.AgentTest\"}");

        assertTrue(result.success());
        assertEquals("-Dtest=com.agent.AgentTest", runner.command().get(2));
        assertEquals("test", runner.command().get(3));
    }

    @Test
    void runsOneValidatedTestMethod() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

        ToolResult result = tool.execute(
                "{\"testClass\":\"AgentTest\",\"testMethod\":\"respondsToHello\"}"
        );

        assertTrue(result.success());
        assertEquals("-Dtest=AgentTest#respondsToHello", runner.command().get(2));
    }

    @Test
    void rejectsControlCharactersWithoutStartingProcess() {
        FakeProcessRunner runner = successRunner();
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

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
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

        ToolResult result = tool.execute("{}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TEST_FAILED, result.errorCode());
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
        RunMavenTestTool tool = new RunMavenTestTool(
                workspace,
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5))
        );

        ToolResult result = tool.execute("{}");

        assertEquals("COMPILATION", result.metadata().get("diagnosticType"));
        assertTrue(result.metadata().get("diagnosticSummary").toString().contains("cannot find symbol"));
        assertEquals("variable Objects", result.metadata().get("diagnosticSymbol"));
        assertEquals(output, result.output());
    }

    @Test
    void summarizesSyntaxFailure() {
        String output = "[ERROR] C:\\workspace\\RangeSum.java:[3,82] illegal start of expression";
        RunMavenTestTool tool = new RunMavenTestTool(
                workspace,
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5))
        );

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
        RunMavenTestTool tool = new RunMavenTestTool(
                workspace,
                new FakeProcessRunner(new ProcessExecutionResult(1, false, output, false, 5))
        );

        ToolResult result = tool.execute("{}");

        assertEquals("ASSERTION", result.metadata().get("diagnosticType"));
        assertEquals("bench.CalculatorTest", result.metadata().get("diagnosticTestClass"));
        assertEquals("adds", result.metadata().get("diagnosticTestMethod"));
        assertEquals("5", result.metadata().get("diagnosticExpected"));
        assertEquals("4", result.metadata().get("diagnosticActual"));
    }

    @Test
    void returnsTimeoutWithCapturedOutput() {
        FakeProcessRunner runner = new FakeProcessRunner(
                new ProcessExecutionResult(-1, true, "partial", false, 120_000)
        );
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

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
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

        ToolResult result = tool.execute("{}");

        assertTrue(result.success());
        assertEquals(true, result.metadata().get("outputTruncated"));
    }

    @Test
    void classifiesProcessStartFailure() {
        ProcessRunner runner = (command, directory, timeout, maxOutput) -> {
            throw new IOException("maven unavailable");
        };
        RunMavenTestTool tool = new RunMavenTestTool(workspace, runner);

        ToolResult result = tool.execute("{}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.PROCESS_START_FAILED, result.errorCode());
    }

    private static FakeProcessRunner successRunner() {
        return new FakeProcessRunner(
                new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 10)
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
}
