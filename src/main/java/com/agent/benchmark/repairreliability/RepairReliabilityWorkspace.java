package com.agent.benchmark.repairreliability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Copies one frozen fixture to one never-reused condition workspace. */
public final class RepairReliabilityWorkspace {
    public Path create(RepairReliabilityTask task, RepairReliabilityMode mode, Path fixturesRoot,
                       Path runsRoot, String runId) throws IOException {
        Path fixtures = fixturesRoot.toAbsolutePath().normalize();
        Path source = fixtures.resolve(task.fixture()).normalize();
        if (!source.startsWith(fixtures) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("fixture directory is missing or escapes fixture root: " + task.fixture());
        }
        Path root = runsRoot.toAbsolutePath().normalize();
        Path destination = root.resolve(runId).resolve(task.id()).resolve(mode.name()).normalize();
        if (!destination.startsWith(root)) throw new IOException("run workspace escapes result root");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("condition workspace already exists: " + destination);
        }
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("fixture symlinks are not allowed");
                Path target = destination.resolve(source.relativize(path)).normalize();
                if (!target.startsWith(destination)) throw new IOException("fixture entry escapes destination");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.copy(path, target);
                else throw new IOException("unsupported fixture entry: " + path);
            }
        }
        return destination;
    }
}
