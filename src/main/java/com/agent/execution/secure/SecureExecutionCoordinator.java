package com.agent.execution.secure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Fail-closed approval/staging coordinator; not registered in ToolRegistry by S1-A. */
public final class SecureExecutionCoordinator {
    private final ExecutionPolicy policy;
    private final ExecutionApprover approver;
    private final IsolatedExecutionBackend backend;
    private final WorkspaceStager stager;

    /** Production construction always binds approval to the real OS console, never CLI/model input. */
    static SecureExecutionCoordinator interactive(ExecutionPolicy policy,
                                                  IsolatedExecutionBackend backend,
                                                  WorkspaceStager stager) {
        return new SecureExecutionCoordinator(policy, ConsoleExecutionApprover.systemConsole(), backend, stager);
    }

    /** Package-private test seam; fake approvers cannot be selected by normal runtime configuration. */
    SecureExecutionCoordinator(ExecutionPolicy policy, ExecutionApprover approver,
                               IsolatedExecutionBackend backend, WorkspaceStager stager) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.approver = java.util.Objects.requireNonNull(approver);
        this.backend = java.util.Objects.requireNonNull(backend);
        this.stager = java.util.Objects.requireNonNull(stager);
    }

    public ExecutionResult execute(ExecutionRequest request) {
        String digest = "";
        try {
            policy.validate(request);
            DockerEnvironmentCheck check = backend.inspect(policy);
            if (check == null || !check.readyFor(policy)) {
                return result(ApprovalDecision.Status.UNAVAILABLE, ExecutionResult.Status.UNAVAILABLE,
                        false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
            }
            StagedWorkspace workspace;
            try {
                workspace = stager.stage(request, policy);
            } catch (Exception exception) {
                return result(ApprovalDecision.Status.UNAVAILABLE, ExecutionResult.Status.STAGING_FAILED,
                        false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.UNKNOWN);
            }
            digest = requestDigest(request, policy, workspace.snapshotDigest(), workspace.workspaceCopy());
            ExecutionResult execution = executeStaged(request, workspace, digest);
            try {
                workspace.close();
            } catch (Exception cleanupFailure) {
                return result(execution.approvalStatus(), ExecutionResult.Status.CLEANUP_FAILED,
                        execution.executed(), execution.exitCode(), execution.output(),
                        execution.outputTruncated(), execution.durationMs(), digest,
                        execution.containerName(), ExecutionResult.CleanupStatus.FAILED);
            }
            return execution;
        } catch (RuntimeException exception) {
            return result(ApprovalDecision.Status.UNAVAILABLE, ExecutionResult.Status.POLICY_REJECTED,
                    false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
        }
    }

    private ExecutionResult executeStaged(ExecutionRequest request, StagedWorkspace workspace, String digest) {
        String challenge = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String selectedTest = request.testClass() == null ? "all tests"
                : request.testClass() + (request.testMethod() == null ? "" : "#" + request.testMethod());
        ExecutionApprovalPrompt prompt = new ExecutionApprovalPrompt(digest, challenge, request.action(),
                "source=" + request.workspaceRoot() + "; execution-copy=" + workspace.workspaceCopy(),
                selectedTest, policy.imageReference(),
                "Docker Linux container; network=none; read-only root; non-root; capped resources; "
                        + "only a disposable /workspace tmpfs; no host bind mounts",
                "Maven build plugins and tests execute untrusted project code.");
        ApprovalDecision decision = approver.approve(prompt);
        if (decision == null || decision.status() == ApprovalDecision.Status.UNAVAILABLE) {
            return result(ApprovalDecision.Status.UNAVAILABLE, ExecutionResult.Status.UNAVAILABLE,
                    false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
        }
        if (decision.status() == ApprovalDecision.Status.DENIED) {
            return result(ApprovalDecision.Status.DENIED, ExecutionResult.Status.DENIED,
                    false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
        }
        if (!digest.equals(decision.requestDigest()) || decision.approvalId().isBlank()) {
            return result(ApprovalDecision.Status.DENIED, ExecutionResult.Status.POLICY_REJECTED,
                    false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
        }
        if (!workspace.unchangedSinceApproval()) {
            return result(ApprovalDecision.Status.DENIED, ExecutionResult.Status.POLICY_REJECTED,
                    false, null, "", false, 0, digest, "", ExecutionResult.CleanupStatus.NOT_REQUIRED);
        }
        BackendExecutionResult actual;
        try {
            actual = backend.execute(request, policy, workspace);
        } catch (Exception exception) {
            actual = new BackendExecutionResult(ExecutionResult.Status.START_FAILED,
                    false, null, "", false, 0, "", ExecutionResult.CleanupStatus.UNKNOWN);
        }
        ExecutionResult.Status status = actual.cleanupStatus() == ExecutionResult.CleanupStatus.FAILED
                ? ExecutionResult.Status.CLEANUP_FAILED : actual.status();
        return result(ApprovalDecision.Status.APPROVED, status, actual.executed(), actual.exitCode(),
                actual.output(), actual.outputTruncated(), actual.durationMs(), digest,
                actual.containerName(), actual.cleanupStatus());
    }

    private static ExecutionResult result(ApprovalDecision.Status approval, ExecutionResult.Status status,
                                          boolean executed, Integer exitCode, String output,
                                          boolean truncated, long duration, String digest, String container,
                                          ExecutionResult.CleanupStatus cleanup) {
        return new ExecutionResult(approval, status, executed, exitCode, output, truncated,
                duration, digest, container, cleanup);
    }

    private static String requestDigest(ExecutionRequest request, ExecutionPolicy policy, String snapshot,
                                        java.nio.file.Path stagedPath) {
        String canonical = request.requestId() + "\n" + request.toolCallId() + "\n" + request.action()
                + "\n" + request.workspaceRoot() + "\n" + request.includedFiles().stream().sorted().toList()
                + "\n" + request.testClass() + "\n" + request.testMethod() + "\n"
                + request.timeout().toMillis() + "\n" + request.maxOutputBytes() + "\n"
                + policy.profileId() + "\n" + snapshot + "\n" + stagedPath.toAbsolutePath().normalize();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
