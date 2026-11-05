package com.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Bounded, content-free last-run provider status for diagnosing Daily failures after restart. */
public final class DailyProviderDiagnosticStore {
    public record Record(int version, String updatedAt, String sessionId, String turnId,
                         String taskStatus, long httpAttempts, String httpStatus,
                         String providerStatus, String failureCategory, String usage) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path workspace;
    private final Path file;

    public DailyProviderDiagnosticStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.file = this.workspace.resolve(".agent-cli/diagnostics/daily-provider-last.json");
    }

    public Record read() throws IOException {
        checkPath(false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096)
            throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_UNSAFE_OR_OVERSIZED");
        Record value = JSON.readValue(Files.readAllBytes(file), Record.class);
        if (value.version() != 1) throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_VERSION_UNSUPPORTED");
        return value;
    }

    public void write(Record record) throws IOException {
        checkPath(true);
        Files.createDirectories(file.getParent());
        checkPath(true);
        byte[] bytes = JSON.writeValueAsBytes(record);
        if (bytes.length > 4096) throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_TOO_LARGE");
        Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_ATOMIC_MOVE_UNAVAILABLE", unsupported);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void checkPath(boolean forWrite) throws IOException {
        for (Path parent = workspace; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_UNSAFE_PATH");
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_WORKSPACE_UNAVAILABLE");
        Path metadata = workspace.resolve(".agent-cli");
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(metadata))
            throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_UNSAFE_PATH");
        Path diagnostics = metadata.resolve("diagnostics");
        if (Files.exists(diagnostics, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(diagnostics))
            throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_UNSAFE_PATH");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(file))
            throw new IOException("DAILY_PROVIDER_DIAGNOSTIC_UNSAFE_PATH");
    }

    public static Record record(String sessionId, String turnId, String taskStatus, long attempts,
                                Integer httpStatus, String providerStatus, String failureCategory,
                                String usage) {
        return new Record(1, Instant.now().toString(), safeId(sessionId), safeId(turnId),
                taskStatus, Math.max(0, attempts), httpStatus == null ? "UNKNOWN" : httpStatus.toString(),
                providerStatus, failureCategory, usage);
    }

    private static String safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}") ? value : "UNKNOWN";
    }
}
