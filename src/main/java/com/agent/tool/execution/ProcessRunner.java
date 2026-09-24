package com.agent.tool.execution;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface ProcessRunner {
    ProcessExecutionResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes
    ) throws IOException, InterruptedException;
}
