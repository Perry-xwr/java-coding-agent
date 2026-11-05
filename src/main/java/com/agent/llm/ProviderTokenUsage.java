package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** Token counts explicitly returned by a provider. Null means that field was not supplied. */
public record ProviderTokenUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Integer cacheHitTokens,
        Integer cacheMissTokens,
        boolean usageAvailable
) {
    public static ProviderTokenUsage unknown() {
        return new ProviderTokenUsage(null, null, null, null, null, false);
    }

    public static ProviderTokenUsage fromJson(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return unknown();
        }
        return new ProviderTokenUsage(integer(usage, "prompt_tokens"),
                integer(usage, "completion_tokens"), integer(usage, "total_tokens"),
                integer(usage, "prompt_cache_hit_tokens"), integer(usage, "prompt_cache_miss_tokens"), true);
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }
}
