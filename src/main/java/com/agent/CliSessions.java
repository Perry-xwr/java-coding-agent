package com.agent;

import com.agent.agent.Agent;

import java.util.Objects;

public record CliSessions(Agent chat, Agent read, Agent code) {
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
}
