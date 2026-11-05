package com.agent.llm;

/** One provider request/exchange record. Token values are null unless explicitly returned by the server. */
public record ModelRequestUsage(
        long requestSequence,
        String provider,
        String model,
        String endpoint,
        String runId,
        String profile,
        long agentTurn,
        String status,
        Integer httpStatus,
        boolean usageAvailable,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Integer cacheHitTokens,
        Integer cacheMissTokens,
        RequestContextMetrics context
) {
    public ModelRequestUsage withResult(String nextStatus, Integer nextHttpStatus, ProviderTokenUsage usage) {
        ProviderTokenUsage value = usage == null ? ProviderTokenUsage.unknown() : usage;
        return new ModelRequestUsage(requestSequence, provider, model, endpoint, runId, profile, agentTurn,
                nextStatus, nextHttpStatus, value.usageAvailable(), value.inputTokens(), value.outputTokens(),
                value.totalTokens(), value.cacheHitTokens(), value.cacheMissTokens(), context);
    }
}
