package com.agent.memory;

import com.agent.CliMode;
import com.agent.agent.AgentRunResult;
import com.agent.agent.TaskEvidence;
import com.agent.llm.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Quotes whole eligible user messages. Assistant prose never becomes a preference or success fact. */
public final class ExtractiveSessionSummarizer implements SessionSummarizer {
    private static final Pattern CONSTRAINT = Pattern.compile("(?i)(\\bmust\\b|do not|\\bnever\\b|constraints?:|不要|必须|禁止|仅允许|约束|要求)");
    private static final Pattern DECISION = Pattern.compile("(?i)(decision:|\\bdecided\\b|决定|已确认)");
    private static final Pattern PENDING = Pattern.compile("(?i)(\\bTODO\\b|unfinished|未完成|待办)");

    @Override
    public SessionSummary summarize(String id, Map<CliMode, List<Message>> histories, SessionSummary previous) {
        SessionSummary old = previous != null && id.equals(previous.sessionId()) ? previous : SessionSummary.empty();
        List<SessionSummary.Fact> facts = new ArrayList<>();
        boolean overflow = old.overflowed() && histories.values().stream()
                .anyMatch(messages -> messages.stream().anyMatch(message -> "user".equals(message.role())));
        for (CliMode profile : List.of(CliMode.CHAT, CliMode.READ, CliMode.CODE)) {
            List<Message> history = histories.getOrDefault(profile, List.of());
            for (int index = 1; index < history.size(); index++) {
                Message message = history.get(index);
                if (!"user".equals(message.role()) || message.content().isBlank() || message.content().length() > 4096) continue;
                if (facts.size() == SessionSummary.MAX_ENTRIES) { overflow = true; break; }
                facts.add(new SessionSummary.Fact(profile, index, SessionSummary.digest(message.content()),
                        kind(message.content()), message.content()));
            }
        }
        List<SessionSummary.TaskState> tasks = old.tasks().stream().filter(task ->
                SessionSummary.sourceMatches(histories.getOrDefault(task.profile(), List.of()), task.userMessageIndex(), task.userSha256())).toList();
        Map<CliMode, Integer> boundaries = new java.util.EnumMap<>(CliMode.class);
        old.compactThrough().forEach((profile, end) -> {
            if (histories.getOrDefault(profile, List.of()).size() >= end) boundaries.put(profile, end);
        });
        return new SessionSummary(1, id, old.manualCompact(), overflow, facts, tasks, boundaries);
    }

    public SessionSummary recordTask(String id, CliMode profile, Map<CliMode, List<Message>> histories,
                                     SessionSummary previous, AgentRunResult result) {
        SessionSummary refreshed = summarize(id, histories, previous);
        List<Message> history = histories.get(profile);
        int index = -1;
        for (int i = history.size() - 1; i >= 1; i--) {
            if ("user".equals(history.get(i).role()) && history.get(i).content().equals(result.trajectory().task())) { index = i; break; }
        }
        if (index < 0) return refreshed;
        TaskEvidence evidence = result.evidence();
        List<SessionSummary.TaskState> tasks = new ArrayList<>(refreshed.tasks());
        final int userIndex = index;
        tasks.removeIf(task -> task.profile() == profile && task.userMessageIndex() == userIndex);
        tasks.add(new SessionSummary.TaskState(profile, index, SessionSummary.digest(history.get(index).content()),
                result.trajectory().runId(), result.trajectory().completed(), evidence.executionStatus().name(),
                evidence.mutationStatus().name(), evidence.verificationKind() + "=" + evidence.verificationStatus(),
                evidence.functionalVerification().name(), evidence.changedFiles().stream().map(file -> file.path()).limit(64).toList(),
                evidence.evidenceSources().stream().map(source -> source.eventType() + ":" + source.name()
                        + "#" + source.eventId() + "=" + source.status()).limit(64).toList()));
        boolean overflow = refreshed.overflowed() || tasks.size() > SessionSummary.MAX_ENTRIES;
        if (tasks.size() > SessionSummary.MAX_ENTRIES) tasks = new ArrayList<>(tasks.subList(tasks.size() - SessionSummary.MAX_ENTRIES, tasks.size()));
        return new SessionSummary(1, id, refreshed.manualCompact(), overflow, refreshed.facts(), tasks, refreshed.compactThrough());
    }

    public static SessionSummary.Kind kind(String text) {
        if (CONSTRAINT.matcher(text).find()) return SessionSummary.Kind.EXPLICIT_CONSTRAINT;
        if (DECISION.matcher(text).find()) return SessionSummary.Kind.EXPLICIT_DECISION;
        if (PENDING.matcher(text).find()) return SessionSummary.Kind.PENDING_REQUEST;
        return SessionSummary.Kind.USER_TASK;
    }
}
