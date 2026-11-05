package com.agent.execution.secure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** An owned temporary copy. Only its workspace child is ever sent to a container. */
public final class StagedWorkspace implements AutoCloseable {
    private final Path parent;
    private final Path ownerDirectory;
    private final Path workspaceCopy;
    private final Path sourceRoot;
    private final Path marker;
    private final String requestId;
    private final Map<String, String> sourceHashes;
    private final String snapshotDigest;
    private boolean closed;

    StagedWorkspace(Path parent, Path ownerDirectory, Path workspaceCopy, Path sourceRoot, Path marker,
                    String requestId, Map<String, String> sourceHashes, String snapshotDigest) {
        this.parent = parent;
        this.ownerDirectory = ownerDirectory;
        this.workspaceCopy = workspaceCopy;
        this.sourceRoot = sourceRoot;
        this.marker = marker;
        this.requestId = requestId;
        this.sourceHashes = Map.copyOf(sourceHashes);
        this.snapshotDigest = snapshotDigest;
    }

    public Path workspaceCopy() { return workspaceCopy; }
    public String snapshotDigest() { return snapshotDigest; }
    public Map<String, String> sourceHashes() { return sourceHashes; }

    /** Revalidates source and staged bytes after approval and before backend execution. */
    public boolean unchangedSinceApproval() {
        if (closed) return false;
        try {
            for (Map.Entry<String, String> item : sourceHashes.entrySet()) {
                Path source = sourceRoot.resolve(item.getKey()).normalize();
                if (!noSymlinkPath(sourceRoot, item.getKey())
                        || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)
                        || !item.getValue().equals(sha256(Files.readAllBytes(source)))) return false;
                Path staged = workspaceCopy.resolve(item.getKey()).normalize();
                if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(staged)
                        || !item.getValue().equals(sha256(Files.readAllBytes(staged)))) return false;
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        Path realParent = parent.toRealPath();
        if (!ownerDirectory.getParent().equals(realParent)
                || Files.isSymbolicLink(ownerDirectory)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).equals(requestId)) {
            throw new IOException("Refusing to clean an unowned or relocated staging directory");
        }
        Files.walkFileTree(ownerDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                makeWritableForCleanup(directory, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                makeWritableForCleanup(file, false);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                makeWritableForCleanup(directory, true);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String digest(Map<String, String> hashes) {
        String canonical = hashes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "\u0000" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private boolean noSymlinkPath(Path root, String relative) {
        Path current = root;
        for (Path part : Path.of(relative)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return false;
        }
        return current.normalize().startsWith(root);
    }

    private static void makeWritableForCleanup(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                    : java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
    }
}
