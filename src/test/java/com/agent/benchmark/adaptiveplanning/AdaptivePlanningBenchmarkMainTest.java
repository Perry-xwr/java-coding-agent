package com.agent.benchmark.adaptiveplanning;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptivePlanningBenchmarkMainTest {
    @Test
    void missingAllowRealRejectsBeforeProviderConstruction() {
        AtomicInteger constructed = new AtomicInteger();

        assertThrows(IllegalArgumentException.class,
                () -> AdaptivePlanningBenchmarkMain.createProvider(new String[0], () -> {
                    constructed.incrementAndGet();
                    return messages -> { throw new AssertionError("provider must not be called"); };
                }));

        assertEquals(0, constructed.get());
    }

    @Test
    void onlyExplicitTrueAllowsProviderConstruction() {
        AtomicInteger constructed = new AtomicInteger();
        var client = AdaptivePlanningBenchmarkMain.createProvider(
                new String[]{"--allow-real=true"}, () -> {
                    constructed.incrementAndGet();
                    return messages -> null;
                });
        assertNotNull(client);
        assertEquals(1, constructed.get());
        assertThrows(IllegalArgumentException.class, () ->
                AdaptivePlanningBenchmarkMain.createProvider(new String[]{"--allow-real=false"}, () -> {
                    throw new AssertionError("provider must not be called");
                }));
    }
}
