package com.agent.benchmark.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads the independent planning-v1 manifest. */
public final class PlanningBenchmarkTaskLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<PlanningBenchmarkTask> load(Path manifest) throws IOException {
        JsonNode root = mapper.readTree(manifest.toFile());
        if (!"planning-v1".equals(root.path("protocol").asText())
                || !"DEV".equals(root.path("split").asText())) {
            throw new IOException("Expected planning-v1 DEV manifest");
        }
        List<PlanningBenchmarkTask> tasks = mapper.convertValue(root.path("tasks"),
                new TypeReference<>() { });
        if (tasks.size() != 8) throw new IOException("planning-v1 DEV must contain exactly 8 tasks");
        Set<String> ids = new HashSet<>();
        for (PlanningBenchmarkTask task : tasks) {
            if (!ids.add(task.id())) throw new IOException("Duplicate task id: " + task.id());
        }
        return List.copyOf(tasks);
    }
}
