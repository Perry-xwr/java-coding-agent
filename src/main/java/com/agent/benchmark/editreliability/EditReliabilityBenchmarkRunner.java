package com.agent.benchmark.editreliability;

import com.agent.llm.LLMClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Paired runner. Provider creation is mandatory and explicitly injected; there is no live-provider default. */
public final class EditReliabilityBenchmarkRunner {
    private final EditReliabilityRuntimeHarness harness = new EditReliabilityRuntimeHarness();

    public List<EditReliabilityRuntimeHarness.RunResult> runAll(
            EditReliabilityManifest manifest,
            ProviderFactory providers,
            Path fixturesRoot,
            Path runsRoot,
            String runId,
            Path localMavenRepository
    ) throws Exception {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(providers, "provider factory must be explicitly supplied");
        List<EditReliabilityRuntimeHarness.RunResult> results = new ArrayList<>();
        for (EditReliabilityTask task : manifest.tasks()) {
            for (EditReliabilityMode mode : EditReliabilityMode.values()) {
                LLMClient provider = Objects.requireNonNull(providers.create(task, mode),
                        "provider factory returned null");
                results.add(harness.run(task, mode, provider, fixturesRoot, runsRoot, runId,
                        localMavenRepository));
            }
        }
        return List.copyOf(results);
    }

    @FunctionalInterface
    public interface ProviderFactory {
        LLMClient create(EditReliabilityTask task, EditReliabilityMode mode);
    }
}
