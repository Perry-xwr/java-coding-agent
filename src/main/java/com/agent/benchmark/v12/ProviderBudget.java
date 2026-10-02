package com.agent.benchmark.v12;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProviderBudget {
    private final int runCap;
    private final AtomicInteger used = new AtomicInteger();

    public ProviderBudget(int runCap) {
        if (runCap < 1) throw new IllegalArgumentException("runCap must be positive");
        this.runCap = runCap;
    }

    public void acquire() throws IOException {
        int request = used.incrementAndGet();
        if (request > runCap) {
            used.decrementAndGet();
            throw new IOException("BUDGET_CAP_REACHED: provider request cap " + runCap);
        }
    }

    public int used() { return used.get(); }
    public int cap() { return runCap; }
}
