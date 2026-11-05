package com.agent.execution.secure;

import com.agent.tool.WorkspacePathResolver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Copies only explicitly selected, bounded regular UTF-8 files; unsafe inputs abort the whole stage. */
public final class WorkspaceStager {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|access[_-]?token|authorization)\\s*[:=]\\s*['\\\"]?[A-Za-z0-9_./+=-]{8,}");
    private static final List<String> SECRET_MARKERS = List.of(
            "-----begin private key-----", "authorization: bearer", "glm_api_key=",
            "deepseek_api_key=", "aws_secret_access_key=");
    private static final Set<String> SENSITIVE_DIRS = Set.of(
            ".git", ".m2", ".ssh", ".aws", ".azure", ".kube", ".docker", ".config",
            "credentials", "secrets", "target", "node_modules");
    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx");

    public StagedWorkspace stage(ExecutionRequest request, ExecutionPolicy policy) throws IOException {
        policy.validate(request);
        Path parent = policy.stagingParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("Staging parent must be an existing non-symlink directory");
        }
        parent = parent.toRealPath();
        Path sourceRoot = new WorkspacePathResolver(request.workspaceRoot()).root();
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace is not a directory");
        if (request.includedFiles().isEmpty() || request.includedFiles().size() > policy.maxFiles()) {
            throw new IOException("Selected file count is empty or exceeds policy");
        }

        Path owner = Files.createTempDirectory(parent, "agent-exec-" + request.requestId().toString().substring(0, 8) + "-");
        Path marker = owner.resolve(".agent-execution-owner");
        Path copy = owner.resolve("workspace");
        try {
            Files.writeString(marker, request.requestId().toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.createDirectory(copy);
            setOwnerOnlyIfPosix(owner);
            setOwnerOnlyIfPosix(copy);

            List<String> normalized = request.includedFiles().stream().map(WorkspaceStager::normalizeRelative)
                    .distinct().sorted().toList();
            if (normalized.size() != request.includedFiles().size()) throw new IOException("Duplicate selected paths");
            Map<String, String> hashes = new LinkedHashMap<>();
            long total = 0;
            for (String relative : normalized) {
                Path source = resolveNoSymlinks(sourceRoot, relative);
                BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.isRegularFile() || before.isSymbolicLink()) throw new IOException("Selected path is not a regular file");
                if (isSensitivePath(Path.of(relative))) throw new IOException("Selected path is excluded by sensitive-file policy");
                if (before.size() > policy.maxSingleFileBytes()) throw new IOException("Selected file exceeds per-file size limit");
                byte[] bytes = Files.readAllBytes(source);
                BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                    throw new IOException("Selected file changed while staging");
                }
                if (bytes.length > policy.maxSingleFileBytes()) throw new IOException("Selected file exceeds per-file size limit");
                total += bytes.length;
                if (total > policy.maxTotalBytes()) throw new IOException("Workspace snapshot exceeds total size limit");
                String text = decodeUtf8(bytes);
                String lower = text.toLowerCase(Locale.ROOT);
                if (SECRET_ASSIGNMENT.matcher(text).find() || SECRET_MARKERS.stream().anyMatch(lower::contains)) {
                    throw new IOException("Selected file matches a sensitive-content rule");
                }
                Path target = copy.resolve(relative).normalize();
                if (!target.startsWith(copy)) throw new IOException("Selected path escapes staging workspace");
                Files.createDirectories(target.getParent());
                setOwnerOnlyIfPosix(target.getParent());
                Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                setReadableFileIfPosix(target);
                hashes.put(relative, StagedWorkspace.sha256(bytes));
            }
            freezeSnapshot(copy);
            return new StagedWorkspace(parent, owner, copy, sourceRoot, marker, request.requestId().toString(), hashes,
                    StagedWorkspace.digest(hashes));
        } catch (IOException | RuntimeException exception) {
            try { deleteOwned(owner, parent, marker, request.requestId().toString()); }
            catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    private static Path resolveNoSymlinks(Path root, String relative) throws IOException {
        Path current = root;
        for (Path segment : Path.of(relative)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in staged paths");
        }
        Path normalized = current.normalize();
        if (!normalized.startsWith(root) || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Selected file is missing or outside workspace");
        }
        return normalized;
    }

    static String normalizeRelative(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Selected path must not be blank");
        Path path = Path.of(raw);
        if (path.isAbsolute()) throw new IllegalArgumentException("Absolute paths are not allowed");
        for (Path part : path) if (part.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) throw new IllegalArgumentException("Invalid selected path");
        return normalized.toString().replace('\\', '/');
    }

    static boolean isSensitivePath(Path path) {
        for (Path segment : path) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (SENSITIVE_DIRS.contains(name) || name.equals(".env") || name.startsWith(".env.")) return true;
            if (name.contains("credential") || name.contains("secret") || name.equals("id_rsa")
                    || name.equals("id_ed25519") || name.equals("known_hosts")) return true;
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && SENSITIVE_EXTENSIONS.contains(name.substring(dot))) return true;
        }
        return false;
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        if (containsNul(bytes)) throw new IOException("Binary files are not eligible for staging");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Non-UTF-8 files are not eligible for staging", exception);
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private static void setOwnerOnlyIfPosix(Path path) throws IOException {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) { /* Windows ACLs inherit from the configured temp parent. */ }
    }

    private static void setReadableFileIfPosix(Path path) throws IOException {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)); }
        catch (UnsupportedOperationException ignored) { /* Docker copies need readable modes; Windows ACLs govern host access. */ }
    }

    private static void freezeSnapshot(Path copy) throws IOException {
        try (var paths = Files.walk(copy)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
                }
            }
        } catch (UnsupportedOperationException ignored) { /* Windows inherits ACLs from the staging parent. */ }
    }

    private static void deleteOwned(Path owner, Path parent, Path marker, String requestId) throws IOException {
        if (Files.isSymbolicLink(owner) || !owner.getParent().equals(parent.toRealPath()))
            throw new IOException("Staging cleanup ownership check failed");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).equals(requestId)))
            throw new IOException("Staging cleanup ownership check failed");
        Files.walkFileTree(owner, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                makeWritableForCleanup(dir, true);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                makeWritableForCleanup(file, false);
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeWritableForCleanup(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
    }
}
