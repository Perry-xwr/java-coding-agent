package com.agent.benchmark.repairreliability;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.CodeVerifier;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolResult;
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

import static org.junit.jupiter.api.Assertions.*;

class RepairReliabilityProtocolTest {
    @TempDir Path temp;
    private static final Path FIXTURES = Path.of("benchmark/repair-reliability-v1/fixtures");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void manifestHasBalancedUniqueTasksAndEqualConditionBudgets() throws Exception {
        RepairReliabilityManifest manifest = new RepairReliabilityManifestLoader()
                .load(Path.of("benchmark/repair-reliability-v1/manifest.json"));
        assertEquals(12, manifest.tasks().size());
        assertEquals(12, manifest.providerRequestCap());
        assertEquals(12, manifest.tasks().stream().map(RepairReliabilityTask::id).distinct().count());
        assertEquals(Map.of("python", 4L, "javascript", 4L, "java", 4L),
                manifest.tasks().stream().collect(java.util.stream.Collectors.groupingBy(
                        RepairReliabilityTask::language, java.util.stream.Collectors.counting())));
        for (String category : List.of("NESTED_BLOCK_EDIT", "FUNCTION_SIGNATURE_EDIT",
                "STRUCTURAL_INSERT", "TWO_STAGE_CHANGE")) {
            assertEquals(3, manifest.tasks().stream().filter(task -> task.category().equals(category)).count());
            for (String language : List.of("python", "javascript", "java")) {
                assertEquals(1, manifest.tasks().stream().filter(task -> task.category().equals(category)
                        && task.language().equals(language)).count(), language + "/" + category);
            }
        }
        assertTrue(manifest.tasks().stream().allMatch(task -> task.maxSteps() == 10
                && task.maxProviderRequests() == 12));
        assertEquals(10, com.agent.agent.Agent.MAX_ITERATIONS);
    }

    @Test
    void allInitialFixturesPassTheirIndependentLanguageVerifier() throws Exception {
        var registry = BuiltInCodeVerifiers.registry(new com.agent.tool.execution.DefaultProcessRunner());
        var service = new com.agent.environment.verification.PostEditVerificationService(FIXTURES, registry);
        RepairReliabilityManifest manifest = new RepairReliabilityManifestLoader()
                .load(Path.of("benchmark/repair-reliability-v1/manifest.json"));
        for (RepairReliabilityTask task : manifest.tasks()) {
            Path fixture = FIXTURES.resolve(task.fixture());
            for (String relative : task.expectedFiles().keySet()) {
                var result = service.verify(Path.of(task.fixture()).resolve(relative).toString(), 0);
                assertEquals(VerificationStatus.PASS, result.status(), task.id() + ": " + result);
            }
        }
    }

    @Test
    void conditionWorkspacesAreIndependentAndNeverReused() throws Exception {
        RepairReliabilityTask task = manifest().tasks().get(0);
        RepairReliabilityWorkspace workspaces = new RepairReliabilityWorkspace();
        Path first = workspaces.create(task, RepairReliabilityMode.VERIFICATION_ONLY,
                FIXTURES, temp.resolve("runs"), "isolation");
        Path second = workspaces.create(task, RepairReliabilityMode.GUIDED_REPAIR,
                FIXTURES, temp.resolve("runs"), "isolation");
        Files.writeString(first.resolve("routes.py"), "changed");
        assertNotEquals(first, second);
        assertNotEquals("changed", Files.readString(second.resolve("routes.py")));
        assertThrows(IOException.class, () -> workspaces.create(task,
                RepairReliabilityMode.VERIFICATION_ONLY, FIXTURES, temp.resolve("runs"), "isolation"));
    }

