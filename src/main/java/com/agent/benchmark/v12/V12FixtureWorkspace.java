package com.agent.benchmark.v12;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/** Materializes each manifest fixture into an isolated, resettable workspace. */
public final class V12FixtureWorkspace {
    public Path reset(V12Task task, Path runsRoot, String runId) throws IOException {
        Path root = runsRoot.toAbsolutePath().normalize();
        Path workspace = root.resolve(runId).resolve(task.id()).normalize();
        if (!workspace.startsWith(root)) {
            throw new IOException("V1.2 workspace escaped runs root");
        }
        deleteTree(workspace);
        Files.createDirectories(workspace);
        for (Map.Entry<String, String> entry : task.initialFixture().entrySet()) {
            Path file = workspace.resolve(entry.getKey()).normalize();
            if (!file.startsWith(workspace)) {
                throw new IOException("V1.2 fixture escaped workspace");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return workspace;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }
}
