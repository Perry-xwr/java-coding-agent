package com.agent.llm;

import java.util.List;

/** Per-agent-turn request records and exact aggregates; a null aggregate means at least one value is unknown. */
public record ModelRequestUsageSummary(
        List<ModelRequestUsage> requests,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Integer cacheHitTokens,
        Integer cacheMissTokens,
        long maxPayloadBytes,
        boolean usageComplete
) {
    public ModelRequestUsageSummary {
        requests = List.copyOf(requests);
    }

    public static ModelRequestUsageSummary empty() {
        return new ModelRequestUsageSummary(List.of(), null, null, null, null, null, 0, false);
    }

    static ModelRequestUsageSummary from(List<ModelRequestUsage> requests) {
        if (requests.isEmpty()) return empty();
        boolean complete = requests.stream().allMatch(r -> "SUCCEEDED".equals(r.status())
                && r.inputTokens() != null && r.outputTokens() != null && r.totalTokens() != null);
        return new ModelRequestUsageSummary(requests,
                sumIfAllKnown(requests, ModelRequestUsage::inputTokens),
                sumIfAllKnown(requests, ModelRequestUsage::outputTokens),
                sumIfAllKnown(requests, ModelRequestUsage::totalTokens),
                sumIfAllKnown(requests, ModelRequestUsage::cacheHitTokens),
                sumIfAllKnown(requests, ModelRequestUsage::cacheMissTokens),
                requests.stream().mapToLong(r -> r.context().payloadBytes()).max().orElse(0), complete);
    }

    private static Integer sumIfAllKnown(List<ModelRequestUsage> requests,
                                         java.util.function.Function<ModelRequestUsage, Integer> getter) {
        long total = 0;
        for (ModelRequestUsage request : requests) {
            Integer value = getter.apply(request);
            if (value == null) return null;
            total += value;
        }
        return total > Integer.MAX_VALUE ? null : (int) total;
    }
}
