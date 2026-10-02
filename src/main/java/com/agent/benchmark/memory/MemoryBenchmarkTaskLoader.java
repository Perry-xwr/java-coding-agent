package com.agent.benchmark.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads the independent memory-v1 manifest without depending on V1.2 task types. */
public final class MemoryBenchmarkTaskLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<MemoryBenchmarkTask> load(Path manifest) throws IOException {
        JsonNode root = mapper.readTree(manifest.toFile());
        if (!"memory-v1".equals(root.path("protocol").asText())) {
            throw new IOException("Expected memory-v1 protocol manifest");
        }
        JsonNode tasks = root.path("tasks");
        if (!tasks.isArray() || tasks.isEmpty()) {
            throw new IOException("memory-v1 tasks must be a non-empty array");
        }
        List<MemoryBenchmarkTask> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode taskNode : tasks) {
            MemoryBenchmarkTask task = mapper.treeToValue(taskNode, MemoryBenchmarkTask.class);
            if (!ids.add(task.id())) {
                throw new IOException("Duplicate memory-v1 task id: " + task.id());
            }
            loaded.add(task);
        }
        return List.copyOf(loaded);
    }
}
