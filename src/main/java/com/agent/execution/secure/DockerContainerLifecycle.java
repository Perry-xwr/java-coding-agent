package com.agent.execution.secure;

import com.agent.tool.execution.ProcessExecutionResult;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Docker lifecycle orchestration with explicit stop/remove cleanup; command execution is injected. */
final class DockerContainerLifecycle {
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private final DockerCommandExecutor executor;

    DockerContainerLifecycle(DockerCommandExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    BackendExecutionResult execute(DockerInvocationBuilder.InvocationPlan plan,
                                   CancellationSignal cancellation) {
        boolean owned = false;
        boolean started = false;
        boolean stopRequired = false;
        long start = System.nanoTime();
        BackendExecutionResult outcome;
        try {
            ProcessExecutionResult create = executor.run(plan.createArguments(), CREATE_TIMEOUT,
                    plan.maxOutputBytes(), cancellation);
            if (create.timedOut()) {
                boolean verifiedOwner = ownsContainer(plan);
                ExecutionResult.CleanupStatus cleanup = verifiedOwner ? cleanup(plan, true)
                        : ExecutionResult.CleanupStatus.UNKNOWN;
                return result(ExecutionResult.Status.START_FAILED, false, null, create, plan,
                        elapsed(start), cleanup);
            }
            if (create.exitCode() != 0) {
                return result(ExecutionResult.Status.START_FAILED, false, null, create, plan,
                        elapsed(start), ExecutionResult.CleanupStatus.NOT_REQUIRED);
            }
            owned = true;
            ProcessExecutionResult copy = executor.run(plan.copyArguments(), CREATE_TIMEOUT,
                    plan.maxOutputBytes(), cancellation);
            if (copy.timedOut() || copy.exitCode() != 0) {
                outcome = result(copy.timedOut() ? ExecutionResult.Status.TIMED_OUT
                                : ExecutionResult.Status.START_FAILED,
                        false, null, copy, plan, elapsed(start), ExecutionResult.CleanupStatus.UNKNOWN);
                return withCleanup(outcome, plan, owned, false);
            }
            if (cancellation.cancelled()) {
                outcome = result(ExecutionResult.Status.CANCELLED, false, null, copy, plan,
                        elapsed(start), ExecutionResult.CleanupStatus.UNKNOWN);
                return withCleanup(outcome, plan, owned, false);
            }
            started = true;
            ProcessExecutionResult run = executor.run(plan.startArguments(), plan.timeout(),
                    plan.maxOutputBytes(), cancellation);
            if (run.timedOut()) {
                stopRequired = true;
                outcome = result(ExecutionResult.Status.TIMED_OUT, true, null, run, plan,
                        elapsed(start), ExecutionResult.CleanupStatus.UNKNOWN);
            } else if (cancellation.cancelled()) {
                stopRequired = true;
                outcome = result(ExecutionResult.Status.CANCELLED, true, null, run, plan,
                        elapsed(start), ExecutionResult.CleanupStatus.UNKNOWN);
            } else {
                outcome = result(ExecutionResult.Status.EXITED, true, run.exitCode(), run, plan,
                        elapsed(start), ExecutionResult.CleanupStatus.UNKNOWN);
            }
            return withCleanup(outcome, plan, owned, stopRequired);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = new BackendExecutionResult(ExecutionResult.Status.CANCELLED, started, null,
                    "", false, elapsed(start), plan.containerName(), ExecutionResult.CleanupStatus.UNKNOWN);
            return withCleanup(outcome, plan, owned, started);
        } catch (IOException | RuntimeException exception) {
            outcome = new BackendExecutionResult(ExecutionResult.Status.START_FAILED, started, null,
                    "", false, elapsed(start), plan.containerName(), ExecutionResult.CleanupStatus.UNKNOWN);
            return withCleanup(outcome, plan, owned, started);
        }
    }

    private BackendExecutionResult withCleanup(BackendExecutionResult original,
                                               DockerInvocationBuilder.InvocationPlan plan,
                                               boolean owned, boolean stopRequired) {
        if (!owned) return original;
        ExecutionResult.CleanupStatus status = cleanup(plan, stopRequired);
        return new BackendExecutionResult(original.status(), original.executed(), original.exitCode(),
                original.output(), original.outputTruncated(), original.durationMs(),
                original.containerName(), status);
    }

    private boolean ownsContainer(DockerInvocationBuilder.InvocationPlan plan) {
        try {
            ProcessExecutionResult result = executor.run(plan.ownershipCheckArguments(), CLEANUP_TIMEOUT, 256,
                    () -> false);
            return !result.timedOut() && result.exitCode() == 0
                    && result.output().trim().equals(requestLabel(plan.createArguments()));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String requestLabel(List<String> args) {
        for (String arg : args) if (arg.startsWith("com.agent.s1a.request=")) return arg.substring(arg.indexOf('=') + 1);
        return "";
    }

    private ExecutionResult.CleanupStatus cleanup(DockerInvocationBuilder.InvocationPlan plan, boolean stop) {
        if (stop) {
            try { executor.run(plan.stopArguments(), CLEANUP_TIMEOUT, 512, () -> false); }
            catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                // Force-remove is still attempted; it stops a running container if the daemon is reachable.
            }
        }
        try {
            ProcessExecutionResult removed = executor.run(plan.removeArguments(), CLEANUP_TIMEOUT, 512, () -> false);
            return !removed.timedOut() && removed.exitCode() == 0
                    ? ExecutionResult.CleanupStatus.SUCCEEDED : ExecutionResult.CleanupStatus.FAILED;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return ExecutionResult.CleanupStatus.FAILED;
        }
    }

    private static BackendExecutionResult result(ExecutionResult.Status status, boolean executed,
                                                 Integer exitCode, ProcessExecutionResult process,
                                                 DockerInvocationBuilder.InvocationPlan plan,
                                                 long duration, ExecutionResult.CleanupStatus cleanup) {
        ExecutionOutputSanitizer.SanitizedOutput safe = ExecutionOutputSanitizer.sanitize(
                process.output(), plan.maxOutputBytes());
        return new BackendExecutionResult(status, executed, exitCode, safe.output(),
                process.outputTruncated() || safe.truncated(), duration, plan.containerName(), cleanup);
    }

    private static long elapsed(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    @FunctionalInterface
    public interface CancellationSignal { boolean cancelled(); }

    @FunctionalInterface
    public interface DockerCommandExecutor {
        ProcessExecutionResult run(List<String> arguments, Duration timeout, int maxOutputBytes,
                                   CancellationSignal cancellation) throws IOException, InterruptedException;
    }
}
