package com.agent.benchmark.planning;

import com.agent.benchmark.FixtureWorkspaceManager;
import com.agent.agent.PlanningMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Materializes separate clean workspaces for each task/mode condition. */
public final class PlanningFixtureWorkspace {
    public Path reset(PlanningBenchmarkTask task, Path fixturesRoot, Path runsRoot,
                      String runId, PlanningMode mode) throws IOException {
        Path fixtureRoot = fixturesRoot.toAbsolutePath().normalize();
        Path root = runsRoot.toAbsolutePath().normalize();
        Path workspace = root.resolve(runId).resolve(mode.name()).resolve(task.id()).normalize();
        Path fixture = fixtureRoot.resolve(task.fixture()).normalize();
        if (!workspace.startsWith(root) || !fixture.startsWith(fixtureRoot)) {
            throw new IOException("planning-v1 workspace or fixture escaped its root");
        }
        deleteTree(workspace);
        Files.createDirectories(workspace);
        FixtureWorkspaceManager.copyTree(fixture, workspace);
        return workspace;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }
}
