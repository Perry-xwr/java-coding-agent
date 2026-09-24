package com.agent.benchmark;

public record ExperimentMetadata(
        String experimentId,
        String model,
        String provider,
        BaselineType baseline,
        String benchmarkVersion,
        String temperature,
        int maxSteps,
        String timestamp,
        String gitCommit
) {
}
