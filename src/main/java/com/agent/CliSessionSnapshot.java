package com.agent;

import com.agent.llm.Message;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned, provider-independent state needed to resume the existing CLI profiles. */
public record CliSessionSnapshot(
        int version,
        String savedAt,
        CliMode activeMode,
        CliMode lastAutoRoutedMode,
        Map<CliMode, List<Message>> histories,
        CliWorkingContext.Snapshot workingContext,
        com.agent.memory.SessionSummary summary,
        com.agent.memory.CrossSessionState crossSessionMemory,
        com.agent.memory.ExtractionState extraction
) implements SessionMemory {
    public static final int CURRENT_VERSION = 1;
    public CliSessionSnapshot(int version,String savedAt,CliMode activeMode,CliMode lastAutoRoutedMode,
            Map<CliMode,List<Message>> histories,CliWorkingContext.Snapshot workingContext,
            com.agent.memory.SessionSummary summary,com.agent.memory.CrossSessionState crossSessionMemory) {
        this(version,savedAt,activeMode,lastAutoRoutedMode,histories,workingContext,summary,crossSessionMemory,com.agent.memory.ExtractionState.empty());
    }


    public CliSessionSnapshot withSummary(com.agent.memory.SessionSummary replacement) {
        return new CliSessionSnapshot(version, savedAt, activeMode, lastAutoRoutedMode, histories, workingContext, replacement, crossSessionMemory, extraction);
    }

    public CliSessionSnapshot(int version, String savedAt, CliMode activeMode, CliMode lastAutoRoutedMode,
                              Map<CliMode, List<Message>> histories, CliWorkingContext.Snapshot workingContext) {
        this(version, savedAt, activeMode, lastAutoRoutedMode, histories, workingContext,
                com.agent.memory.SessionSummary.empty(), com.agent.memory.CrossSessionState.off());
    }
    public CliSessionSnapshot(int version, String savedAt, CliMode activeMode, CliMode lastAutoRoutedMode,
                              Map<CliMode, List<Message>> histories, CliWorkingContext.Snapshot workingContext,
                              com.agent.memory.SessionSummary summary) {
        this(version, savedAt, activeMode, lastAutoRoutedMode, histories, workingContext, summary, com.agent.memory.CrossSessionState.off());
    }

    public CliSessionSnapshot {
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        Objects.requireNonNull(activeMode, "activeMode must not be null");
        Objects.requireNonNull(histories, "histories must not be null");
        Objects.requireNonNull(workingContext, "workingContext must not be null");
        extraction = extraction == null ? com.agent.memory.ExtractionState.empty() : extraction;
        summary = summary == null ? com.agent.memory.SessionSummary.empty() : summary;
        crossSessionMemory = crossSessionMemory == null ? com.agent.memory.CrossSessionState.off() : crossSessionMemory;
        EnumMap<CliMode, List<Message>> copy = new EnumMap<>(CliMode.class);
        histories.forEach((mode, messages) -> copy.put(
                Objects.requireNonNull(mode, "history mode must not be null"),
                List.copyOf(Objects.requireNonNull(messages, "history messages must not be null"))));
        histories = Map.copyOf(copy);
        crossSessionMemory.validateHistories(histories);
    }
}
