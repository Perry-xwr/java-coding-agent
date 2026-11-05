package com.agent.agent;

import java.util.List;
import java.util.Objects;

/** Read-only, bounded task-scoped change preview derived from actual mutation tool execution. */
public record TaskChangeReview(String runId, List<FileChange> changedFiles, int totalDiffCharacters,
                               int maxDiffCharacters, boolean diffComplete) {
    public TaskChangeReview {
        Objects.requireNonNull(runId, "runId must not be null");
        changedFiles = List.copyOf(changedFiles);
    }

    public static TaskChangeReview empty(String runId) {
        return new TaskChangeReview(runId, List.of(), 0, TaskDiffCollector.MAX_TOTAL_DIFF_CHARS, true);
    }

    public record FileChange(String path, ChangeType type, Attribution attribution,
                             String beforeSha256, String afterSha256, String diff,
                             boolean diffComplete, String status, List<String> toolCallIds,
                             String verificationStatus) {
        public FileChange {
            Objects.requireNonNull(path);
            Objects.requireNonNull(type);
            Objects.requireNonNull(attribution);
            Objects.requireNonNull(status);
            toolCallIds = List.copyOf(toolCallIds);
            verificationStatus = verificationStatus == null ? "NOT_RUN" : verificationStatus;
        }
    }

    public enum ChangeType { ADDED, MODIFIED, DELETED, UNKNOWN }
    public enum Attribution { TOOL_EXECUTION, UNCERTAIN }
}
