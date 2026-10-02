package com.agent.benchmark.memory;

import com.agent.benchmark.FixtureWorkspaceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Creates an isolated workspace for every memory-v1 task and condition. */
public final class MemoryFixtureWorkspace {
    private final Path fixturesRoot;

    public MemoryFixtureWorkspace(Path fixturesRoot) {
        this.fixturesRoot = fixturesRoot.toAbsolutePath().normalize();
    }

    public Path reset(MemoryBenchmarkTask task, Path runsRoot, String runId, String condition) throws IOException {
        Path root = runsRoot.toAbsolutePath().normalize();
        Path workspace = root.resolve(runId).resolve(condition).resolve(task.id()).normalize();
        if (!workspace.startsWith(root)) {
            throw new IOException("memory-v1 workspace escaped runs root");
        }
        Path fixture = fixturesRoot.resolve(task.fixture()).normalize();
        if (!fixture.startsWith(fixturesRoot)) {
            throw new IOException("memory-v1 fixture escaped fixtures root");
        }
        deleteTree(workspace);
        Files.createDirectories(workspace);
        FixtureWorkspaceManager.copyTree(fixture, workspace);
        return workspace;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(item);
            }
        }
    }
}
