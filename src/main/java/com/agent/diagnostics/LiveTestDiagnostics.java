package com.agent.diagnostics;

import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.llm.LiveTestControl;
import com.agent.llm.ModelRequestUsage;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Opt-in local JSONL trace. Content and prompts are omitted unless context capture is explicitly enabled. */
public final class LiveTestDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<LiveTestDiagnostics> ACTIVE = new ThreadLocal<>();
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|authorization|token|password)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static volatile String provider = "unknown";
    private static volatile String model = "unknown";
    private static volatile String endpoint = "unknown";

    private final Path traceFile;
    private final boolean captureEditContext;
    private volatile IOException failure;

    private LiveTestDiagnostics(Path traceFile) {
        this.traceFile = traceFile;
        this.captureEditContext = flag("agent.live-test-capture-edit-context",
                "AGENT_LIVE_TEST_CAPTURE_EDIT_CONTEXT");
    }

    public static boolean enabled() {
        return LiveTestControl.enabled() && flag("agent.live-test-diagnostics", "AGENT_LIVE_TEST_DIAGNOSTICS");
    }

    public static void configureProvider(String selectedProvider, String selectedModel, String apiEndpoint) {
        provider = safe(selectedProvider);
        model = safe(selectedModel);
        endpoint = safeEndpoint(apiEndpoint);
    }

    /** Creates traces only under the repo's ignored benchmark-runs fixture tree. */
    public static void beginRun(String runId, String task, Path workspaceRoot) {
        if (!enabled()) {
            ACTIVE.remove();
            return;
        }
        if (workspaceRoot == null) {
            throw new IllegalStateException("Live diagnostics require a local workspace root");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (root.getNameCount() < 1 || java.util.stream.IntStream.range(0, root.getNameCount())
                .mapToObj(index -> root.getName(index).toString())
                .noneMatch("benchmark-runs"::equalsIgnoreCase)) {
            throw new IllegalStateException("Live diagnostics are restricted to benchmark-runs workspaces");
        }
        try {
            Path directory = root.resolve("p1.5b-diagnostics");
            if (!isIgnoredBenchmarkWorkspace(root)) {
                throw new IOException("Diagnostic output is not proven Git-ignored by an ancestor repository");
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(directory)) {
                throw new IOException("Diagnostic output directory must not be a symbolic link");
            }
            Files.createDirectories(directory);
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(root.toRealPath())) {
                throw new IOException("Diagnostic output directory escapes the workspace");
            }
            Path trace = directory.resolve(runId + ".jsonl");
            if (Files.exists(trace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Diagnostic trace already exists; refusing to overwrite it");
            }
            LiveTestDiagnostics diagnostics = new LiveTestDiagnostics(trace);
            ACTIVE.set(diagnostics);
            diagnostics.write("run_started", Map.of(
                    "runId", runId,
                    "provider", provider,
                    "model", model,
                    "endpoint", endpoint,
                    "taskSha256", sha256(task),
                    "workspaceName", root.getFileName().toString(),
                    "requestLimit", LiveTestControl.MAX_REQUEST_ATTEMPTS,
                    "timeLimitSeconds", LiveTestControl.MAX_TASK_DURATION.toSeconds(),
                    "editContextCapture", diagnostics.captureEditContext));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize live-test diagnostic trace", exception);
        }
    }

    public static void recordModelRequest(int requestNumber, String requestEndpoint, String state) {
        record("model_request", Map.of("requestNumber", requestNumber,
                "endpoint", safeEndpoint(requestEndpoint), "state", state));
    }

    public static void recordModelResponse(boolean hasToolCalls, List<ToolCall> calls) {
        List<Map<String, String>> summary = calls.stream().map(call -> Map.of(
                "toolCallId", safe(call.id()), "toolName", safe(call.name()))).toList();
        record("model_response", Map.of("hasToolCalls", hasToolCalls, "toolCalls", summary));
    }

    /** Records numeric request metadata only; message text, tool arguments, and response bodies are excluded. */
    public static void recordRequestUsage(ModelRequestUsage usage) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestSequence", usage.requestSequence());
        event.put("provider", safe(usage.provider()));
        event.put("model", safe(usage.model()));
        event.put("endpoint", safeEndpoint(usage.endpoint()));
        event.put("runId", safe(usage.runId()));
        event.put("profile", safe(usage.profile()));
        event.put("agentTurn", usage.agentTurn());
        event.put("status", usage.status());
        event.put("httpStatus", usage.httpStatus() == null ? "UNKNOWN" : usage.httpStatus());
        event.put("usageAvailable", usage.usageAvailable());
        event.put("inputTokens", usage.inputTokens() == null ? "UNKNOWN" : usage.inputTokens());
        event.put("outputTokens", usage.outputTokens() == null ? "UNKNOWN" : usage.outputTokens());
        event.put("totalTokens", usage.totalTokens() == null ? "UNKNOWN" : usage.totalTokens());
        event.put("cacheHitTokens", usage.cacheHitTokens() == null ? "UNKNOWN" : usage.cacheHitTokens());
        event.put("cacheMissTokens", usage.cacheMissTokens() == null ? "UNKNOWN" : usage.cacheMissTokens());
        event.put("messageCount", usage.context().messageCount());
        event.put("toolDefinitionCount", usage.context().toolDefinitionCount());
        event.put("payloadBytes", usage.context().payloadBytes());
        event.put("messageCountByRole", usage.context().messageCountByRole());
        event.put("contentCharsByRole", usage.context().contentCharsByRole());
        record("model_usage", event);
    }

    public static String beforeToolCall(String toolCallId, String toolName,
                                        Map<String, Object> arguments, Path workspaceRoot) {
        return beforeToolCall(toolCallId, toolName, arguments, null, workspaceRoot);
    }

    public static String beforeToolCall(String toolCallId, String toolName,
                                        Map<String, Object> arguments, String rawArguments,
                                        Path workspaceRoot) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("toolCallId", safe(toolCallId));
        event.put("toolName", safe(toolName));
        event.put("targetRelativePath", relativeTarget(arguments));
        event.put("argumentKeys", arguments == null ? List.of() : arguments.keySet().stream().sorted().toList());
        event.put("argumentSha256", sha256(toJson(arguments)));
        event.put("modelArgumentsSha256", sha256(rawArguments));
        event.put("modelArgumentsChars", rawArguments == null ? 0 : rawArguments.length());
        event.put("modelArgumentsParseStatus", isJsonObject(rawArguments) ? "JSON_OBJECT" : "INVALID_OR_NON_OBJECT_JSON");
        event.put("editParameterSummary", editSummary(arguments));
        event.put("fileSha256Before", fileHash(workspaceRoot, relativeTarget(arguments)));
        LiveTestDiagnostics diagnostics = ACTIVE.get();
        if (diagnostics != null && diagnostics.captureEditContext) {
            event.put("editContext", safeEditContext(arguments));
        }
        record("tool_call_proposed", event);
        return fileHash(workspaceRoot, relativeTarget(arguments));
    }

    private static boolean isJsonObject(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) return false;
        try { return JSON.readTree(rawArguments).isObject(); }
        catch (JsonProcessingException exception) { return false; }
    }

    public static void afterToolCall(ToolCall call, Map<String, Object> arguments, ToolResult result,
                                     String fileHashBefore, Path workspaceRoot) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("toolCallId", safe(call.id()));
        event.put("toolName", safe(call.name()));
        event.put("targetRelativePath", relativeTarget(arguments));
        event.put("success", result.success());
        event.put("errorCode", result.errorCode() == null ? "" : result.errorCode().name());
        event.put("fileSha256Before", fileHashBefore == null ? "" : fileHashBefore);
        event.put("fileSha256After", fileHash(workspaceRoot, relativeTarget(arguments)));
        event.put("toolResultSha256", sha256(result.output() == null ? result.errorMessage() : result.output()));
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        for (String key : List.of("path", "changed", "editCount", "versionBefore", "versionAfter",
                "expectedVersion", "actualVersion")) {
            if (result.metadata().containsKey(key)) safeMetadata.put(key, result.metadata().get(key));
        }
        event.put("toolMetadata", safeMetadata);
        event.put("proposalVsApplied", Map.of(
                "proposedArgumentSha256", sha256(toJson(arguments)),
                "actualWholeFileSha256", fileHash(workspaceRoot, relativeTarget(arguments)),
                "comparison", "separate digests; edit payload is not equivalent to resulting whole-file bytes"));
        record("tool_result", event);
    }

    public static void recordVerification(VerificationResult result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("verifierType", result.verifierId());
        event.put("status", result.status().name());
        event.put("file", result.file().getFileName() == null ? "" : result.file().getFileName().toString());
        event.put("mutationSequence", result.mutationSequence());
        event.put("diagnostic", result.status() == VerificationStatus.FAIL
                ? bounded(redact(result.diagnosticSummary()), 2000) : "");
        event.put("unavailableReason", result.status() == VerificationStatus.UNAVAILABLE
                ? bounded(redact(result.unavailableReason()), 1000) : "");
        record("verification", event);
    }

    public static void recordVerificationFeedbackQueued(VerificationResult result) {
        record("verification_feedback_queued", Map.of(
                "file", result.file().getFileName() == null ? "" : result.file().getFileName().toString(),
                "verifierType", result.verifierId(),
                "status", result.status().name(),
                "diagnosticSha256", sha256(redact(result.diagnosticSummary())),
                "sentAsInternalHistoryMessage", true));
    }

    public static void recordRepair(String file, String toolName) {
        record("repair_followup", Map.of("file", safe(file), "toolName", safe(toolName)));
    }

    public static void recordCompletion(String terminationReason, boolean completed, String guard) {
        record("completion", Map.of("terminationReason", safe(terminationReason),
                "completed", completed, "completionGuard", safe(guard)));
    }

    public static boolean finishRun(String terminationReason, boolean completed, String guard) {
        recordCompletion(terminationReason, completed, guard);
        LiveTestDiagnostics diagnostics = ACTIVE.get();
        boolean healthy = diagnostics == null || diagnostics.failure == null;
        ACTIVE.remove();
        return healthy;
    }

    public static void recordProviderHttpResponse(int status) {
        record("http_response", Map.of("status", status));
    }

    public static void recordProviderFailure(Throwable failure) {
        String category = "IO_FAILURE";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.agent.llm.LiveTestControlException controlException) {
                category = controlException.code().name();
                break;
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                category = "TIMEOUT";
                break;
            }
            if (cause instanceof java.net.ConnectException) {
                category = "CONNECTION_FAILURE";
                break;
            }
            if (cause instanceof java.net.UnknownHostException) {
                category = "DNS_FAILURE";
                break;
            }
        }
        record("provider_failure", Map.of("category", category,
                "exceptionType", failure == null ? "" : failure.getClass().getSimpleName()));
    }

    public static void assertHealthy() throws IOException {
        LiveTestDiagnostics diagnostics = ACTIVE.get();
        if (diagnostics != null && diagnostics.failure != null) {
            throw new IOException("DIAGNOSTICS_WRITE_FAILED: trace is incomplete; no further request allowed.",
                    diagnostics.failure);
        }
    }

    private static void record(String type, Map<String, ?> fields) {
        LiveTestDiagnostics diagnostics = ACTIVE.get();
        if (diagnostics == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", type);
        event.put("timestamp", java.time.Instant.now().toString());
        event.putAll(fields);
        try {
            diagnostics.writeJson(event);
        } catch (IOException exception) {
            diagnostics.failure = exception;
        }
    }

    private synchronized void write(String type, Map<String, ?> fields) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", type);
        event.put("timestamp", java.time.Instant.now().toString());
        event.putAll(fields);
        writeJson(event);
    }

    private synchronized void writeJson(Map<String, ?> event) throws IOException {
        try {
            String line = JSON.writeValueAsString(event) + System.lineSeparator();
            java.nio.file.OpenOption[] options = Files.exists(traceFile, LinkOption.NOFOLLOW_LINKS)
                    ? new java.nio.file.OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS}
                    : new java.nio.file.OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW};
            Files.writeString(traceFile, line, StandardCharsets.UTF_8, options);
        } catch (JsonProcessingException exception) {
            throw new IOException("Unable to serialize live-test trace event", exception);
        }
    }

    private static String relativeTarget(Map<String, Object> arguments) {
        Object path = arguments == null ? null : arguments.get("path");
        if (!(path instanceof String value) || value.isBlank()) return "";
        try {
            Path parsed = Path.of(value);
            if (parsed.isAbsolute() || parsed.normalize().startsWith("..")) return "[REJECTED_PATH]";
            return parsed.normalize().toString().replace('\\', '/');
        } catch (RuntimeException exception) {
            return "[INVALID_PATH]";
        }
    }

    private static String fileHash(Path root, String relative) {
        if (root == null || relative == null || relative.isBlank()
                || relative.startsWith("[")) return "";
        try {
            Path normalizedRoot = root.toRealPath();
            Path target = normalizedRoot.resolve(relative).normalize();
            if (!target.startsWith(normalizedRoot) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return "";
            if (!target.toRealPath().startsWith(normalizedRoot)) return "";
            return sha256(Files.readAllBytes(target));
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static Map<String, Object> editSummary(Map<String, Object> arguments) {
        if (arguments == null) return Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("expectedContent", "content", "oldText", "newText", "anchor", "text")) {
            Object value = arguments.get(key);
            if (value instanceof String text) summary.put(key, Map.of("chars", text.length(), "sha256", sha256(text)));
        }
        Object edits = arguments.get("edits");
        if (edits instanceof List<?> list) {
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Object item : list) summaries.add(Map.of("sha256", sha256(toJson(item)),
                    "chars", toJson(item).length()));
            summary.put("edits", summaries);
        }
        return summary;
    }

    private static Map<String, Object> safeEditContext(Map<String, Object> arguments) {
        if (arguments == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("expectedContent", "content", "oldText", "newText", "anchor", "text", "edits")) {
            if (arguments.containsKey(key)) result.put(key, sanitizeContext(arguments.get(key)));
        }
        return result;
    }

    private static String toJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { return String.valueOf(value); }
    }

    private static String safeEndpoint(String value) {
        if (value == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(value);
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception exception) { return "[INVALID_ENDPOINT]"; }
    }

    private static boolean isIgnoredBenchmarkWorkspace(Path workspace) throws IOException {
        for (Path current = workspace; current != null; current = current.getParent()) {
            Path git = current.resolve(".git");
            Path ignore = current.resolve(".gitignore");
            if (Files.exists(git, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(ignore)) {
                String contents = Files.readString(ignore, StandardCharsets.UTF_8);
                boolean ignoreRule = contents.lines().map(String::trim)
                        .anyMatch(line -> !line.startsWith("#")
                                && (line.equals("benchmark-runs/") || line.equals("benchmark-runs")));
                if (!ignoreRule) return false;
                Path relative = current.relativize(workspace);
                for (Path part : relative) {
                    if ("benchmark-runs".equalsIgnoreCase(part.toString())) return true;
                }
                return false;
            }
        }
        return false;
    }

    private static Object sanitizeContext(Object value) {
        if (value instanceof String text) return redact(text);
        if (value instanceof List<?> list) return list.stream().map(LiveTestDiagnostics::sanitizeContext).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(String.valueOf(key), sanitizeContext(item)));
            return safe;
        }
        return value;
    }

    private static String redact(String value) {
        if (value == null) return "";
        String result = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=[REDACTED]");
        String glmKey = System.getenv("GLM_API_KEY");
        if (glmKey != null && !glmKey.isBlank()) result = result.replace(glmKey, "[REDACTED]");
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static boolean flag(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        return Boolean.parseBoolean(value);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "...[truncated]";
    }

    private static String sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
