package com.agent.benchmark.toolavailability;

import com.agent.agent.PlanningMode;
import com.agent.agent.VerificationRepairPolicy;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolResult;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAvailabilityBenchmarkProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROTOCOL = Path.of("benchmark/tool-availability-v1");
    private final ToolAvailabilityTaskLoader loader = new ToolAvailabilityTaskLoader();

    @TempDir Path temporary;

    @Test
    void manifestHasTwelveUniqueDevTasksWithBalancedClassesAndCategories() throws Exception {
        List<ToolAvailabilityTask> tasks = tasks();
        assertEquals(12, tasks.size());
        assertEquals(12, tasks.stream().map(ToolAvailabilityTask::id).distinct().count());
        assertEquals(12, tasks.stream().map(ToolAvailabilityTask::fixture).distinct().count());
        assertEquals(4, tasks.stream().filter(t -> t.workspaceClass() == ToolAvailabilityWorkspaceClass.STANDALONE_JAVA).count());
        assertEquals(4, tasks.stream().filter(t -> t.workspaceClass() == ToolAvailabilityWorkspaceClass.MAVEN_JAVA).count());
        assertEquals(2, tasks.stream().filter(t -> t.workspaceClass() == ToolAvailabilityWorkspaceClass.PYTHON).count());
        assertEquals(2, tasks.stream().filter(t -> t.workspaceClass() == ToolAvailabilityWorkspaceClass.JAVASCRIPT).count());
        assertTrue(tasks.stream().allMatch(t -> t.maxProviderRequests() == 12 && t.maxSteps() == 10));
    }

    @Test
    void allInitialFixturesPassTheDeterministicGateSeam() throws Exception {
        ToolAvailabilityInfrastructureGate gate = new ToolAvailabilityInfrastructureGate(successRunner());
        var result = gate.check(tasks(), PROTOCOL.resolve("fixtures"), temporary.resolve("m2/repository"));
        assertTrue(result.ready(), result.checks().toString());
        assertEquals(12, result.checks().size());
    }

    @Test
    void legacyStandaloneMismatchIsAdvertisedAndActuallyExecutesMavenTool() throws Exception {
        AtomicInteger mavenRuns = new AtomicInteger();
        ProcessRunner processRunner = (command, cwd, timeout, limit) -> {
            if (isMaven(command)) {
                mavenRuns.incrementAndGet();
                return new ProcessExecutionResult(1, false,
                        "MissingProjectException: The goal requires a project but there is no POM in this directory.",
                        false, 1);
            }
            return pass();
        };
        var result = runSimple(ToolAvailabilityMode.LEGACY_ALL_TOOLS, processRunner,
                "legacy-mismatch", simpleFlow(true));
        assertTrue(result.agentResult().trajectory().completed());
        assertTrue(result.agentResult().trajectory().steps().stream().anyMatch(step ->
                step.toolResult() != null && !step.toolResult().success()
                        && step.toolResult().errorMessage().contains("Maven tests failed")));
        assertTrue(result.agentResult().trajectory().steps().stream().anyMatch(step ->
                step.toolName() != null && step.toolName().equals("run_maven_test")
                        && step.toolResult() != null
                        && step.toolResult().errorCode() == ToolErrorCode.TEST_FAILED));
        assertEquals(1, mavenRuns.get());
        assertEquals(1, result.metrics().environmentMismatchAttempts());
        assertEquals(1, result.metrics().environmentMismatchExecutions());
        assertEquals(0, result.metrics().environmentMismatchRejections());
        assertEquals(1, result.metrics().missingProjectFailures());
        assertTrue(result.metrics().mavenToolAdvertisedTurns() > 0);
    }

    @Test
    void awareStandaloneHidesMavenAndRejectsForgedStaleCallWithoutExecution() throws Exception {
        AtomicInteger mavenRuns = new AtomicInteger();
        ProcessRunner processRunner = (command, cwd, timeout, limit) -> {
            if (isMaven(command)) mavenRuns.incrementAndGet();
            return pass();
        };
        var result = runSimple(ToolAvailabilityMode.ENVIRONMENT_AWARE, processRunner,
                "aware-stale", simpleFlow(true));
        assertTrue(result.agentResult().trajectory().completed());
        assertEquals(0, mavenRuns.get());
        assertTrue(result.metrics().environmentMismatchAttempts() == 1);
        assertEquals(0, result.metrics().environmentMismatchExecutions());
        assertEquals(1, result.metrics().environmentMismatchRejections());
        assertEquals(0, result.metrics().runMavenTestExecutions());
        assertEquals(0, result.metrics().mavenToolAdvertisedTurns());
        assertTrue(result.agentResult().trajectory().steps().stream().anyMatch(step -> step.toolResult() != null
                && step.toolResult().errorCode() == ToolErrorCode.TOOL_UNAVAILABLE_IN_ENVIRONMENT));
    }

    @Test
    void mavenProjectAllowsNormalMavenUseInBothConditions() throws Exception {
        for (ToolAvailabilityMode mode : ToolAvailabilityMode.values()) {
            var responses = List.of(read("src/main/java/demo/Score.java"),
                    patch("src/main/java/demo/Score.java", "return score - bonus;", "return score + bonus;"),
                    call("run_maven_test", Map.of()), finalAnswer());
            var result = run(ToolAvailabilityWorkspaceClass.MAVEN_JAVA, "ta_maven_simple", mode,
                    "maven-" + mode.name(), responses, successRunner());
            assertTrue(result.agentResult().trajectory().completed(), mode.toString());
            assertTrue(result.metrics().initialSafeRootPom());
            assertTrue(result.metrics().mavenToolAdvertisedTurns() > 0);
            assertEquals(1, result.metrics().appropriateMavenAttempts());
            assertEquals(0, result.metrics().environmentMismatchAttempts());
            assertEquals(1, result.metrics().runMavenTestExecutions());
        }
    }

    @Test
    void capabilityPromotesAfterPomCreationAndEarlierMismatchRemainsHistorical() throws Exception {
        List<LLMResponse> script = List.of(read("Calculator.java"), call("run_maven_test", Map.of()),
                call("create_file", Map.of("path", "pom.xml", "content", "<project/>\n")),
                call("run_maven_test", Map.of()), finalAnswer());
        var result = run(ToolAvailabilityWorkspaceClass.STANDALONE_JAVA, "ta_java_simple",
                ToolAvailabilityMode.ENVIRONMENT_AWARE, "promotion-history", script, successRunner());
        assertFalse(result.metrics().initialSafeRootPom());
        assertTrue(result.metrics().finalSafeRootPom());
        assertTrue(result.metrics().pomCreatedDuringRun());
        assertEquals(1, result.metrics().mavenAvailabilityTransitionCount());
        assertEquals(1, result.metrics().environmentMismatchAttempts());
        assertEquals(1, result.metrics().environmentMismatchRejections());
        assertEquals(0, result.metrics().environmentMismatchExecutions());
        assertEquals(1, result.metrics().appropriateMavenAttempts());
        assertEquals(2, result.metrics().runMavenTestAttempts());
        assertTrue(result.metrics().invocationTimeSnapshots().stream()
                .anyMatch(snapshot -> snapshot.event().equals("PROVIDER_ADVERTISEMENT")
                        && snapshot.safeRootPomPresent() && snapshot.runMavenTestAvailable()));
        assertTrue(result.metrics().targetDrift());
    }

    @Test
    void planningModesAndGuidedRepairKeepSameEnvironmentAvailabilityBoundary() throws Exception {
        for (PlanningMode planning : PlanningMode.values()) {
            List<LLMResponse> flow = new ArrayList<>();
            if (planning == PlanningMode.PLAN_EXECUTE) {
                flow.add(new LLMResponse("{\"goal\":\"Fix the addition\",\"steps\":[{\"id\":\"S1\",\"description\":\"Update Calculator.add\"}]}", List.of()));
            }
            flow.add(read("Calculator.java"));
            flow.add(patch("Calculator.java", "return a - b;", "return a + b;"));
            flow.add(finalAnswer());
            var result = run(ToolAvailabilityWorkspaceClass.STANDALONE_JAVA, "ta_java_simple",
                    ToolAvailabilityMode.ENVIRONMENT_AWARE, "planning-" + planning, flow, successRunner(), planning);
            assertTrue(result.agentResult().trajectory().completed(), planning.toString());
            assertEquals(planning == PlanningMode.PLAN_EXECUTE,
                    result.agentResult().trajectory().plan() != null, planning.toString());
            assertEquals(0, result.metrics().mavenToolAdvertisedTurns());
            assertEquals(0, result.metrics().runMavenTestExecutions());
        }
    }

    @Test
    void independentEvaluatorUsesFinalWorkspaceAndFreshSyntaxCheck() throws Exception {
        ToolAvailabilityTask task = tasks().stream().filter(t -> t.id().equals("ta_java_simple")).findFirst().orElseThrow();
        Path fixture = PROTOCOL.resolve("fixtures").resolve(task.fixture()).toAbsolutePath().normalize();
        Path workspace = temporary.resolve("independent-eval");
        Files.createDirectories(workspace);
        com.agent.benchmark.FixtureWorkspaceManager.copyTree(fixture, workspace);
        Files.writeString(workspace.resolve("Calculator.java"),
                "public class Calculator { public int add(int a, int b) { return a + b; } }\n");
        var evaluation = new ToolAvailabilityEvaluator(successRunner()).evaluate(task, workspace, fixture,
                true, temporary.resolve("m2/repository"));
        assertTrue(evaluation.success(), evaluation.failures().toString());
        assertEquals("PASS", evaluation.finalValidationStatus());
    }

    @Test
    void ablationChangesOnlyMavenAdvertisementForStandaloneWorkspaces() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("visibility"));
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, successRunner(),
                temporary.resolve("m2/repository"));
        LocalWorkspaceEnvironment local = new LocalWorkspaceEnvironment(workspace, registry);
        var observations = new ToolAvailabilityObservations();
        ToolAvailabilityEnvironment.MavenProcessCounter counter = () -> 0;
        var legacy = new ToolAvailabilityEnvironment(ToolAvailabilityMode.LEGACY_ALL_TOOLS, local, registry,
                observations, counter);
        var aware = new ToolAvailabilityEnvironment(ToolAvailabilityMode.ENVIRONMENT_AWARE, local, registry,
                observations, counter);
        var legacyNames = new java.util.HashSet<>(legacy.toolDefinitions().stream()
                .map(ToolDefinition::name).toList());
        var awareNames = new java.util.HashSet<>(aware.toolDefinitions().stream()
                .map(ToolDefinition::name).toList());
        assertTrue(legacyNames.contains("run_maven_test"));
        assertFalse(awareNames.contains("run_maven_test"));
        legacyNames.remove("run_maven_test");
        assertEquals(legacyNames, awareNames);
    }

    @Test
    void runnerDoesNotCreateProviderUntilOfflineGatePasses() throws Exception {
        AtomicInteger providers = new AtomicInteger();
        ProcessRunner failingOfflineRunner = (command, cwd, timeout, limit) ->
                new ProcessExecutionResult(1, false, "offline artifact missing", false, 1);
        var runner = new ToolAvailabilityBenchmarkRunner(
                new ToolAvailabilityInfrastructureGate(failingOfflineRunner),
                new ToolAvailabilityRuntimeHarness(successRunner()));
        var summary = runner.run(tasks(), (task, mode) -> {
            providers.incrementAndGet();
            throw new AssertionError("Provider must not be constructed when the fixture gate fails");
        }, PROTOCOL.resolve("fixtures"), temporary.resolve("benchmark-runs/tool-availability-v1"),
                "gate-fails", temporary.resolve("m2/repository"), PlanningMode.REACTIVE,
                VerificationRepairPolicy.GUIDED_REPAIR);
        assertFalse(summary.infrastructureGate().ready());
        assertEquals(0, providers.get());
        assertTrue(summary.results().isEmpty());
    }

    @Test
    void requestCapIsEqualAndInfrastructureStopRuleStopsAtThree() {
        assertEquals(ToolAvailabilityRuntimeHarness.PROVIDER_REQUEST_CAP, 12);
        assertEquals(ToolAvailabilityRuntimeHarness.MAX_AGENT_STEPS, 10);
        ToolAvailabilityInfrastructureStopRule rule = new ToolAvailabilityInfrastructureStopRule();
        assertFalse(rule.observeCondition(false));
        assertFalse(rule.observeCondition(true));
        assertFalse(rule.observeCondition(false));
        assertFalse(rule.observeCondition(true));
        assertTrue(rule.observeCondition(true));
        assertEquals(3, rule.failures());
    }

    @Test
    void rawBenchmarkResultsAreIgnoredByGit() throws Exception {
        String ignore = Files.readString(Path.of(".gitignore"));
        assertTrue(ignore.lines().anyMatch(line -> line.trim().equals("benchmark-runs/")));
    }

    private ToolAvailabilityRuntimeHarness.RunResult runSimple(ToolAvailabilityMode mode,
                                                                 ProcessRunner runner,
                                                                 String runId,
                                                                 List<LLMResponse> script) throws Exception {
        return run(ToolAvailabilityWorkspaceClass.STANDALONE_JAVA, "ta_java_simple", mode, runId, script, runner);
    }

    private ToolAvailabilityRuntimeHarness.RunResult run(ToolAvailabilityWorkspaceClass workspaceClass, String taskId,
                                                          ToolAvailabilityMode mode, String runId,
                                                          List<LLMResponse> script, ProcessRunner runner)
            throws Exception {
        return run(workspaceClass, taskId, mode, runId, script, runner, PlanningMode.REACTIVE);
    }

    private ToolAvailabilityRuntimeHarness.RunResult run(ToolAvailabilityWorkspaceClass workspaceClass, String taskId,
                                                          ToolAvailabilityMode mode, String runId,
                                                          List<LLMResponse> script, ProcessRunner runner,
                                                          PlanningMode planningMode) throws Exception {
        ToolAvailabilityTask task = tasks().stream().filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
        assertEquals(workspaceClass, task.workspaceClass());
        ToolAvailabilityRuntimeHarness harness = new ToolAvailabilityRuntimeHarness(runner);
        return harness.run(task, mode, new ScriptedClient(script), PROTOCOL.resolve("fixtures"),
                temporary.resolve("runs"), runId, temporary.resolve("m2/repository"),
                planningMode, VerificationRepairPolicy.GUIDED_REPAIR);
    }

    private static List<LLMResponse> simpleFlow(boolean includeMaven) throws Exception {
        List<LLMResponse> flow = new ArrayList<>();
        flow.add(read("Calculator.java"));
        if (includeMaven) flow.add(call("run_maven_test", Map.of()));
        flow.add(patch("Calculator.java", "return a - b;", "return a + b;"));
        flow.add(finalAnswer());
        return flow;
    }

    private List<ToolAvailabilityTask> tasks() throws IOException {
        return loader.load(PROTOCOL.resolve("manifest.json"));
    }

    private static LLMResponse read(String path) throws Exception {
        return call("read_file", Map.of("path", path));
    }

    private static LLMResponse patch(String path, String oldText, String newText) throws Exception {
        return call("apply_patch", Map.of("path", path, "oldText", oldText, "newText", newText));
    }

    private static LLMResponse call(String name, Map<String, Object> arguments) throws Exception {
        return new LLMResponse("", List.of(new ToolCall("call-" + name,
                name, JSON.writeValueAsString(arguments))));
    }

    private static LLMResponse finalAnswer() { return new LLMResponse("The requested change is complete.", List.of()); }

    private static ProcessRunner successRunner() {
        return (command, cwd, timeout, limit) -> pass();
    }

    private static ProcessExecutionResult pass() {
        return new ProcessExecutionResult(0, false, "BUILD SUCCESS", false, 1);
    }

    private static boolean isMaven(List<String> command) {
        return !command.isEmpty() && Path.of(command.get(0)).getFileName().toString().toLowerCase().startsWith("mvn");
    }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<ToolDefinition>> advertised = new ArrayList<>();
        private ScriptedClient(List<LLMResponse> responses) { this.responses = new ArrayDeque<>(responses); }
        @Override public LLMResponse chat(List<Message> messages) throws IOException { return chat(messages, List.of()); }
        @Override public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            advertised.add(List.copyOf(tools));
            if (responses.isEmpty()) throw new IOException("script exhausted");
            return responses.removeFirst();
        }
    }

}
