package com.agent.agent;

import com.agent.llm.ToolCall;
import com.agent.tool.ToolResult;
import com.agent.tool.WorkspacePathResolver;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Captures only files named by successful mutation tool calls; never consults Git state. */
public final class TaskDiffCollector {
    public static final int MAX_FILE_DIFF_CHARS = 12_000;
    public static final int MAX_TOTAL_DIFF_CHARS = 24_000;
    private static final int MAX_CAPTURE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> MUTATION_TOOLS = Set.of("create_file", "apply_patch", "apply_context_patch",
            "replace_lines", "insert_before", "insert_after");
    private static final Set<String> SENSITIVE_NAMES = Set.of(".env", ".env.local", "credentials", "secrets",
            "id_rsa", "id_ed25519", "known_hosts");
    private static final List<String> SENSITIVE_MARKERS = List.of("-----BEGIN PRIVATE KEY-----", "api_key=",
            "api-key:", "authorization: bearer", "glm_api_key=", "deepseek_api_key=", "password=",
            "password:", "secret=", "secret:", "token=");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|access[_-]?token|authorization)\\s*[:=]\\s*['\\\"]?[A-Za-z0-9_./+=-]{8,}");

    private final Path root;
    private final WorkspacePathResolver pathResolver;
    private final Map<String, MutableChange> changes = new LinkedHashMap<>();

    public TaskDiffCollector(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.pathResolver = root == null ? null : new WorkspacePathResolver(root);
    }

    public Snapshot before(ToolCall call) {
        if (root == null || call == null || !MUTATION_TOOLS.contains(call.name())) return Snapshot.unavailable();
        String path = targetPath(call);
        if (path == null) return Snapshot.unavailable();
        try {
            Path target = resolve(path);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) > MAX_CAPTURE_BYTES) {
                return new Snapshot(path, null, null, call.id(), true, false);
            }
            byte[] bytes = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readAllBytes(target) : null;
            return new Snapshot(path, bytes, bytes == null ? null : sha256(bytes), call.id(), true, true);
        } catch (IOException | RuntimeException exception) {
            return new Snapshot(path, null, null, call.id(), false, false);
        }
    }

    public void after(Snapshot before, ToolCall call, ToolResult result) {
        if (!before.available() || result == null) return;
        if (!before.capturable()) {
            MutableChange unavailable = changes.computeIfAbsent(before.path(), ignored -> new MutableChange(null));
            unavailable.callIds.add(call.id());
            unavailable.captureUnavailable = true;
            unavailable.attributionUncertain = true;
            unavailable.lastStatus = "FILE_TOO_LARGE_OR_UNREADABLE_TO_CAPTURE";
            return;
        }
        boolean claimedChange = result.success() && Boolean.TRUE.equals(result.metadata().get("changed"));
        if (!claimedChange && result.success()) {
            try {
                Path target = resolve(before.path());
                byte[] current = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(target) : null;
                String currentHash = current == null ? null : sha256(current);
                if (java.util.Objects.equals(currentHash, before.sha256())) return;
                MutableChange external = changes.computeIfAbsent(before.path(), ignored -> new MutableChange(before.bytes()));
                external.callIds.add(call.id());
                external.latestBytes = current;
                external.latestHash = currentHash;
                external.attributionUncertain = true;
                external.lastStatus = "FILE_CHANGED_DESPITE_NOOP_TOOL_RESULT";
            } catch (IOException | RuntimeException ignored) {
                // The tool result remains authoritative for its own no-op; unreadable targets are not previewed.
            }
            return;
        }
        String path = before.path();
        MutableChange change = changes.computeIfAbsent(path, ignored -> new MutableChange(before.bytes()));
        change.callIds.add(call.id());
        if (!result.success()) {
            change.lastStatus = "TOOL_FAILED";
            return;
        }
        try {
            Path target = resolve(path);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) > MAX_CAPTURE_BYTES) {
                change.attributionUncertain = true;
                change.lastStatus = "FILE_TOO_LARGE_TO_CAPTURE";
                return;
            }
            if (change.latestHash != null && !change.latestHash.equals(before.sha256())) {
                change.attributionUncertain = true;
            }
            byte[] after = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(target) : null;
            String actualBeforeHash = before.sha256();
            String actualAfterHash = after == null ? null : sha256(after);
            String expectedBefore = stringValue(result.metadata().get("versionBefore"));
            String expectedAfter = stringValue(result.metadata().get("versionAfter"));
            if (before.bytes() != null && expectedBefore != null && !expectedBefore.equals(actualBeforeHash)) {
                change.attributionUncertain = true;
            }
            if (expectedAfter != null && !expectedAfter.equals(actualAfterHash)) change.attributionUncertain = true;
            if ((before.bytes() != null && expectedBefore == null) || expectedAfter == null) {
                change.attributionUncertain = true;
            }
            change.latestBytes = after;
            change.latestHash = actualAfterHash;
            change.lastStatus = after == null ? "FILE_MISSING_AFTER_TOOL" : "CAPTURED";
        } catch (IOException | RuntimeException exception) {
            change.attributionUncertain = true;
            change.lastStatus = "UNREADABLE_AFTER_TOOL";
        }
    }

    public TaskChangeReview finish(String runId, Map<String, String> verificationByPath) {
        List<TaskChangeReview.FileChange> files = new ArrayList<>();
        int remaining = MAX_TOTAL_DIFF_CHARS;
        int total = 0;
        boolean allComplete = true;
        for (Map.Entry<String, MutableChange> entry : changes.entrySet()) {
            String path = entry.getKey();
            MutableChange item = entry.getValue();
            if (item.captureUnavailable) {
                String message = "[Diff unavailable: file exceeded the safe capture limit or was unreadable.]\n";
                if (message.length() > remaining) {
                    message = remaining >= "[DIFF TRUNCATED]\n".length() ? "[DIFF TRUNCATED]\n" : "";
                    allComplete = false;
                }
                files.add(new TaskChangeReview.FileChange(path, TaskChangeReview.ChangeType.UNKNOWN,
                        TaskChangeReview.Attribution.UNCERTAIN, null, null, message, false,
                        item.lastStatus, item.callIds, verificationByPath.getOrDefault(path, "NOT_RUN")));
                allComplete = false;
                total += message.length();
                remaining -= message.length();
                continue;
            }
            try {
                Path target = resolve(path);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) > MAX_CAPTURE_BYTES) {
                    item.attributionUncertain = true;
                    item.lastStatus = "FILE_TOO_LARGE_TO_CAPTURE";
                    continue;
                }
                byte[] current = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(target) : null;
                String currentHash = current == null ? null : sha256(current);
                if (!java.util.Objects.equals(currentHash, item.latestHash)) item.attributionUncertain = true;
                item.latestBytes = current;
                item.latestHash = currentHash;
            } catch (IOException | RuntimeException exception) {
                item.attributionUncertain = true;
            }
            if (java.util.Objects.equals(item.baselineHash(), item.latestHash)) continue;
            boolean binary = !isUtf8(item.baseline) || !isUtf8(item.latestBytes);
            String diff = binary ? "[Diff unavailable: file is binary or not valid UTF-8.]\n" : unified(path,
                    item.baseline == null ? null : decode(item.baseline),
                    item.latestBytes == null ? null : decode(item.latestBytes));
            boolean sensitive = sensitive(path, item.baseline, item.latestBytes);
            if (sensitive) diff = "[Diff hidden: file path or content appears sensitive.]\n";
            if (sensitive || binary) allComplete = false;
            boolean complete = diff.length() <= Math.min(MAX_FILE_DIFF_CHARS, remaining);
            if (!complete) {
                String marker = "[DIFF TRUNCATED]\n";
                int contentLimit = Math.min(MAX_FILE_DIFF_CHARS, remaining) - marker.length();
                diff = contentLimit < 0 ? ""
                        : diff.substring(0, Math.min(contentLimit, diff.length())) + marker;
                allComplete = false;
            }
            remaining = Math.max(0, remaining - diff.length());
            total += diff.length();
            TaskChangeReview.ChangeType type = item.baseline == null
                    ? TaskChangeReview.ChangeType.ADDED : item.latestBytes == null
                    ? TaskChangeReview.ChangeType.DELETED : TaskChangeReview.ChangeType.MODIFIED;
            files.add(new TaskChangeReview.FileChange(path, type,
                    item.attributionUncertain || sensitive ? TaskChangeReview.Attribution.UNCERTAIN
                            : TaskChangeReview.Attribution.TOOL_EXECUTION,
                    item.baselineHash(), item.latestHash, diff, complete && !sensitive && !binary,
                    sensitive ? "SENSITIVE_CONTENT_HIDDEN" : binary ? "BINARY_DIFF_UNAVAILABLE" : item.lastStatus,
                    item.callIds, verificationByPath.getOrDefault(path,
                            verificationByPath.getOrDefault(path.replace('/', '\\'), "NOT_RUN"))));
        }
        return new TaskChangeReview(runId, files, total, MAX_TOTAL_DIFF_CHARS, allComplete);
    }

    private Path resolve(String relative) throws IOException {
        Path candidate = Path.of(relative);
        if (candidate.isAbsolute()) throw new IOException("unsafe path");
        Path target = root.resolve(candidate).normalize();
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                ? pathResolver.resolveExisting(relative)
                : pathResolver.resolveNew(relative);
    }

    private static String targetPath(ToolCall call) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(call.arguments());
            String value = node == null ? null : node.path("path").asText(null);
            return value == null || value.isBlank() ? null
                    : Path.of(value).normalize().toString().replace('\\', '/');
        } catch (Exception exception) { return null; }
    }

    private static String unified(String path, String before, String after) {
        if (java.util.Objects.equals(before, after)) return "";
        String oldValue = before == null ? "" : before;
        String newValue = after == null ? "" : after;
        List<String> oldLines = lines(oldValue);
        List<String> newLines = lines(newValue);
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
        int oldSuffix = oldLines.size(), newSuffix = newLines.size();
        while (oldSuffix > prefix && newSuffix > prefix
                && oldLines.get(oldSuffix - 1).equals(newLines.get(newSuffix - 1))) { oldSuffix--; newSuffix--; }
        boolean lineEndingChanged = !oldValue.isEmpty() && oldValue.endsWith("\r\n") != newValue.endsWith("\r\n");
        if (prefix == oldLines.size() && prefix == newLines.size() && lineEndingChanged) {
            StringBuilder eolOnly = new StringBuilder("--- a/").append(path).append("\n+++ b/")
                    .append(path).append("\n@@ -1,").append(oldLines.size()).append(" +1,")
                    .append(newLines.size()).append(" @@\n");
            for (String line : oldLines) eolOnly.append(' ').append(line).append('\n');
            return eolOnly.append("\\ Line ending style changed\n").toString();
        }
        StringBuilder out = new StringBuilder("--- ").append(before == null ? "/dev/null" : "a/" + path)
                .append("\n+++ ").append(after == null ? "/dev/null" : "b/" + path)
                .append("\n@@ -").append(prefix + 1).append(',').append(oldSuffix - prefix)
                .append(" +").append(prefix + 1).append(',').append(newSuffix - prefix).append(" @@\n");
        for (int index = prefix; index < oldSuffix; index++) out.append('-').append(oldLines.get(index)).append('\n');
        for (int index = prefix; index < newSuffix; index++) out.append('+').append(newLines.get(index)).append('\n');
        if (lineEndingChanged) {
            out.append("\\ Line ending style changed\n");
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }
    private static boolean sensitive(String path, byte[] before, byte[] after) {
        String name = Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (SENSITIVE_NAMES.contains(name) || name.startsWith(".env.")) return true;
        String text = (decode(before) + "\n" + decode(after)).toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(text::contains) || SENSITIVE_ASSIGNMENT.matcher(text).find();
    }
    private static boolean isUtf8(byte[] bytes) { return bytes == null || decodeStrict(bytes) != null; }
    private static String decode(byte[] bytes) { return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8); }
    private static String decodeStrict(byte[] bytes) {
        if (bytes == null) return "";
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { return null; }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String stringValue(Object value) { return value instanceof String text ? text : null; }

    public record Snapshot(String path, byte[] bytes, String sha256, String toolCallId,
                           boolean available, boolean capturable) {
        static Snapshot unavailable() { return new Snapshot(null, null, null, null, false, false); }
    }
    private static final class MutableChange {
        final byte[] baseline;
        byte[] latestBytes;
        String latestHash;
        boolean attributionUncertain;
        boolean captureUnavailable;
        String lastStatus = "TOOL_FAILED";
        final List<String> callIds = new ArrayList<>();
        MutableChange(byte[] baseline) { this.baseline = baseline == null ? null : baseline.clone(); }
        String baselineHash() { return baseline == null ? null : sha256(baseline); }
    }
}
