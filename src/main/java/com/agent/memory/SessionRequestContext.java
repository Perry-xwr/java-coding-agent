package com.agent.memory;

import com.agent.CliMode;
import java.util.Objects;

public record SessionRequestContext(String sessionId, CliMode profile, SessionSummary summary, SessionContextConfig config, CrossSessionState crossState, java.util.List<SharedMemoryHit> sharedHits) {
    public SessionRequestContext(String id, CliMode profile, SessionSummary summary, SessionContextConfig config) {
        this(id, profile, summary, config, CrossSessionState.off(), java.util.List.of());
    }
    public SessionRequestContext {
        crossState = crossState == null ? CrossSessionState.off() : crossState;
        sharedHits = sharedHits == null || !crossState.enabled() ? java.util.List.of() : java.util.List.copyOf(sharedHits);
        Objects.requireNonNull(sessionId); Objects.requireNonNull(profile); Objects.requireNonNull(config);
        summary = summary == null || !sessionId.equals(summary.sessionId()) ? SessionSummary.empty() : summary;
    }
}
