package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WorkspacePathResolver {
    private final Path root;

    public WorkspacePathResolver(Path root) {
        Objects.requireNonNull(root, "root must not be null");

        Path normalizedRoot = root.toAbsolutePath().normalize();
        try {
            this.root = Files.exists(normalizedRoot)
                    ? normalizedRoot.toRealPath()
                    : normalizedRoot;
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to resolve workspace root: " + normalizedRoot,
                    exception
            );
        }
    }

    public Path resolveExisting(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path requested = Path.of(value);
        if (requested.isAbsolute()) {
            throw new WorkspaceViolationException(
                    "Absolute paths are not allowed: " + value
            );
        }

        Path normalized = root.resolve(requested).normalize();
        if (!normalized.startsWith(root)) {
            throw new WorkspaceViolationException(
                    "Path is outside the workspace: " + value
            );
        }

        Path realPath = normalized.toRealPath();
        if (!realPath.startsWith(root)) {
            throw new WorkspaceViolationException(
                    "Resolved path is outside the workspace: " + value
            );
        }
        return realPath;
    }

    public Path root() {
        return root;
    }
}
