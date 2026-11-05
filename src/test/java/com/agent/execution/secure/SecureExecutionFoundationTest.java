package com.agent.execution.secure;

import com.agent.agent.CommandExecutionEvidence;
import com.agent.agent.TaskEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecureExecutionFoundationTest {
    private static final String IMAGE = "registry.example/agent-test@sha256:" + "a".repeat(64);

    @TempDir Path temp;

    @Test
    void denialDoesNotCallExecutionBackendAndCleansOnlyOwnedStage() throws Exception {
        Path root = workspace("class App {}\n");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        IsolatedExecutionBackend backend = backend(calls, true,
                new BackendExecutionResult(ExecutionResult.Status.EXITED, true, 0, "tests", false, 10,
                        "container", ExecutionResult.CleanupStatus.SUCCEEDED));
        SecureExecutionCoordinator coordinator = coordinator(backend, prompt -> {
            approvals.incrementAndGet();
            return new ApprovalDecision(ApprovalDecision.Status.DENIED, prompt.requestDigest(), "");
        });

        ExecutionResult result = coordinator.execute(request(root));

        assertEquals(1, approvals.get());
        assertEquals(0, calls.get(), "denial must prevent reaching execution backend");
        assertEquals(ApprovalDecision.Status.DENIED, result.approvalStatus());
        assertEquals(ExecutionResult.Status.DENIED, result.status());
        assertFalse(result.executed());
        assertEquals("class App {}\n", Files.readString(root.resolve("src/App.java")));
        try (var children = Files.list(temp)) {
            assertEquals(0, children.filter(path -> path.getFileName().toString().startsWith("agent-exec-")).count());
        }
    }

    @Test
    void approvalIsBoundToExactDigestAndSourceSnapshotIsRechecked() throws Exception {
        Path root = workspace("class App {}\n");
        AtomicInteger calls = new AtomicInteger();
        IsolatedExecutionBackend backend = backend(calls, true,
                new BackendExecutionResult(ExecutionResult.Status.EXITED, true, 0, "", false, 1,
                        "container", ExecutionResult.CleanupStatus.SUCCEEDED));

        SecureExecutionCoordinator tamperedApproval = coordinator(backend, prompt ->
                new ApprovalDecision(ApprovalDecision.Status.APPROVED, "wrong-digest", "id"));
        ExecutionResult mismatch = tamperedApproval.execute(request(root));
        assertEquals(ExecutionResult.Status.POLICY_REJECTED, mismatch.status());
        assertEquals(0, calls.get());

        SecureExecutionCoordinator sourceChanged = coordinator(backend, prompt -> {
            try { Files.writeString(root.resolve("src/App.java"), "class Changed {}\n"); }
            catch (IOException exception) { throw new RuntimeException(exception); }
            return new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(), "id");
        });
        ExecutionResult stale = sourceChanged.execute(request(root));
        assertEquals(ExecutionResult.Status.POLICY_REJECTED, stale.status());
        assertFalse(stale.executed());
        assertEquals(0, calls.get());
    }

    @Test
    void approvedAndExecutedAreSeparateEvidenceFacts() throws Exception {
        Path root = workspace("class App {}\n");
        AtomicInteger calls = new AtomicInteger();
        IsolatedExecutionBackend backend = backend(calls, true,
                new BackendExecutionResult(ExecutionResult.Status.EXITED, true, 0, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", false,
                        7, "agent-s1a-test", ExecutionResult.CleanupStatus.SUCCEEDED));
        SecureExecutionCoordinator coordinator = coordinator(backend, prompt ->
                new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(), "human-approval"));

        ExecutionResult result = coordinator.execute(request(root));
        CommandExecutionEvidence evidence = CommandExecutionEvidence.from("tool-1", result);
        assertEquals(ApprovalDecision.Status.APPROVED, evidence.approvalStatus());
        assertTrue(evidence.actuallyExecuted());
        assertEquals(0, evidence.exitCode());
        assertEquals(1, calls.get());

        ExecutionResult consentOnly = new ExecutionResult(ApprovalDecision.Status.APPROVED,
                ExecutionResult.Status.APPROVED, false, null, "", false, 0, "digest", "",
                ExecutionResult.CleanupStatus.NOT_REQUIRED);
        CommandExecutionEvidence notRun = CommandExecutionEvidence.from("tool-2", consentOnly);
        assertEquals(ApprovalDecision.Status.APPROVED, notRun.approvalStatus());
        assertFalse(notRun.actuallyExecuted());
        assertNull(notRun.exitCode());

        TaskEvidence empty = new TaskEvidence(TaskEvidence.ExecutionStatus.COMPLETED,
                TaskEvidence.MutationStatus.NOT_MODIFIED, TaskEvidence.EvidenceStatus.NOT_RUN,
                TaskEvidence.VerificationKind.NONE, null, TaskEvidence.EvidenceStatus.NOT_RUN,
                TaskEvidence.CompletionAssessment.EXECUTION_COMPLETED, true, List.of());
        TaskEvidence assessed = empty.withCommandExecutionEvidence(notRun);
        assertEquals(ApprovalDecision.Status.APPROVED,
                assessed.commandExecutionEvidence().get(0).approvalStatus());
        assertFalse(assessed.commandExecutionEvidence().get(0).actuallyExecuted());
        assertTrue(empty.commandExecutionEvidence().isEmpty(), "original evidence remains immutable");
    }

    @Test
    void userUnavailableOrDockerUnavailableFailsClosedBeforeApproval() throws Exception {
        Path root = workspace("class App {}\n");
        AtomicInteger calls = new AtomicInteger();
        IsolatedExecutionBackend unavailable = backend(calls, false, null);
        AtomicInteger approvals = new AtomicInteger();
        SecureExecutionCoordinator coordinator = coordinator(unavailable, prompt -> {
            approvals.incrementAndGet();
            return new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(), "id");
        });
        ExecutionResult result = coordinator.execute(request(root));
        assertEquals(ExecutionResult.Status.UNAVAILABLE, result.status());
        assertEquals(0, approvals.get());
        assertEquals(0, calls.get());

        ApprovalDecision noConsole = new ConsoleExecutionApprover(null).approve(
                new ExecutionApprovalPrompt("digest", "challenge", ExecutionRequest.Action.MAVEN_TEST,
                        "copy", "all tests", IMAGE, "none", "untrusted tests"));
        assertEquals(ApprovalDecision.Status.UNAVAILABLE, noConsole.status());
    }

    @Test
    void stagerRejectsTraversalSensitiveContentAndSymbolicLinks() throws Exception {
        Path root = workspace("class App {}\n");
        ExecutionRequest traversal = new ExecutionRequest(UUID.randomUUID(), "tc", ExecutionRequest.Action.MAVEN_TEST,
                root, List.of("../outside.txt"), null, null, Duration.ofSeconds(20), 1024);
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceStager().stage(traversal, policy()));

        Files.writeString(root.resolve("src/secret.properties"), "api_key=abcdefgh12345678\n");
        ExecutionRequest secret = new ExecutionRequest(UUID.randomUUID(), "tc", ExecutionRequest.Action.MAVEN_TEST,
                root, List.of("src/secret.properties"), null, null, Duration.ofSeconds(20), 1024);
        assertThrows(IOException.class, () -> new WorkspaceStager().stage(secret, policy()));

        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path link = root.resolve("src/linked.txt");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symlink creation is unavailable on this Windows configuration");
        }
        ExecutionRequest symlink = new ExecutionRequest(UUID.randomUUID(), "tc", ExecutionRequest.Action.MAVEN_TEST,
                root, List.of("src/linked.txt"), null, null, Duration.ofSeconds(20), 1024);
        assertThrows(IOException.class, () -> new WorkspaceStager().stage(symlink, policy()));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void requestRejectsCommandAndDockerParameterInjection() throws Exception {
        Path root = workspace("class App {}\n");
        assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest(UUID.randomUUID(), "tc",
                ExecutionRequest.Action.MAVEN_TEST, root, List.of("src/App.java"),
                "AppTest; powershell.exe", null, Duration.ofSeconds(20), 1024));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest(UUID.randomUUID(), "tc",
                ExecutionRequest.Action.MAVEN_TEST, root, List.of("src/App.java"),
                "AppTest", "method && whoami", Duration.ofSeconds(20), 1024));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy("latest", temp,
                "0:0", 99, Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy(IMAGE, temp,
                "10001:10001", Double.NaN, 512L * 1024 * 1024, 64,
                128L * 1024 * 1024, 32L * 1024 * 1024, Duration.ofMinutes(2), 8192,
                32, 1024 * 1024, 4 * 1024 * 1024));
    }

    @Test
    void outputIsRedactedAndBoundedByUtf8Bytes() {
        ExecutionOutputSanitizer.SanitizedOutput sanitized = ExecutionOutputSanitizer.sanitize(
                "API_KEY=abcdefgh12345678\nBearer abcdefghijklmnop\n" + "中".repeat(100), 64);
        assertTrue(sanitized.truncated());
        assertTrue(sanitized.output().contains("[REDACTED]"));
        assertTrue(sanitized.output().contains("[output truncated]"));
        assertTrue(sanitized.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        assertTrue(ExecutionOutputSanitizer.sanitize("long", 1).output().length() <= 1);
    }

    @Test
    void dockerPlanIsPinnedRestrictedAndDoesNotExposeHostMountsOrEnvironment() throws Exception {
        Path root = workspace("class App {}\n");
        ExecutionRequest request = request(root);
        try (StagedWorkspace staged = new WorkspaceStager().stage(request, policy())) {
            DockerInvocationBuilder.InvocationPlan plan = new DockerInvocationBuilder().build(request, policy(), staged);
            List<String> args = plan.createArguments();
            assertTrue(args.contains("--pull=never"));
            assertTrue(args.contains("--network=none"));
            assertTrue(args.contains("--read-only"));
            assertTrue(args.contains("--cap-drop=ALL"));
            assertTrue(args.contains("--security-opt=no-new-privileges:true"));
            assertTrue(args.containsAll(List.of("--user", "10001:10001", "--pids-limit=64")));
            assertTrue(args.stream().anyMatch(value -> value.startsWith("--cpus=")));
            assertTrue(args.stream().anyMatch(value -> value.startsWith("--memory=")));
            assertTrue(args.stream().anyMatch(value -> value.startsWith("--tmpfs")));
            assertTrue(args.contains(IMAGE));
            assertFalse(args.stream().anyMatch(value -> value.equals("--privileged") || value.contains("docker.sock")
                    || value.toLowerCase().contains("deepseek_api_key") || value.toLowerCase().contains("glm_api_key")));
            assertFalse(args.contains("--mount"));
            assertTrue(plan.copyArguments().contains(staged.workspaceCopy().toString() + "/."));
            assertTrue(plan.stopArguments().contains(plan.containerName()));
            assertTrue(plan.removeArguments().contains("--force"));
            assertTrue(plan.containerName().matches("agent-s1a-[0-9a-f]{32}"));
        }
    }

    @Test
    void timeoutStopsAndForceRemovesWholeContainerAndCancellationIsDistinct() throws Exception {
        Path root = workspace("class App {}\n");
        ExecutionRequest request = request(root);
        try (StagedWorkspace staged = new WorkspaceStager().stage(request, policy())) {
            DockerInvocationBuilder.InvocationPlan plan = new DockerInvocationBuilder().build(request, policy(), staged);
            List<List<String>> commands = new ArrayList<>();
            DockerContainerLifecycle lifecycle = new DockerContainerLifecycle((args, timeout, limit, cancel) -> {
                commands.add(args);
                if (args.equals(plan.createArguments()) || args.equals(plan.copyArguments())) return process(0, false);
                if (args.equals(plan.startArguments())) return process(-1, true);
                return process(0, false);
            });
            BackendExecutionResult timedOut = lifecycle.execute(plan, () -> false);
            assertEquals(ExecutionResult.Status.TIMED_OUT, timedOut.status());
            assertTrue(timedOut.executed());
            assertTrue(commands.contains(plan.stopArguments()));
            assertTrue(commands.contains(plan.removeArguments()));
            assertEquals(ExecutionResult.CleanupStatus.SUCCEEDED, timedOut.cleanupStatus());

            AtomicInteger cancelled = new AtomicInteger();
            List<List<String>> cancelCommands = new ArrayList<>();
            DockerContainerLifecycle cancelledLifecycle = new DockerContainerLifecycle((args, timeout, limit, signal) -> {
                cancelCommands.add(args);
                if (args.equals(plan.createArguments()) || args.equals(plan.copyArguments())) return process(0, false);
                if (args.equals(plan.startArguments())) {
                    cancelled.set(1);
                    return process(0, false);
                }
                return process(0, false);
            });
            BackendExecutionResult cancelledResult = cancelledLifecycle.execute(plan, () -> cancelled.get() == 1);
            assertEquals(ExecutionResult.Status.CANCELLED, cancelledResult.status());
            assertTrue(cancelledResult.executed());
            assertTrue(cancelCommands.contains(plan.stopArguments()));
            assertTrue(cancelCommands.contains(plan.removeArguments()));
        }
    }

    @Test
    void dockerEnvironmentInspectorRequiresLinuxDaemonAndExactLocalDigest() {
        DockerEnvironmentInspector.ProbeResult ready = new DockerEnvironmentInspector.ProbeResult(
                true, true, "linux", true, IMAGE);
        DockerEnvironmentCheck check = new DockerEnvironmentInspector(ignored -> ready).inspect(policy());
        assertTrue(check.readyFor(policy()));
        DockerEnvironmentCheck windows = new DockerEnvironmentInspector(ignored ->
                new DockerEnvironmentInspector.ProbeResult(true, true, "windows", true, IMAGE)).inspect(policy());
        assertEquals(DockerEnvironmentCheck.Status.WRONG_CONTAINER_MODE, windows.status());
        DockerEnvironmentCheck absent = new DockerEnvironmentInspector(ignored ->
                new DockerEnvironmentInspector.ProbeResult(true, true, "linux", false, "")).inspect(policy());
        assertEquals(DockerEnvironmentCheck.Status.IMAGE_MISSING, absent.status());
        DockerEnvironmentCheck mismatch = new DockerEnvironmentInspector(ignored ->
                new DockerEnvironmentInspector.ProbeResult(true, true, "linux", true,
                        "registry.example/agent-test@sha256:" + "b".repeat(64))).inspect(policy());
        assertEquals(DockerEnvironmentCheck.Status.IMAGE_DIGEST_MISMATCH, mismatch.status());
    }

    @Test
    void cleanupFailureDoesNotEraseFactThatCommandExecuted() throws Exception {
        Path root = workspace("class App {}\n");
        IsolatedExecutionBackend backend = backend(new AtomicInteger(), true,
                new BackendExecutionResult(ExecutionResult.Status.TIMED_OUT, true, null, "", false, 300,
                        "container", ExecutionResult.CleanupStatus.FAILED));
        ExecutionResult result = coordinator(backend, prompt ->
                new ApprovalDecision(ApprovalDecision.Status.APPROVED, prompt.requestDigest(), "id"))
                .execute(request(root));
        assertEquals(ExecutionResult.Status.CLEANUP_FAILED, result.status());
        assertTrue(result.executed());
        assertEquals(ExecutionResult.CleanupStatus.FAILED, result.cleanupStatus());

        ExecutionResult cancelled = new ExecutionResult(ApprovalDecision.Status.APPROVED,
                ExecutionResult.Status.CANCELLED, true, null, "", false, 4, "digest", "container",
                ExecutionResult.CleanupStatus.SUCCEEDED);
        CommandExecutionEvidence cancellationEvidence = CommandExecutionEvidence.from("tc", cancelled);
        assertTrue(cancellationEvidence.actuallyExecuted());
        assertEquals(ExecutionResult.Status.CANCELLED, cancellationEvidence.executionStatus());
    }

    @Test
    void fakeApproverIsNotPubliclySelectableByNormalRuntimeConfiguration() {
        assertFalse(Modifier.isPublic(ExecutionApprover.class.getModifiers()));
        assertEquals(0, SecureExecutionCoordinator.class.getConstructors().length,
                "the package-private test constructor is not public; production uses the real-console factory");
    }

    @Test
    void stagedWorkspaceIsBoundedAndCleanupPreservesSource() throws Exception {
        Path root = workspace("public class App { }\n");
        ExecutionRequest request = request(root);
        StagedWorkspace staged = new WorkspaceStager().stage(request, policy());
        Path copy = staged.workspaceCopy();
        assertTrue(staged.unchangedSinceApproval());
        assertEquals(Files.readString(root.resolve("src/App.java")), Files.readString(copy.resolve("src/App.java")));
        String digest = staged.snapshotDigest();
        assertEquals(64, digest.length());
        staged.close();
        assertFalse(Files.exists(copy));
        assertEquals("public class App { }\n", Files.readString(root.resolve("src/App.java")));
    }

    private SecureExecutionCoordinator coordinator(IsolatedExecutionBackend backend, ExecutionApprover approver) {
        return new SecureExecutionCoordinator(policy(), approver, backend, new WorkspaceStager());
    }

    private IsolatedExecutionBackend backend(AtomicInteger calls, boolean available, BackendExecutionResult result) {
        return new IsolatedExecutionBackend() {
            @Override public DockerEnvironmentCheck inspect(ExecutionPolicy ignored) {
                return new DockerEnvironmentCheck(available ? DockerEnvironmentCheck.Status.READY
                        : DockerEnvironmentCheck.Status.CLI_UNAVAILABLE, available, available, available,
                        available, available ? IMAGE : "", available ? "ready" : "missing");
            }
            @Override public BackendExecutionResult execute(ExecutionRequest request, ExecutionPolicy policy,
                                                            StagedWorkspace workspace) {
                calls.incrementAndGet();
                assertTrue(workspace.unchangedSinceApproval());
                return result;
            }
        };
    }

    private ExecutionRequest request(Path root) {
        return new ExecutionRequest(UUID.randomUUID(), "tool-call-1", ExecutionRequest.Action.MAVEN_TEST,
                root, List.of("src/App.java"), "sample.AppTest", null, Duration.ofSeconds(30), 4096);
    }

    private ExecutionPolicy policy() {
        return new ExecutionPolicy(IMAGE, temp, "10001:10001", 1.0, 512L * 1024 * 1024, 64,
                128L * 1024 * 1024, 32L * 1024 * 1024, Duration.ofMinutes(2), 8192,
                32, 1024 * 1024, 4 * 1024 * 1024);
    }

    private Path workspace(String source) throws IOException {
        Path root = Files.createDirectory(temp.resolve("workspace-" + UUID.randomUUID()));
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return root;
    }

    private static com.agent.tool.execution.ProcessExecutionResult process(int exit, boolean timeout) {
        return new com.agent.tool.execution.ProcessExecutionResult(exit, timeout, "", false, 1);
    }
}
