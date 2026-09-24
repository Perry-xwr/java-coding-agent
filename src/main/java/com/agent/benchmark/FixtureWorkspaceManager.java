package com.agent.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public final class FixtureWorkspaceManager {
    private final Path fixturesRoot;
    private final Path runsRoot;

    public FixtureWorkspaceManager(Path fixturesRoot, Path runsRoot) {
        this.fixturesRoot = Objects.requireNonNull(fixturesRoot).toAbsolutePath().normalize();
        this.runsRoot = Objects.requireNonNull(runsRoot).toAbsolutePath().normalize();
    }

    public Path reset(BenchmarkTask task, String experimentId) throws IOException {
        Path experimentRoot = runsRoot.resolve(experimentId).normalize();
        if (!experimentRoot.startsWith(runsRoot)) {
            throw new IOException("Invalid experiment id");
        }
        Path workspace = experimentRoot.resolve(task.id()).normalize();
        if (!workspace.startsWith(experimentRoot)) {
            throw new IOException("Invalid task id");
        }
        deleteTree(workspace);
        Files.createDirectories(workspace);
        copyTree(fixturesRoot.resolve("common"), workspace);
        copyTree(fixturesRoot.resolve(task.fixture()), workspace);
        return workspace;
    }

    public Path fixture(String fixture) {
        Path resolved = fixturesRoot.resolve(fixture).normalize();
        if (!resolved.startsWith(fixturesRoot)) {
            throw new IllegalArgumentException("Fixture is outside fixture root");
        }
        return resolved;
    }

    public static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Fixture directory does not exist: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
