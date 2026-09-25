package com.agent;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small, process-local workspace reference handoff for the CLI profiles.
 * It deliberately keeps only tool-resolved relative paths, never model chat history.
 */
final class CliWorkingContext {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private List<String> lastResolvedFiles = List.of();
    private String lastResolvedFile;

    void observe(AgentTrajectory trajectory) {
        Objects.requireNonNull(trajectory, "trajectory must not be null");
        trajectory.steps().stream()
                .filter(step -> step.actionType() == AgentActionType.TOOL_CALL)
                .forEach(this::observe);
    }

    void clear() {
        lastResolvedFiles = List.of();
        lastResolvedFile = null;
    }

    List<String> lastResolvedFiles() {
        return lastResolvedFiles;
    }

    String lastResolvedFile() {
        return lastResolvedFile;
    }

    boolean hasContextualFileReference(String input) {
        String text = Objects.requireNonNull(input, "input must not be null").toLowerCase(java.util.Locale.ROOT);
        return text.matches("(?s).*?(这个|该|刚刚找到的|刚才那个|这些)\\s*(?:[a-z0-9+#.-]+\\s*)?文件.*")
                || text.contains("里面")
                || text.contains("this file");
    }

    String contextualPrompt() {
        if (lastResolvedFile != null) {
            return "Recent workspace context:\n"
                    + "The previous workspace operation resolved the referenced file to:\n"
                    + lastResolvedFile;
        }
        if (!lastResolvedFiles.isEmpty()) {
            return "Recent workspace context:\n"
                    + "The previous workspace operation resolved multiple candidate files: "
                    + String.join(", ", lastResolvedFiles) + ".\n"
                    + "Do not choose one arbitrarily; ask the user to clarify the target before reading or modifying a file.";
        }
        return "";
    }

    private void observe(AgentStep step) {
        ToolResult result = step.toolResult();
        if (result == null || !result.success()) {
            return;
        }
        switch (step.toolName()) {
            case "find_files" -> observeFindFiles(result);
            case "read_file", "create_file", "apply_patch", "insert_after" -> observePath(step.arguments(), result);
            default -> {
                // Other tools do not resolve a specific workspace file for cross-profile handoff.
            }
        }
    }

    private void observeFindFiles(ToolResult result) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.output());
            JsonNode files = root == null ? null : root.get("files");
            if (files == null || !files.isArray()) {
                return;
            }
            List<String> resolved = new ArrayList<>();
            for (JsonNode file : files) {
                if (file.isTextual() && !file.asText().isBlank()) {
                    resolved.add(file.asText());
                }
            }
            recordResolvedFiles(resolved);
        } catch (Exception ignored) {
            // A malformed successful observation must not manufacture a workspace target.
        }
    }

    private void observePath(Map<String, Object> arguments, ToolResult result) {
        Object candidate = result.metadata().getOrDefault("path", arguments.get("path"));
        if (candidate instanceof String path && !path.isBlank()) {
            recordResolvedFiles(List.of(path));
        }
    }

    private void recordResolvedFiles(List<String> files) {
        lastResolvedFiles = List.copyOf(files);
        lastResolvedFile = files.size() == 1 ? files.get(0) : null;
    }
}
