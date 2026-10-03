package com.agent.benchmark.editreliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EditReliabilityManifest {
    private final int providerRequestCap;
    private final List<EditReliabilityTask> tasks;

    private EditReliabilityManifest(int providerRequestCap, List<EditReliabilityTask> tasks) {
        this.providerRequestCap = providerRequestCap;
        this.tasks = List.copyOf(tasks);
    }

    public static EditReliabilityManifest load(Path path) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        if (!"DEV".equals(root.path("split").asText())) {
            throw new IOException("edit reliability protocol supports DEV only");
        }
        int cap = root.path("providerRequestCap").asInt(0);
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray()) throw new IOException("tasks must be an array");
        List<EditReliabilityTask> tasks = new ArrayList<>();
        for (JsonNode node : tasksNode) {
            Map<String, List<String>> expected = new LinkedHashMap<>();
            node.path("expectedFiles").fields().forEachRemaining(entry -> {
                List<String> snippets = new ArrayList<>();
                entry.getValue().forEach(value -> snippets.add(value.asText()));
                expected.put(entry.getKey(), snippets);
            });
            try {
                tasks.add(new EditReliabilityTask(node.path("id").asText(), node.path("language").asText(),
                        EditReliabilityTaskCategory.valueOf(node.path("category").asText()),
                        node.path("fixture").asText(), node.path("instruction").asText(), expected, cap));
            } catch (RuntimeException exception) {
                throw new IOException("invalid edit reliability task: " + node.path("id").asText(), exception);
            }
        }
        if (tasks.size() != 12 || cap != 12) throw new IOException("protocol requires 12 tasks and request cap 12");
        return new EditReliabilityManifest(cap, tasks);
    }

    public int providerRequestCap() { return providerRequestCap; }
    public List<EditReliabilityTask> tasks() { return tasks; }
}
