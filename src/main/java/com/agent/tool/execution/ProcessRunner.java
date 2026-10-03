package com.agent.tool.execution;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProcessRunner {
    ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes
    ) throws IOException, InterruptedException;

    default ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> environmentOverrides
    ) throws IOException, InterruptedException {
        return run(command, workingDirectory, timeout, maxOutputBytes);
    }
}