    @Test
    void evaluatorUsesFinalWorkspaceRatherThanTrajectoryPassEvent() throws Exception {
        Path workspace = temp.resolve("independent");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.py"), "value = syntax_bad\n");
        RepairReliabilityTask task = task("independent", Map.of("source.py", List.of("syntax_bad")));
        AgentTrajectory trajectory = trajectory(List.of(
                mutationStep(1, "source.py"),
                new AgentStep(2, AgentActionType.POST_EDIT_VERIFICATION, "post_edit_verification", null, null,
                        Map.of("file", "source.py", "status", "PASS"), null, null, null, 0, 0),
                finalStep(3)), true, TerminationReason.FINAL_ANSWER);
        var evaluation = new RepairReliabilityEvaluator(sentinelVerifiers())
                .evaluate(task, workspace, trajectory);
        assertEquals(VerificationStatus.FAIL, evaluation.finalSyntaxStatus());
        assertTrue(evaluation.falseSuccess());
        assertFalse(evaluation.taskSuccess());
    }

    @Test
    void verificationOnlyAllowsRepairWithoutAnExplicitModelRereadAfterFailure() throws Exception {
        var result = run("blind", RepairReliabilityMode.VERIFICATION_ONLY, List.of(
                read("source.py"), patch("source.py", "value = 1", "syntax_bad = ("),
                patch("source.py", "syntax_bad = (", "value = 2"), read("source.py"),
                patch("source.py", "syntax_bad = (", "value = 2"), read("source.py"), done()),
                task("blind", Map.of("source.py", List.of("value = 2"))));
        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures().toString());
        assertEquals(1, result.metrics().verificationFailures());
        assertEquals(1, result.metrics().repairAttemptWithoutFreshRead());
        assertEquals(1, result.metrics().successfulRecoveries());
        assertTrue(result.metrics().recoveredRun());
        assertEquals(1, result.metrics().diagnosticHadLocation());
        assertTrue(result.agentResult().trajectory().steps().stream()
                .anyMatch(step -> step.actionType() == AgentActionType.AUTO_REREAD));
        assertFalse(result.agentResult().trajectory().steps().stream()
                .anyMatch(step -> step.toolResult() != null
                        && step.toolResult().errorCode() == com.agent.tool.ToolErrorCode.REPAIR_REQUIRES_FRESH_READ));
    }

    @Test
    void guidedRepairRejectsBlindMutationThenReadsRepairsAndRecovers() throws Exception {
        var result = run("guided", RepairReliabilityMode.GUIDED_REPAIR, List.of(
                read("source.py"), patch("source.py", "value = 1", "syntax_bad = ("),
                patch("source.py", "syntax_bad = (", "value = 2"), read("source.py"),
                patch("source.py", "syntax_bad = (", "value = 2"), read("source.py"), done()),
                task("guided", Map.of("source.py", List.of("value = 2"))));
        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures().toString());
        assertEquals(1, result.metrics().repairGuardRejections());
        assertEquals(1, result.metrics().repairAttemptWithoutFreshRead());
        assertEquals(1, result.metrics().freshReadsAfterFailure());
        assertEquals(1, result.metrics().successfulRecoveries());
        assertTrue(result.agentResult().trajectory().steps().stream()
                .anyMatch(step -> step.toolResult() != null
                        && step.toolResult().errorCode() == com.agent.tool.ToolErrorCode.REPAIR_REQUIRES_FRESH_READ));
    }

    @Test
    void repeatedFailCanRecoverAfterAnotherFreshRead() throws Exception {
        var result = run("repeated", RepairReliabilityMode.GUIDED_REPAIR, List.of(
                read("source.py"), patch("source.py", "value = 1", "syntax_bad_one = ("),
                read("source.py"), patch("source.py", "syntax_bad_one = (", "syntax_bad_two = ("),
                read("source.py"), patch("source.py", "syntax_bad_two = (", "value = 2"),
                read("source.py"), done()), task("repeated", Map.of("source.py", List.of("value = 2"))));
        assertEquals(2, result.metrics().verificationFailures());
        assertEquals(1, result.metrics().repeatedVerificationFailures());
        assertEquals(2, result.metrics().repairMutations());
        assertEquals(1, result.metrics().successfulRecoveries());
        assertTrue(result.evaluation().taskSuccess());
    }

    @Test
    void unresolvedFailureIsNotRecoveryOrTaskSuccess() throws Exception {
        var result = run("unresolved", RepairReliabilityMode.GUIDED_REPAIR, List.of(
                read("source.py"), patch("source.py", "value = 1", "syntax_bad_one = ("),
                read("source.py"), patch("source.py", "syntax_bad_one = (", "syntax_bad_two = ("),
                done(), done()), task("unresolved", Map.of("source.py", List.of("value = 2"))));
        assertEquals(1, result.metrics().unresolvedFailuresAtEnd());
        assertEquals(0, result.metrics().successfulRecoveries());
        assertFalse(result.metrics().recoveredRun());
        assertFalse(result.evaluation().taskSuccess());
    }

    @Test
    void noVerificationFailureIsExcludedAndGuidanceCostsNoExtraProviderTurn() throws Exception {
        List<LLMResponse> flow = List.of(read("source.py"), patch("source.py", "value = 1", "value = 2"),
                read("source.py"), done());
        var baseline = run("no-fail", RepairReliabilityMode.VERIFICATION_ONLY, flow,
                task("no-fail", Map.of("source.py", List.of("value = 2"))));
        var guided = run("no-fail", RepairReliabilityMode.GUIDED_REPAIR, flow,
                task("no-fail", Map.of("source.py", List.of("value = 2"))));
        assertFalse(baseline.metrics().repairEligibleRun());
        assertFalse(guided.metrics().repairEligibleRun());
        assertEquals(0, baseline.metrics().repairMutations());
        assertEquals(0, guided.metrics().repairMutations());
        assertEquals(baseline.providerRequests(), guided.providerRequests());
        assertNotEquals(baseline.workspace(), guided.workspace());
    }

    @Test
    void multipleFailedFilesMustEachRecover() throws Exception {
        var incomplete = run("multi-incomplete", RepairReliabilityMode.GUIDED_REPAIR, List.of(
                calls(read("a.py"), read("b.py")), patch("a.py", "value = 1", "syntax_bad = ("), read("a.py"),
                patch("a.py", "syntax_bad = (", "value = 2"), read("a.py"),
                patch("b.py", "value = 1", "syntax_bad = ("),
                read("b.py"), patch("b.py", "syntax_bad = (", "syntax_bad = ("), done(), done()),
                task("multi-incomplete", Map.of("a.py", List.of("value = 2"), "b.py", List.of("value = 2"))));
        assertEquals(1, incomplete.metrics().successfulRecoveries());
        assertEquals(1, incomplete.metrics().unresolvedFailuresAtEnd());
        assertFalse(incomplete.evaluation().taskSuccess());

        var complete = run("multi-complete", RepairReliabilityMode.GUIDED_REPAIR, List.of(
                calls(read("a.py"), read("b.py")), patch("a.py", "value = 1", "syntax_bad = ("), read("a.py"),
                patch("a.py", "syntax_bad = (", "value = 2"), read("a.py"),
                patch("b.py", "value = 1", "syntax_bad = ("),
                read("b.py"), patch("b.py", "syntax_bad = (", "value = 2"), read("b.py"), done()),
                task("multi-complete", Map.of("a.py", List.of("value = 2"), "b.py", List.of("value = 2"))));
        assertEquals(2, complete.metrics().successfulRecoveries());
        assertTrue(complete.evaluation().taskSuccess(), complete.evaluation().failures().toString());
    }

    @Test
    void unavailableVerifierDoesNotBecomeRepairEligible() throws Exception {
        var unavailable = new VerifierRegistry(List.of(verifier("python", ".py", VerificationStatus.UNAVAILABLE)));
        var response = run("unavailable", RepairReliabilityMode.GUIDED_REPAIR,
                List.of(read("source.py"), patch("source.py", "value = 1", "value = 2"), done(), done()),
                task("unavailable", Map.of("source.py", List.of("value = 2"))), unavailable);
        assertFalse(response.metrics().repairEligibleRun());
        assertEquals(0, response.metrics().repairGuardRejections());
        assertEquals(0, response.metrics().repairMutations());
        assertTrue(response.metrics().infrastructureError());
    }

    @Test
    void standaloneJavaMavenCallIsMeasuredButNotProhibited() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(maven(), done(), done()));
        RepairReliabilityTask task = task("java-mismatch", "java", "standalone/Probe.java",
                Map.of("Probe.java", List.of("class Probe")));
        Path fixtureRoot = temp.resolve("java-fixtures");
        Path source = fixtureRoot.resolve(task.fixture()).resolve("Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Probe {}\n");
        var result = new RepairReliabilityRuntimeHarness(dummyRunner(), javaVerifier()).run(task,
                RepairReliabilityMode.GUIDED_REPAIR, provider, fixtureRoot, temp.resolve("runs"),
                "java-mismatch", temp.resolve("m2/repository"));
        assertEquals(1, result.metrics().toolSelectionMismatch());
        assertTrue(provider.firstRequestMessages().stream().anyMatch(message -> message.content() != null
                && message.content().contains("Java verifier=javac")
                && message.content().contains("Maven project not detected")),
                provider.firstRequestMessages().toString());
    }

    @Test
    void providerTransportFailureIsRecordedAsInfrastructureError() throws Exception {
        var result = run("provider-error", RepairReliabilityMode.GUIDED_REPAIR, List.of(),
                task("provider-error", Map.of("source.py", List.of("value = 2"))));
        assertNotNull(result.providerFailure());
        assertTrue(result.metrics().infrastructureError());
        assertFalse(result.metrics().repairEligibleRun());
    }

    @Test
    void resultDirectoryIsIgnoredAndV18BenchmarkHasNoProtocolChanges() throws Exception {
        String ignore = Files.readString(Path.of(".gitignore"));
        assertTrue(ignore.lines().anyMatch(line -> line.trim().equals("benchmark-runs/")));
        assertFalse(Files.exists(Path.of("benchmark/repair-reliability-v1/results")));
        assertTrue(Files.isDirectory(Path.of("benchmark/edit-reliability-v1")));
    }

    @Test
    void roundRunnerUsesFrozenOrderAndWritesOneRedactedRecordPerCondition() throws Exception {
        RepairReliabilityManifest manifest = manifest();
        List<String> observed = new ArrayList<>();
        RepairReliabilityBenchmarkRunner runner = new RepairReliabilityBenchmarkRunner(
                (task, mode, provider, fixturesRoot, runsRoot, runId, localMavenRepository) -> {
                    observed.add(task.id() + ":" + mode.name());
                    return emptyRunResult(task, mode, runsRoot.resolve(runId).resolve(task.id()).resolve(mode.name()));
                });
        Path output = temp.resolve("round-1");
        var result = runner.runRound(manifest, (task, mode) -> new ScriptedProvider(List.of()), FIXTURES,
                temp.resolve("workspaces"), "round-1", temp.resolve("m2/repository"), output,
                RepairReliabilityRoundOrder.VERIFICATION_THEN_GUIDED);

        assertEquals(24, result.results().size());
        assertEquals(0, result.infrastructureFailures().size());
        assertFalse(result.stoppedAtInfrastructureThreshold());
        assertEquals("py_nested_01:VERIFICATION_ONLY", observed.get(0));
        assertEquals("py_nested_01:GUIDED_REPAIR", observed.get(1));
        assertEquals("py_signature_01:VERIFICATION_ONLY", observed.get(2));
        assertEquals(24, Files.readAllLines(output.resolve("task-results.jsonl")).size());
        try (var trajectories = Files.list(output.resolve("trajectories"))) {
            assertEquals(24, trajectories.count());
        }
        assertTrue(Files.isRegularFile(output.resolve("round-summary.json")));
    }

    @Test
    void roundRunnerStopsOnThirdInfrastructureFailureWithoutRetrying() throws Exception {
        RepairReliabilityManifest manifest = manifest();
        List<String> attempted = new ArrayList<>();
        RepairReliabilityBenchmarkRunner runner = new RepairReliabilityBenchmarkRunner(
                (task, mode, provider, fixturesRoot, runsRoot, runId, localMavenRepository) -> {
                    attempted.add(task.id() + ":" + mode.name());
                    throw new IOException("proxy connection timeout");
                });
        var result = runner.runRound(manifest, (task, mode) -> new ScriptedProvider(List.of()), FIXTURES,
                temp.resolve("workspaces"), "round-stop", temp.resolve("m2/repository"), null,
                RepairReliabilityRoundOrder.VERIFICATION_THEN_GUIDED);

        assertTrue(result.stoppedAtInfrastructureThreshold());
        assertEquals(3, result.infrastructureFailures().size());
        assertEquals(3, attempted.size());
        assertEquals("py_nested_01:VERIFICATION_ONLY", attempted.get(0));
        assertEquals("py_nested_01:GUIDED_REPAIR", attempted.get(1));
        assertEquals("py_signature_01:VERIFICATION_ONLY", attempted.get(2));
        assertTrue(result.infrastructureFailures().stream()
                .allMatch(failure -> failure.category().equals("PROVIDER_TRANSPORT")));
    }

    @Test
    void liveEntrypointRequiresExplicitAuthorizationBeforePreflight() {
        assertThrows(IllegalArgumentException.class, () -> RepairReliabilityBenchmarkMain.main(
                new String[]{"--round=1", "--benchmark-commit=123abcd"}));
    }

    private RepairReliabilityRuntimeHarness.RunResult run(String runId, RepairReliabilityMode mode,
                                                           List<LLMResponse> responses, RepairReliabilityTask task)
            throws Exception {
        return run(runId, mode, responses, task, sentinelVerifiers());
    }
    private RepairReliabilityRuntimeHarness.RunResult run(String runId, RepairReliabilityMode mode,
                                                           List<LLMResponse> responses, RepairReliabilityTask task,
                                                           VerifierRegistry verifiers) throws Exception {
        Path fixtureRoot = temp.resolve("fixtures");
        Path sourceRoot = fixtureRoot.resolve(task.fixture());
        Files.createDirectories(sourceRoot);
        for (String relative : task.expectedFiles().keySet()) {
            Path file = sourceRoot.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, task.language().equals("java") ? "class Probe {}\n" : "value = 1\n");
        }
        return new RepairReliabilityRuntimeHarness(dummyRunner(), verifiers).run(task, mode,
                new ScriptedProvider(responses), fixtureRoot, temp.resolve("runs"), runId,
                temp.resolve("m2/repository"));
    }

    private RepairReliabilityManifest manifest() throws Exception {
        return new RepairReliabilityManifestLoader().load(Path.of("benchmark/repair-reliability-v1/manifest.json"));
    }

    private static RepairReliabilityRuntimeHarness.RunResult emptyRunResult(
            RepairReliabilityTask task, RepairReliabilityMode mode, Path workspace) {
        AgentTrajectory trajectory = new AgentTrajectory(java.util.UUID.randomUUID().toString(), task.id(),
                List.of(), "", TerminationReason.MAX_STEPS, false, null, 0, 0);
        var evaluation = new RepairReliabilityEvaluator.Evaluation(false, false, false,
                VerificationStatus.PASS, false, false, false, List.of("NO_SUCCESSFUL_MUTATION"));
        var metrics = RepairReliabilityMetrics.from(task, mode, trajectory, 0, evaluation, false, false);
        return new RepairReliabilityRuntimeHarness.RunResult(task, mode, workspace,
                new com.agent.agent.AgentRunResult("", trajectory), evaluation, metrics, 0, null);
    }

    private RepairReliabilityTask task(String id, Map<String, List<String>> expected) {
        return task(id, "python", id, expected);
    }
    private RepairReliabilityTask task(String id, String language, String fixture,
                                       Map<String, List<String>> expected) {
        return new RepairReliabilityTask(id, language, "NESTED_BLOCK_EDIT", fixture,
                "Please make the requested bounded change in the listed workspace file.", expected, 10, 12);
    }

    private static LLMResponse read(String path) { return call("read_file", Map.of("path", path)); }
    private static LLMResponse patch(String path, String oldText, String newText) {
        return call("apply_patch", Map.of("path", path, "oldText", oldText, "newText", newText));
    }
    private static LLMResponse maven() { return call("run_maven_test", Map.of()); }
    private static LLMResponse done() { return new LLMResponse("The requested change is complete.", List.of()); }
    private static LLMResponse call(String name, Map<String, Object> args) {
        try { return new LLMResponse("", List.of(new ToolCall("id-" + name, name, JSON.writeValueAsString(args)))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static LLMResponse calls(LLMResponse... responses) {
        List<ToolCall> calls = java.util.Arrays.stream(responses).flatMap(response -> response.toolCalls().stream())
                .toList();
        return new LLMResponse("", calls);
    }
    private static ProcessRunner dummyRunner() {
        return (command, workingDirectory, timeout, maxBytes) -> new ProcessExecutionResult(0, false, "", false, 0);
    }
    private static VerifierRegistry sentinelVerifiers() {
        return new VerifierRegistry(List.of(verifier("python-sentinel", ".py", VerificationStatus.PASS,
                VerificationStatus.FAIL)));
    }
    private static VerifierRegistry javaVerifier() {
        return new VerifierRegistry(List.of(verifier("javac-sentinel", ".java", VerificationStatus.PASS)));
    }
    private static CodeVerifier verifier(String id, String extension, VerificationStatus normal,
                                         VerificationStatus... alternatives) {
        return new CodeVerifier() {
            @Override public String id() { return id; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(extension); }
            @Override public VerificationResult verify(Path file, Path root, long sequence) {
                try {
                    String content = Files.readString(file);
                    VerificationStatus status = content.contains("syntax_bad") && alternatives.length > 0
                            ? alternatives[0] : normal;
                    return new VerificationResult(status, root.relativize(file), id,
                            status == VerificationStatus.FAIL ? "SyntaxError: line 1" : "", "", sequence);
                } catch (IOException exception) {
                    return new VerificationResult(VerificationStatus.UNAVAILABLE, root.relativize(file), id,
                            "", "fixture read failed", sequence);
                }
            }
        };
    }
    private static AgentStep mutationStep(int index, String file) {
        return new AgentStep(index, AgentActionType.TOOL_CALL, "apply_patch", "patch", "{}",
                Map.of("path", file), ToolResult.success("changed", Map.of("changed", true, "path", file)),
                null, null, 0, 0);
    }
    private static AgentStep finalStep(int index) {
        return new AgentStep(index, AgentActionType.FINAL_ANSWER, null, null, null, Map.of(), null,
                "done", null, 0, 0);
    }
    private static AgentTrajectory trajectory(List<AgentStep> steps, boolean completed, TerminationReason reason) {
        return new AgentTrajectory("test", "task", steps, "done", reason, completed, null, 0, 0);
    }

    private static final class ScriptedProvider implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        private ScriptedProvider(List<LLMResponse> responses) { this.responses = new ArrayDeque<>(responses); }
        @Override public LLMResponse chat(List<Message> messages) throws IOException { return next(messages); }
        @Override public LLMResponse chat(List<Message> messages, List<com.agent.llm.ToolDefinition> tools)
                throws IOException { return next(messages); }
        private LLMResponse next(List<Message> messages) throws IOException {
            requests.add(List.copyOf(messages));
            if (responses.isEmpty()) throw new IOException("scripted provider exhausted");
            return responses.removeFirst();
        }
        private List<Message> firstRequestMessages() { return requests.isEmpty() ? List.of() : requests.get(0); }
    }
}
