package com.agent.memory;

public record SharedMemoryHit(LongTermMemory memory, double score) {
    public SharedMemoryHit {
        if (memory == null || !Double.isFinite(score) || score <= 0 || score > 1) throw new IllegalArgumentException("Invalid retrieval hit");
    }
    public Trace trace() { return new Trace(memory.memoryId(), score, (memory.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 3L) / 4L); }
    public record Trace(String memoryId, double relevanceScore, long contentEstimatedTokens) { }
}
