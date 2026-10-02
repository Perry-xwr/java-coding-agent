package com.agent;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, process-local working memory shared by the CLI READ and CODE profiles.
 *
 * <p>Only user input, successful tool observations, and typed tool failures enter this object.
 * It intentionally does not retain model prose, full file contents, or runtime-local safety state
 * from {@code AgentProgress}.</p>
 */
public final class CliWorkingContext {
    static final int MAX_DISCOVERED_FILES = 20;
    static final int MAX_VERIFIED_FACTS = 30;
    static final int MAX_RECENT_FAILURES = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern EXPLICIT_FILE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])((?:[A-Za-z0-9_.-]+[\\\\/])*[A-Za-z0-9_.-]+"
                    + "\\.(?:java|py|md|cpp|c|h|hpp|json|ya?ml|xml|properties|txt)|README(?:\\.md)?)"
    );

    private String activeTask;
    private List<String> explicitTargetFiles = List.of();
    private List<String> lastResolvedFiles = List.of();
    private String lastResolvedFile;
    private final LinkedHashSet<String> discoveredFiles = new LinkedHashSet<>();
    private final Deque<VerifiedFileFact> verifiedFileFacts = new ArrayDeque<>();
    private final Deque<ToolFailure> recentToolFailures = new ArrayDeque<>();
    private LastMutation lastMutation;

    /** Records the raw current user task and any explicitly named workspace-relative file targets. */
    public void observeUserTask(String task) {
        activeTask = requireNonBlank(task, "task must not be blank");
        List<String> targets = explicitTargets(task);
        if (!targets.isEmpty()) {
            explicitTargetFiles = targets;
        }
    }

    /** Observes all typed tool observations in a completed run, including runtime auto-rereads. */
    public void observe(AgentTrajectory trajectory) {
        Objects.requireNonNull(trajectory, "trajectory must not be null");
        trajectory.steps().forEach(this::observe);
    }

    /** Centralized trusted observation entry point for individual tool results. */
    public void observeToolResult(String toolName, Map<String, Object> arguments, ToolResult result) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(result, "result must not be null");

        if (!result.success()) {
            recordFailure(toolName, pathFrom(arguments, result), result.errorCode());
            return;
        }

        switch (toolName) {
            case "find_files" -> observeFindFiles(result);
            case "list_files" -> observeListFiles(result);
            case "read_file" -> observeRead(pathFrom(arguments, result));
            case "create_file" -> observeMutation(pathFrom(arguments, result), toolName, VerifiedFileFactType.FILE_CREATED);
            case "apply_patch", "insert_before", "insert_after", "replace_lines" ->
                    observeMutation(pathFrom(arguments, result), toolName, VerifiedFileFactType.FILE_MUTATED);
            default -> {
                // Tool output without a file fact is intentionally not promoted into memory.
            }
        }
    }

    public void clear() {
        activeTask = null;
        explicitTargetFiles = List.of();
        lastResolvedFiles = List.of();
        lastResolvedFile = null;
        discoveredFiles.clear();
        verifiedFileFacts.clear();
        recentToolFailures.clear();
        lastMutation = null;
    }

    public String activeTask() {
        return activeTask;
    }

    public List<String> explicitTargetFiles() {
        return explicitTargetFiles;
    }

    public List<String> discoveredFiles() {
        return List.copyOf(discoveredFiles);
    }

    public List<VerifiedFileFact> verifiedFileFacts() {
        return List.copyOf(verifiedFileFacts);
    }

    public List<ToolFailure> recentToolFailures() {
        return List.copyOf(recentToolFailures);
    }

    public LastMutation lastMutation() {
        return lastMutation;
    }

    /** Latest tool-resolved candidates, retained for contextual routing. */
    public List<String> lastResolvedFiles() {
        return lastResolvedFiles;
    }

    public String lastResolvedFile() {
        return lastResolvedFile;
    }

    public boolean hasWorkspaceFacts() {
        return !discoveredFiles.isEmpty()
                || !explicitTargetFiles.isEmpty()
                || lastMutation != null
                || !recentToolFailures.isEmpty();
    }

    public boolean hasContextualFileReference(String input) {
        String text = Objects.requireNonNull(input, "input must not be null").toLowerCase(Locale.ROOT);
        return text.matches("(?s).*?(这个|该|刚刚找到的|刚才那个|这些)\\s*(?:[a-z0-9+#.-]+\\s*)?文件.*")
                || text.contains("里面")
                || text.contains("this file");
    }

    /** A concise deterministic prompt fragment; no model answer or full file text is included. */
    public String compactSnapshot() {
        List<String> lines = new ArrayList<>();
        lines.add("Working memory:");
        if (activeTask != null) {
            lines.add("- Active task: " + abbreviate(activeTask));
        }
        boolean hasExplicitTarget = !explicitTargetFiles.isEmpty();
        if (hasExplicitTarget) {
            lines.add("- Explicit target: " + String.join(", ", explicitTargetFiles)
                    + " (overrides historical candidates)");
        }
        if (!hasExplicitTarget && !lastResolvedFiles.isEmpty()) {
            lines.add("- Current candidates: " + String.join(", ", lastResolvedFiles));
            if (lastResolvedFile == null) {
                lines.add("- Do not choose a candidate arbitrarily; ask for clarification when a target is required.");
            }
        }
        if (!hasExplicitTarget && !discoveredFiles.isEmpty()) {
            lines.add("- Known files: " + joinLimited(discoveredFiles, 6));
        }
        if (!verifiedFileFacts.isEmpty()) {
            lines.add("- Verified facts: " + joinFacts(verifiedFileFacts, 6));
        }
        if (lastMutation != null) {
            lines.add("- Last mutation: " + lastMutation.tool() + "(" + lastMutation.path() + ")");
        }
        if (!recentToolFailures.isEmpty()) {
            ToolFailure failure = recentToolFailures.getLast();
            lines.add("- Recent tool failure: " + failure.tool() + "(" + failure.path() + ") -> "
                    + failure.errorCode());
        }
        return lines.size() == 1 ? "" : String.join("\n", lines);
    }

    public String contextualPrompt() {
        return compactSnapshot();
    }

    private void observe(AgentStep step) {
        if (step.toolResult() == null || step.toolName() == null) {
            return;
        }
        if (step.actionType() == AgentActionType.TOOL_CALL || step.actionType() == AgentActionType.AUTO_REREAD) {
            observeToolResult(step.toolName(), step.arguments(), step.toolResult());
        }
    }

    private void observeFindFiles(ToolResult result) {
        List<String> files = parseFiles(result.output(), true);
        if (!files.isEmpty()) {
            recordResolvedFiles(files);
        }
    }

    private void observeListFiles(ToolResult result) {
        List<String> files = parseFiles(result.output(), false);
        if (!files.isEmpty()) {
            recordResolvedFiles(files);
        }
    }

    private List<String> parseFiles(String output, boolean wrapped) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(output);
            JsonNode files = wrapped && root != null ? root.get("files") : root;
            if (files == null || !files.isArray()) {
                return List.of();
            }
            List<String> parsed = new ArrayList<>();
            for (JsonNode file : files) {
                if (file.isTextual()) {
                    normalizedRelativePath(file.asText()).ifPresent(parsed::add);
                }
            }
            return parsed.stream().distinct().limit(MAX_DISCOVERED_FILES).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void observeRead(String path) {
        if (path == null) {
            return;
        }
        recordResolvedFiles(List.of(path));
        addFact(new VerifiedFileFact(VerifiedFileFactType.FILE_EXISTS, path));
        addFact(new VerifiedFileFact(VerifiedFileFactType.FILE_READ, path));
    }

    private void observeMutation(String path, String toolName, VerifiedFileFactType factType) {
        if (path == null) {
            return;
        }
        recordResolvedFiles(List.of(path));
        addFact(new VerifiedFileFact(VerifiedFileFactType.FILE_EXISTS, path));
        addFact(new VerifiedFileFact(factType, path));
        lastMutation = new LastMutation(path, toolName);
    }

    private void recordResolvedFiles(List<String> files) {
        List<String> normalized = files.stream()
                .map(CliWorkingContext::normalizedRelativePath)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .limit(MAX_DISCOVERED_FILES)
                .toList();
        if (normalized.isEmpty()) {
            return;
        }
        lastResolvedFiles = normalized;
        lastResolvedFile = normalized.size() == 1 ? normalized.get(0) : null;
        normalized.forEach(this::addDiscoveredFile);
    }

    private void addDiscoveredFile(String path) {
        discoveredFiles.remove(path);
        discoveredFiles.add(path);
        while (discoveredFiles.size() > MAX_DISCOVERED_FILES) {
            discoveredFiles.remove(discoveredFiles.iterator().next());
        }
    }

    private void addFact(VerifiedFileFact fact) {
        verifiedFileFacts.remove(fact);
        verifiedFileFacts.addLast(fact);
        while (verifiedFileFacts.size() > MAX_VERIFIED_FACTS) {
            verifiedFileFacts.removeFirst();
        }
    }

    private void recordFailure(String toolName, String path, ToolErrorCode errorCode) {
        if (errorCode == null) {
            return;
        }
        recentToolFailures.addLast(new ToolFailure(toolName, path, errorCode));
        while (recentToolFailures.size() > MAX_RECENT_FAILURES) {
            recentToolFailures.removeFirst();
        }
    }

    private static String pathFrom(Map<String, Object> arguments, ToolResult result) {
        Object candidate = result.metadata().getOrDefault("path", arguments.get("path"));
        return candidate instanceof String path ? normalizedRelativePath(path).orElse(null) : null;
    }

    private static List<String> explicitTargets(String task) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher matcher = EXPLICIT_FILE_PATTERN.matcher(task);
        while (matcher.find()) {
            normalizedRelativePath(matcher.group(1)).ifPresent(targets::add);
        }
        return List.copyOf(targets);
    }

    private static java.util.Optional<String> normalizedRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = path.replace('\\', '/').strip();
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            return java.util.Optional.empty();
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..") || part.isEmpty()) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(normalized);
    }

    private static String joinLimited(Iterable<String> values, int limit) {
        List<String> visible = new ArrayList<>();
        int total = 0;
        for (String value : values) {
            total++;
            if (visible.size() < limit) {
                visible.add(value);
            }
        }
        return String.join(", ", visible) + (total > limit ? " (and " + (total - limit) + " more)" : "");
    }

    private static String joinFacts(Iterable<VerifiedFileFact> facts, int limit) {
        List<String> visible = new ArrayList<>();
        int total = 0;
        for (VerifiedFileFact fact : facts) {
            total++;
            if (visible.size() < limit) {
                visible.add(fact.type() + "(" + fact.path() + ")");
            }
        }
        return String.join(", ", visible) + (total > limit ? " (and " + (total - limit) + " more)" : "");
    }

    private static String abbreviate(String value) {
        String firstLine = value.lines().filter(line -> !line.isBlank()).findFirst().orElse(value);
        String compact = firstLine.replaceAll("\\s+", " ").strip();
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public enum VerifiedFileFactType {
        FILE_EXISTS,
        FILE_READ,
        FILE_CREATED,
        FILE_MUTATED
    }

    public record VerifiedFileFact(VerifiedFileFactType type, String path) {
        public VerifiedFileFact {
            Objects.requireNonNull(type, "type must not be null");
            path = requireNonBlank(path, "path must not be blank");
        }
    }

    public record ToolFailure(String tool, String path, ToolErrorCode errorCode) {
        public ToolFailure {
            tool = requireNonBlank(tool, "tool must not be blank");
            path = path == null ? "<unknown>" : path;
            Objects.requireNonNull(errorCode, "errorCode must not be null");
        }
    }

    public record LastMutation(String path, String tool) {
        public LastMutation {
            path = requireNonBlank(path, "path must not be blank");
            tool = requireNonBlank(tool, "tool must not be blank");
        }
    }
}
