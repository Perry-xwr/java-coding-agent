package com.agent.benchmark.editreliability;

import com.agent.benchmark.FixtureWorkspaceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class EditReliabilityWorkspace {
    public Path reset(EditReliabilityTask task, Path fixturesRoot, Path runsRoot,
                      String runId, EditReliabilityMode mode) throws IOException {
        Path fixtures = fixturesRoot.toAbsolutePath().normalize();
        Path runs = runsRoot.toAbsolutePath().normalize();
        Path fixture = fixtures.resolve(task.fixture()).normalize();
        Path workspace = runs.resolve(runId).resolve(task.id()).resolve(mode.name()).normalize();
        if (!fixture.startsWith(fixtures) || !workspace.startsWith(runs)) {
            throw new IOException("edit reliability fixture or workspace escaped its root");
        }
        if (Files.exists(workspace)) {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(workspace);
        FixtureWorkspaceManager.copyTree(fixture, workspace);
        return workspace;
    }
}
