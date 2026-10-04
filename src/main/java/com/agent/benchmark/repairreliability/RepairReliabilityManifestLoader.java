package com.agent.benchmark.repairreliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RepairReliabilityManifestLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public RepairReliabilityManifest load(Path file) throws IOException {
        RawManifest raw = mapper.readValue(file.toFile(), RawManifest.class);
        List<RepairReliabilityTask> tasks = raw.tasks().stream().map(task -> new RepairReliabilityTask(
                task.id(), task.language(), task.category(), task.fixture(), task.instruction(),
                task.expectedFiles(), task.maxSteps(), task.maxProviderRequests())).toList();
        return new RepairReliabilityManifest(raw.version(), raw.providerRequestCap(), tasks);
    }

    private record RawManifest(String version, int providerRequestCap, List<RawTask> tasks) { }
    private record RawTask(String id, String language, String category, String fixture, String instruction,
                          Map<String, List<String>> expectedFiles, int maxSteps, int maxProviderRequests) { }
}
