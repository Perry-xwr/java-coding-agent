package com.agent.memory;

import com.agent.CliMode;
import com.agent.llm.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Extracted historical data, not trusted instructions or evidence of current workspace correctness. */
public record SessionSummary(int version, String sessionId, boolean manualCompact, boolean overflowed,
                             List<Fact> facts, List<TaskState> tasks, Map<CliMode, Integer> compactThrough) {
    public static final int MAX_ENTRIES = 256;
    public SessionSummary {
        if (version != 1 || sessionId == null || (!sessionId.isEmpty() && !java.util.UUID.fromString(sessionId).toString().equals(sessionId))) {
            throw new IllegalArgumentException("Invalid memory version/scope");
        }
        facts = facts == null ? List.of() : List.copyOf(facts);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        compactThrough = compactThrough == null ? Map.of() : Map.copyOf(compactThrough);
        if (compactThrough.entrySet().stream().anyMatch(entry -> entry.getKey() == CliMode.AUTO || entry.getValue() < 1)) {
            throw new IllegalArgumentException("Invalid manual compaction boundary");
        }
        if (facts.size() > MAX_ENTRIES || tasks.size() > MAX_ENTRIES) throw new IllegalArgumentException("Memory exceeds bounds");
    }
    public SessionSummary(int version, String sessionId, boolean manualCompact, boolean overflowed, List<Fact> facts, List<TaskState> tasks) {
        this(version, sessionId, manualCompact, overflowed, facts, tasks, Map.of());
    }
    public static SessionSummary empty() { return new SessionSummary(1, "", false, false, List.of(), List.of()); }
    public SessionSummary compactManually(Map<CliMode, List<Message>> histories) {
        Map<CliMode, Integer> boundaries = new java.util.EnumMap<>(CliMode.class);
        histories.forEach((mode, messages) -> boundaries.put(mode, messages.size()));
        return new SessionSummary(1, sessionId, true, overflowed, facts, tasks, boundaries);
    }

    public enum Kind { USER_TASK, EXPLICIT_CONSTRAINT, EXPLICIT_DECISION, PENDING_REQUEST }
    public record Fact(CliMode profile, int messageIndex, String sourceSha256, Kind kind, String text) {
        public Fact {
            if (profile == null || profile == CliMode.AUTO || messageIndex < 1 || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")
                    || kind == null || text == null || text.isBlank() || text.length() > 4096) {
                throw new IllegalArgumentException("Invalid extracted memory fact");
            }
        }
    }
    public record TaskState(CliMode profile, int userMessageIndex, String userSha256, String runId,
                            boolean completed, String execution, String mutation, String verification,
                            String functional, List<String> changedPaths, List<String> evidenceSources) {
        public TaskState {
            if (profile == null || profile == CliMode.AUTO || userMessageIndex < 1 || userSha256 == null
                    || runId == null || execution == null || mutation == null || verification == null || functional == null) {
                throw new IllegalArgumentException("Invalid task memory source");
            }
            if (!userSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid task source digest");
            java.util.UUID.fromString(runId);
            com.agent.agent.TaskEvidence.ExecutionStatus.valueOf(execution);
            com.agent.agent.TaskEvidence.MutationStatus.valueOf(mutation);
            com.agent.agent.TaskEvidence.EvidenceStatus.valueOf(functional);
            String[] verificationParts = verification.split("=", -1);
            if (verificationParts.length != 2) throw new IllegalArgumentException("Invalid historical verification status");
            com.agent.agent.TaskEvidence.VerificationKind.valueOf(verificationParts[0]);
            com.agent.agent.TaskEvidence.EvidenceStatus.valueOf(verificationParts[1]);
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
            evidenceSources = evidenceSources == null ? List.of() : List.copyOf(evidenceSources);
            if (changedPaths.size() > 64 || evidenceSources.size() > 64) throw new IllegalArgumentException("Task memory exceeds bounds");
        }
    }

    public static boolean sourceMatches(List<Message> history, int index, String sha) {
        return index >= 1 && index < history.size() && "user".equals(history.get(index).role())
                && digest(history.get(index).content()).equals(sha);
    }
    public static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
