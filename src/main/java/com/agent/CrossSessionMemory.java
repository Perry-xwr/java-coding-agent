package com.agent;

import java.util.List;

/** Explicit-consent long-term memory boundary; OFF must not touch shared persistence. */
public interface CrossSessionMemory {
    boolean enabled();
    List<String> retrieve(String sessionId, String query);
    void saveAuthorized(String sessionId, String text);
    default List<com.agent.memory.SharedMemoryHit> retrieveAuthorized(String sessionId, String query) throws java.io.IOException {
        return List.of();
    }
    default com.agent.memory.LongTermMemory rememberAuthorized(String sessionId, String text) throws java.io.IOException {
        throw new IllegalStateException("Cross-session memory is OFF");
    }
    default List<com.agent.memory.LongTermMemory> listAuthorized() throws java.io.IOException {
        throw new IllegalStateException("Cross-session memory is OFF");
    }
    default com.agent.memory.LongTermMemory updateAuthorized(String memoryId, String text) throws java.io.IOException {
        throw new IllegalStateException("Cross-session memory is OFF");
    }
    default void forgetAuthorized(String memoryId) throws java.io.IOException {
        throw new IllegalStateException("Cross-session memory is OFF");
    }

    static CrossSessionMemory disabled() { return Disabled.INSTANCE; }

    enum Disabled implements CrossSessionMemory {
        INSTANCE;
        public boolean enabled() { return false; }
        public List<String> retrieve(String sessionId, String query) { return List.of(); }
        public void saveAuthorized(String sessionId, String text) {
            throw new IllegalStateException("Cross-session memory is OFF");
        }
    }
}
