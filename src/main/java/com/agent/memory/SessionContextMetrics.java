package com.agent.memory;

import java.util.List;

/** Message/schema estimates only. Provider-reported usage remains in ModelRequestUsage. */
public record SessionContextMetrics(String sessionId, String profile, int fullHistoryMessages,
                                    int selectedHistoryMessages, long fullEstimatedTokens,
                                    long selectedEstimatedTokens, int budgetEstimatedTokens,
                                    boolean compacted, boolean softBudgetExceeded, String reason,
                                    List<String> injectedKinds, List<SharedMemoryHit.Trace> sharedMemories) {
    public SessionContextMetrics(String id, String profile, int stored, int selected, long full, long estimate, int budget, boolean compacted, boolean exceeded, String reason, List<String> kinds) {
        this(id, profile, stored, selected, full, estimate, budget, compacted, exceeded, reason, kinds, List.of());
    }
    public SessionContextMetrics { injectedKinds = List.copyOf(injectedKinds); sharedMemories = List.copyOf(sharedMemories); }
    public double estimatedSavingRatio() {
        return fullEstimatedTokens == 0 ? 0 : 1.0 - (double) selectedEstimatedTokens / fullEstimatedTokens;
    }
}
