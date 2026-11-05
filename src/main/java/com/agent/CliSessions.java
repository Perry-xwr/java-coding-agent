package com.agent;

import com.agent.agent.Agent;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import com.agent.llm.Message;

public record CliSessions(Agent chat, Agent read, Agent code, com.agent.llm.LLMClient extractionClient) {
    public CliSessions(Agent chat, Agent read, Agent code) { this(chat, read, code, null); }
    public CliSessions {
        Objects.requireNonNull(chat, "chat must not be null");
        Objects.requireNonNull(read, "read must not be null");
        Objects.requireNonNull(code, "code must not be null");
    }

    public Agent session(CliMode mode) {
        return switch (Objects.requireNonNull(mode, "mode must not be null")) {
            case CHAT -> chat;
            case READ -> read;
            case CODE -> code;
            case AUTO -> throw new IllegalArgumentException("AUTO routing is not enabled");
        };
    }

    public void clearAll() {
        chat.clearHistory();
        read.clearHistory();
        code.clearHistory();
    }

    public Map<CliMode, List<Message>> histories() {
        return Map.of(CliMode.CHAT, chat.history(), CliMode.READ, read.history(), CliMode.CODE, code.history());
    }

    public boolean hasConversationHistory() {
        return chat.history().size() > 1 || read.history().size() > 1 || code.history().size() > 1;
    }

    public void validateHistories(Map<CliMode, List<Message>> histories) {
        Objects.requireNonNull(histories, "histories must not be null");
        if (!histories.keySet().equals(java.util.Set.of(CliMode.CHAT, CliMode.READ, CliMode.CODE))) {
            throw new IllegalArgumentException("Saved session must contain all three profile histories");
        }
        chat.validateRestoredHistory(histories.get(CliMode.CHAT));
        read.validateRestoredHistory(histories.get(CliMode.READ));
        code.validateRestoredHistory(histories.get(CliMode.CODE));
    }

    /** Call only after all three replacement histories have passed validation. */
    public void restoreHistories(Map<CliMode, List<Message>> histories) {
        validateHistories(histories);
        chat.restoreHistory(histories.get(CliMode.CHAT));
        read.restoreHistory(histories.get(CliMode.READ));
        code.restoreHistory(histories.get(CliMode.CODE));
    }
}
