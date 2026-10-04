package com.agent.benchmark.toolavailability;

import com.agent.benchmark.FixtureWorkspaceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class ToolAvailabilityWorkspace {
    Path create(ToolAvailabilityTask task, Path fixturesRoot, Path runsRoot, String runId,
                ToolAvailabilityMode mode) throws IOException {
        Path fixtureBase = fixturesRoot.toAbsolutePath().normalize();
        Path runBase = runsRoot.toAbsolutePath().normalize();
        Path destination = runBase.resolve(runId).resolve(task.id()).resolve(mode.name()).normalize();
        Path source = fixtureBase.resolve(task.fixture()).normalize();
        if (!source.startsWith(fixtureBase) || !destination.startsWith(runBase)) {
            throw new IOException("Tool-availability fixture or run path escaped its configured root");
        }
        if (Files.exists(destination)) throw new IOException("Refusing to overwrite condition workspace: " + destination);
        Files.createDirectories(destination);
        FixtureWorkspaceManager.copyTree(source, destination);
        return destination;
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
}
