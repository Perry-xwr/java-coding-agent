package com.agent.benchmark.adaptiveplanning;

import com.agent.benchmark.FixtureWorkspaceManager;
import com.agent.agent.PlanningMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Builds one clean workspace per run/task/mode beneath the isolated benchmark root. */
public final class AdaptivePlanningWorkspace {
    public Path reset(AdaptivePlanningTask task, Path fixturesRoot, Path runsRoot,
                      String runId, PlanningMode mode) throws IOException {
        Path fixtureRoot = fixturesRoot.toAbsolutePath().normalize();
        Path runRoot = runsRoot.toAbsolutePath().normalize();
        Path conditionRoot = runRoot.resolve(runId).resolve(task.id()).resolve(mode.name()).normalize();
        Path fixture = fixtureRoot.resolve(task.fixture()).normalize();
        if (!conditionRoot.startsWith(runRoot) || !fixture.startsWith(fixtureRoot)) {
            throw new IOException("adaptive benchmark workspace or fixture escaped its root");
        }
        deleteTree(conditionRoot);
        Files.createDirectories(conditionRoot);
        FixtureWorkspaceManager.copyTree(fixture, conditionRoot);
        return conditionRoot;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }
}
