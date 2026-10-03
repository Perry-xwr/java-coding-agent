package com.agent.benchmark.editreliability;

import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.environment.verification.CodeVerifier;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.environment.verification.VerifierRegistry;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditReliabilityBenchmarkProtocolTest {
    private static final Path ROOT = Path.of("benchmark/edit-reliability-v1");
    @TempDir Path temporary;

    @Test
    void manifestHasTwelveUniqueDevTasksFourPerLanguageAndCategoryWithEqualCaps() throws Exception {
        EditReliabilityManifest manifest = manifest();
        assertEquals(12, manifest.tasks().size());
        assertEquals(12, manifest.providerRequestCap());
        assertEquals(12, manifest.tasks().stream().map(EditReliabilityTask::id).distinct().count());
        for (String language : List.of("PYTHON", "JAVASCRIPT", "JAVA")) {
            List<EditReliabilityTask> tasks = manifest.tasks().stream().filter(t -> t.language().equals(language)).toList();
            assertEquals(4, tasks.size(), language);
            assertEquals(Set.of(EditReliabilityTaskCategory.values()),
                    tasks.stream().map(EditReliabilityTask::category).collect(java.util.stream.Collectors.toSet()));
            assertTrue(tasks.stream().allMatch(task -> task.maxProviderRequests() == 12));
        }
    }

    @Test
    void rawBenchmarkRunsAreGitIgnoredAndPairedRunnerHasNoDefaultProvider() throws Exception {
        assertTrue(Files.readString(Path.of(".gitignore")).contains("benchmark-runs/"));
        assertNotNull(EditReliabilityBenchmarkRunner.class.getMethod("runAll",
                EditReliabilityManifest.class, EditReliabilityBenchmarkRunner.ProviderFactory.class,
                Path.class, Path.class, String.class, Path.class));
    }

    @Test
    void allFixturesAreInitiallyValidWithTheirRealLocalVerifier() throws Exception {
        var verifier = BuiltInCodeVerifiers.registry(new DefaultProcessRunner());
        for (EditReliabilityTask task : manifest().tasks()) {
            Path fixture = ROOT.resolve(task.fixture()).normalize();
            assertTrue(fixture.startsWith(ROOT), task.id());
            for (String target : task.expectedFiles().keySet()) {
                var result = new com.agent.environment.verification.PostEditVerificationService(fixture, verifier)
                        .verify(target, 1);
                assertEquals(VerificationStatus.PASS, result.status(), task.id() + ": " + result);
            }
        }
    }

    @Test
    void falseSuccessIsCaughtOnlyByIndependentEvaluatorInRereadOnlyBaseline() throws Exception {
        EditReliabilityTask task = pythonTask("false_success", "def value():\n    return 1\n", "return 1", "return (", "return (");
        ScriptedClient fake = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return ("), done());
        var result = run(task, EditReliabilityMode.REREAD_ONLY, fake);
        assertTrue(result.evaluation().conversationalCompletion());
        assertEquals(VerificationStatus.FAIL, result.evaluation().finalSyntaxStatus());
        assertFalse(result.evaluation().finalSyntaxValid());
        assertTrue(result.evaluation().falseSuccess());
        assertTrue(result.evaluation().syntaxFalseSuccess());
        assertFalse(result.evaluation().infrastructureError());
        assertFalse(result.evaluation().taskSuccess());
        assertEquals(0, result.metrics().verificationFailures());
        assertEquals(0, result.metrics().verificationAttempts());
        assertTrue(fake.observedMessages.stream().flatMap(List::stream)
                .noneMatch(message -> message.content().startsWith("POST_EDIT_VERIFICATION:")));
    }

    @Test
    void postEditFailureFeedsRepairAndIndependentEvaluatorPassesFinalState() throws Exception {
        EditReliabilityTask task = pythonTask("repair", "def value():\n    return 1\n", "return 1", "return (", "return 2");
        ScriptedClient fake = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return ("),
                done(), patch("source.py", "return (", "return 2"), done());
        var result = run(task, EditReliabilityMode.POST_EDIT_VERIFY, fake);
        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures() + " final="
                + Files.readString(result.workspace().resolve("source.py")) + " trajectory="
                + result.agentResult().trajectory().steps());
        assertEquals(1, result.metrics().verificationFailures());
        assertTrue(result.metrics().repairAttempts() >= 1);
        assertTrue(result.metrics().recoveredAfterVerificationFailure());
        List<AgentRunAction> actions = actions(result.agentResult().trajectory());
        int failedVerify = actions.indexOf(new AgentRunAction("POST_EDIT_VERIFICATION", "FAIL"));
        int repairMutation = indexOfAfter(actions, new AgentRunAction("TOOL_CALL", "apply_patch"), failedVerify);
        int finalAnswer = actions.indexOf(new AgentRunAction("FINAL_ANSWER", ""));
        assertTrue(failedVerify >= 0 && repairMutation > failedVerify && finalAnswer > repairMutation, actions.toString());
        assertTrue(fake.observedMessages.stream().flatMap(List::stream)
                .anyMatch(message -> message.content().startsWith("POST_EDIT_VERIFICATION: status=FAIL")));
    }

    @Test
    void initiallyCorrectMutationHasNoAdditionalProviderRequestInVerificationMode() throws Exception {
        EditReliabilityTask task = pythonTask("no_regression", "def value():\n    return 1\n", "return 1", "return 2", "return 2");
        ScriptedClient baseline = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return 2"), done());
        ScriptedClient verified = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return 2"), done());
        var rereadOnly = run(task, EditReliabilityMode.REREAD_ONLY, baseline);
        var postVerify = run(task, EditReliabilityMode.POST_EDIT_VERIFY, verified);
        assertTrue(rereadOnly.evaluation().taskSuccess());
        assertTrue(postVerify.evaluation().taskSuccess());
        assertEquals(rereadOnly.providerRequests(), postVerify.providerRequests());
        assertEquals(3, postVerify.providerRequests());
    }

    @Test
    void unavailableIsNeitherPassNorAnInfiniteRepairLoopAndEvaluatorStillChecks() throws Exception {
        EditReliabilityTask task = pythonTask("unavailable", "def value():\n    return 1\n", "return 1", "return 2", "return 2");
        ScriptedClient fake = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return 2"),
                done(), done());
        CodeVerifier unavailable = new CodeVerifier() {
            @Override public String id() { return "unavailable-test"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(".py"); }
            @Override public VerificationResult verify(Path file, Path root, long seq) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, root.relativize(file), id(), "", "missing", seq);
            }
        };
        var result = new EditReliabilityRuntimeHarness().run(task, EditReliabilityMode.POST_EDIT_VERIFY, fake,
                temporary.resolve("fixtures"), temporary.resolve("runs"), "unavailable-run", temporary.resolve("m2"),
                new VerifierRegistry(List.of(unavailable)));
        assertTrue(result.agentResult().trajectory().completed());
        assertEquals(1, result.metrics().verificationUnavailable());
        assertTrue(result.evaluation().finalSyntaxValid());
        assertEquals(VerificationStatus.PASS, result.evaluation().finalSyntaxStatus());
        assertTrue(result.evaluation().infrastructureError());
    }

    @Test
    void independentEvaluatorDoesNotTrustVerificationTrajectory() throws Exception {
        EditReliabilityTask task = pythonTask("independent", "def value():\n    return 1\n", "return 1", "return 2", "return 2");
        Path workspace = temporary.resolve("independent-eval");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.py"), "def value():\n    return 1\n");
        Files.writeString(workspace.resolve("source.py"), "def value(:\n    return 2\n");
        AgentTrajectory trajectory = new AgentTrajectory("fake", task.instruction(), List.of(), "done",
                TerminationReason.FINAL_ANSWER, true, true, 0, 1);
        var evaluation = new EditReliabilityEvaluator().evaluate(task, workspace, trajectory);
        assertFalse(evaluation.finalSyntaxValid());
        assertEquals(VerificationStatus.FAIL, evaluation.finalSyntaxStatus());
        assertTrue(evaluation.syntaxFalseSuccess());
    }

    @Test
    void independentVerifierUnavailableIsInfrastructureNotSyntaxFalseSuccess() throws Exception {
        EditReliabilityTask task = pythonTask("independent-unavailable", "def value():\n    return 1\n",
                "return 1", "return 2", "return 2");
        Path workspace = temporary.resolve("independent-unavailable-workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.py"), "def value():\n    return 2\n");
        var trajectory = completedMutationTrajectory("source.py");
        CodeVerifier unavailable = new CodeVerifier() {
            @Override public String id() { return "unavailable-evaluator"; }
            @Override public boolean supports(Path file, Path root) { return file.toString().endsWith(".py"); }
            @Override public VerificationResult verify(Path file, Path root, long sequence) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, root.relativize(file), id(), "",
                        "simulated unavailable", sequence);
            }
        };
        var evaluation = new EditReliabilityEvaluator(new VerifierRegistry(List.of(unavailable)))
                .evaluate(task, workspace, trajectory);

        assertTrue(evaluation.conversationalCompletion());
        assertEquals(VerificationStatus.UNAVAILABLE, evaluation.finalSyntaxStatus());
        assertFalse(evaluation.taskSuccess());
        assertFalse(evaluation.syntaxFalseSuccess());
        assertFalse(evaluation.falseSuccess());
        assertTrue(evaluation.infrastructureError());
    }

    @Test
    void runnerStopsAfterThirdConditionLevelVerifierInfrastructureFailure() throws Exception {
        EditReliabilityManifest benchmark = manifest();
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        EditReliabilityBenchmarkRunner runner = new EditReliabilityBenchmarkRunner((task, mode, provider,
                fixtures, runs, runId, maven) -> {
            executed.incrementAndGet();
            var evaluation = new EditReliabilityEvaluator.Evaluation(task.id(), false, true, true,
                    VerificationStatus.UNAVAILABLE, false, false, true,
                    List.of("INDEPENDENT_VERIFIER_UNAVAILABLE:" + task.id()), List.of("python-py-compile"));
            return new EditReliabilityRuntimeHarness.RunResult(task, mode, Path.of("workspace"), null,
                    evaluation, null, 0, null);
        });
        LLMClient unusedProvider = messages -> { throw new AssertionError("provider should not be called"); };

        var result = runner.runRound(benchmark, (task, mode) -> unusedProvider, temporary, temporary,
                "stop-rule", temporary);

        assertEquals(3, executed.get());
        assertEquals(3, result.infrastructureFailureCount());
        assertTrue(result.stoppedAtInfrastructureThreshold());
        assertEquals(3, result.results().size());
        assertEquals(3, result.infrastructureFailures().size());
    }

    @Test
    void eachConditionGetsAnIsolatedWorkspace() throws Exception {
        EditReliabilityTask task = pythonTask("isolation", "def value():\n    return 1\n", "return 1", "return 2", "return 2");
        ScriptedClient first = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return 2"), done());
        ScriptedClient second = new ScriptedClient(read("source.py"), patch("source.py", "return 1", "return 3"), done());
        var baseline = run(task, EditReliabilityMode.REREAD_ONLY, first);
        var verified = run(task, EditReliabilityMode.POST_EDIT_VERIFY, second);
        assertNotEquals(baseline.workspace(), verified.workspace());
        assertTrue(Files.readString(baseline.workspace().resolve("source.py")).contains("return 2"));
        assertTrue(Files.readString(verified.workspace().resolve("source.py")).contains("return 3"));
        assertTrue(Files.readString(baseline.workspace().resolve("source.py")).contains("return 2"));
    }

    @Test
    void twoFileConditionBlocksFinalUntilBothCurrentFilesPass() throws Exception {
        Path fixture = temporary.resolve("fixtures").resolve("multi-fixture");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("a.py"), "def a():\n    return 1\n");
        Files.writeString(fixture.resolve("b.py"), "def b():\n    return 1\n");
        EditReliabilityTask task = new EditReliabilityTask("multi", "PYTHON", EditReliabilityTaskCategory.TWO_STEP_EDIT,
                "multi-fixture", "Update both functions to return two.",
                Map.of("a.py", List.of("return 2"), "b.py", List.of("return 2")), 12);
        ScriptedClient baselineScript = new ScriptedClient(read("a.py"), patch("a.py", "return 1", "return 2"),
                read("b.py"), patch("b.py", "return 1", "return 2("), done());
        var baseline = run(task, EditReliabilityMode.REREAD_ONLY, baselineScript);
        assertTrue(baseline.evaluation().conversationalCompletion());
        assertTrue(baseline.evaluation().syntaxFalseSuccess());
        assertFalse(baseline.evaluation().taskSuccess());
        ScriptedClient fake = new ScriptedClient(read("a.py"), patch("a.py", "return 1", "return 2"),
                read("b.py"), patch("b.py", "return 1", "return 2("), done(),
                patch("b.py", "return 2(", "return 2"), done());
        var result = run(task, EditReliabilityMode.POST_EDIT_VERIFY, fake);
        assertTrue(result.evaluation().taskSuccess(), result.evaluation().failures() + " final="
                + Files.readString(result.workspace().resolve("b.py")) + " trajectory="
                + result.agentResult().trajectory().steps());
        assertTrue(result.metrics().verificationFailures() >= 1);
        assertTrue(result.metrics().recoveredAfterVerificationFailure());
        List<AgentRunAction> actions = actions(result.agentResult().trajectory());
        int firstFailure = actions.indexOf(new AgentRunAction("POST_EDIT_VERIFICATION", "FAIL"));
        int repair = indexOfAfter(actions, new AgentRunAction("TOOL_CALL", "apply_patch"), firstFailure);
        int finalAnswer = actions.indexOf(new AgentRunAction("FINAL_ANSWER", ""));
        assertTrue(firstFailure >= 0 && repair > firstFailure && finalAnswer > repair, actions.toString());
        assertTrue(Files.readString(result.workspace().resolve("a.py")).contains("return 2"));
        assertTrue(Files.readString(result.workspace().resolve("b.py")).contains("return 2"));
    }

    private EditReliabilityRuntimeHarness.RunResult run(EditReliabilityTask task, EditReliabilityMode mode,
                                                        LLMClient client) throws Exception {
        return new EditReliabilityRuntimeHarness().run(task, mode, client, temporary.resolve("fixtures"),
                temporary.resolve("runs"), "scripted", temporary.resolve("m2"));
    }

    private EditReliabilityTask pythonTask(String id, String initial, String oldValue, String newValue,
                                           String expectedValue) throws IOException {
        Path root = temporary.resolve("fixtures").resolve(id);
        Files.createDirectories(root);
        Files.writeString(root.resolve("source.py"), initial);
        return new EditReliabilityTask(id, "PYTHON", EditReliabilityTaskCategory.MODIFY_EXISTING,
                id, "Replace " + oldValue + " with " + newValue + ".",
                Map.of("source.py", List.of(expectedValue)), 12);
    }

    private static LLMResponse read(String path) { return call("read_file", Map.of("path", path)); }

    private static AgentTrajectory completedMutationTrajectory(String path) {
        var mutation = new com.agent.agent.AgentStep(1, com.agent.agent.AgentActionType.TOOL_CALL,
                "apply_patch", "patch-1", "{}", Map.of("path", path),
                com.agent.tool.ToolResult.success("changed", Map.of("changed", true, "path", path)),
                null, null, 0, 1);
        return new AgentTrajectory("fake", "edit file", List.of(mutation), "done",
                TerminationReason.FINAL_ANSWER, true, true, 0, 1);
    }
    private static LLMResponse patch(String path, String oldText, String newText) {
        return call("apply_patch", Map.of("path", path, "oldText", oldText, "newText", newText));
    }
    private static LLMResponse done() { return new LLMResponse("The requested change is complete.", List.of()); }
    private static LLMResponse call(String name, Map<String, Object> arguments) {
        try {
            return new LLMResponse("", List.of(new ToolCall("id-" + name + System.nanoTime(), name,
                    new ObjectMapper().writeValueAsString(arguments))));
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static EditReliabilityManifest manifest() throws IOException { return EditReliabilityManifest.load(ROOT.resolve("manifest.json")); }

    private static List<AgentRunAction> actions(AgentTrajectory trajectory) {
        List<AgentRunAction> result = new ArrayList<>();
        trajectory.steps().forEach(step -> {
            String value = step.actionType().name().equals("POST_EDIT_VERIFICATION")
                    ? String.valueOf(step.arguments().get("status"))
                    : step.actionType().name().equals("TOOL_CALL") ? step.toolName()
                    : step.actionType().name().equals("FINAL_ANSWER") ? "" : null;
            if (value != null) result.add(new AgentRunAction(step.actionType().name(), value));
        });
        return result;
    }

    private static int indexOfAfter(List<AgentRunAction> actions, AgentRunAction expected, int after) {
        for (int index = Math.max(0, after + 1); index < actions.size(); index++) {
            if (actions.get(index).equals(expected)) return index;
        }
        return -1;
    }

    private record AgentRunAction(String type, String value) { }

    private static final class ScriptedClient implements LLMClient {
        private final Deque<LLMResponse> responses;
        private final List<List<Message>> observedMessages = new ArrayList<>();
        private ScriptedClient(LLMResponse... values) { responses = new ArrayDeque<>(List.of(values)); }
        @Override public LLMResponse chat(List<Message> messages) {
            observedMessages.add(List.copyOf(messages));
            if (responses.isEmpty()) throw new AssertionError("script exhausted");
            return responses.removeFirst();
        }
    }
}
