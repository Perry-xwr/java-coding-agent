package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
        Path normalized = resolveRelative(value, false);

        Path realPath = normalized.toRealPath();
        if (!realPath.startsWith(root)) {
            throw new WorkspaceViolationException(
                    "Resolved path is outside the workspace: " + value
            );
        }
        return realPath;
    }

    public Path resolveNew(String value) throws IOException {
        Path normalized = resolveRelative(value, true);
        if (normalized.getFileName() == null) {
            throw new WorkspaceViolationException("Path must name a file: " + value);
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(normalized.toString());
        }

        Path parent = normalized.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("Parent directory does not exist: " + parent);
        }
        Path realParent = parent.toRealPath();
        if (!Files.isDirectory(realParent)) {
            throw new NoSuchFileException("Parent directory does not exist: " + parent);
        }
        if (!realParent.startsWith(root)) {
            throw new WorkspaceViolationException(
                    "Resolved parent is outside the workspace: " + value
            );
        }
        return realParent.resolve(normalized.getFileName());
    }

    public Path root() {
        return root;
    }

    private Path resolveRelative(String value, boolean rejectParentTraversal) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path requested = Path.of(value);
        if (requested.isAbsolute()) {
            throw new WorkspaceViolationException(
                    "Absolute paths are not allowed: " + value
            );
        }
        if (rejectParentTraversal) {
            for (Path segment : requested) {
                if ("..".equals(segment.toString())) {
                    throw new WorkspaceViolationException("Parent traversal is not allowed: " + value);
                }
            }
        }
        Path normalized = root.resolve(requested).normalize();
        if (!normalized.startsWith(root)) {
            throw new WorkspaceViolationException(
                    "Path is outside the workspace: " + value
            );
        }
        return normalized;
    }
}
