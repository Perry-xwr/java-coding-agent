package com.agent.tool.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultProcessRunner implements ProcessRunner {
    private final Map<String, String> parentEnvironment;

    public DefaultProcessRunner() {
        this(System.getenv());
    }

    DefaultProcessRunner(Map<String, String> parentEnvironment) {
        this.parentEnvironment = Map.copyOf(parentEnvironment);
    }

    @Override
    public ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes
    ) throws IOException, InterruptedException {
        return runWithPolicy(command, workingDirectory, timeout, maxOutputBytes, Map.of(),
                ProcessEnvironmentProfile.GENERAL);
    }

    @Override
    public ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> environmentOverrides,
            ProcessEnvironmentProfile environmentProfile
    ) throws IOException, InterruptedException {
        return runWithPolicy(command, workingDirectory, timeout, maxOutputBytes, environmentOverrides,
                environmentProfile);
    }

    @Override
    public ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> environmentOverrides
    ) throws IOException, InterruptedException {
        return runWithPolicy(command, workingDirectory, timeout, maxOutputBytes, environmentOverrides,
                ProcessEnvironmentProfile.GENERAL);
    }

    private ProcessExecutionResult runWithPolicy(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> environmentOverrides,
            ProcessEnvironmentProfile environmentProfile
    ) throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }

        long startedNanos = System.nanoTime();
        ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> safeEnvironment = ProcessEnvironmentPolicy.create(
                environmentProfile,
                parentEnvironment,
                environmentOverrides,
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
        );
        processBuilder.environment().clear();
        processBuilder.environment().putAll(safeEnvironment);
        Process process = processBuilder.start();
        BoundedCapture capture = new BoundedCapture(maxOutputBytes);
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread outputReader = new Thread(
                () -> readOutput(process.getInputStream(), capture, readFailure),
                "agent-maven-output-reader"
        );
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outputReader.join(5_000);
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw exception;
        }

        IOException outputFailure = readFailure.get();
        if (outputFailure != null && finished) {
            throw outputFailure;
        }
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return new ProcessExecutionResult(
                exitCode,
                !finished,
                capture.output(),
                capture.truncated(),
                durationMs
        );
    }

    private static void readOutput(
            InputStream input,
            BoundedCapture capture,
            AtomicReference<IOException> failure
    ) {
        try (input) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                capture.append(buffer, count);
            }
        } catch (IOException exception) {
            failure.set(exception);
        }
    }

    private static void terminateProcessTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static final class BoundedCapture {
        private final int limit;
        private final ByteArrayOutputStream bytes;
        private boolean truncated;

        private BoundedCapture(int limit) {
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, 8_192));
        }

        private synchronized void append(byte[] buffer, int count) {
            int remaining = limit - bytes.size();
            if (remaining > 0) {
                bytes.write(buffer, 0, Math.min(remaining, count));
            }
            if (count > remaining) {
                truncated = true;
            }
        }

        private synchronized String output() {
            String value = bytes.toString(StandardCharsets.UTF_8);
            return truncated ? value + System.lineSeparator() + "...[output truncated]" : value;
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }
}
