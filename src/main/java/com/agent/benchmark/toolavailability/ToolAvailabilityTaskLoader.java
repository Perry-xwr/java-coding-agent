package com.agent.benchmark.toolavailability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads and validates the isolated, no-TEST V1.10 DEV task protocol. */
public final class ToolAvailabilityTaskLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<ToolAvailabilityTask> load(Path manifest) throws IOException {
        JsonNode root = mapper.readTree(manifest.toFile());
        if (!"tool-availability-v1".equals(root.path("protocol").asText())
                || !"DEV".equals(root.path("split").asText())
                || root.path("taskCount").asInt() != 12) {
            throw new IOException("Expected the 12-task tool-availability-v1 DEV manifest");
        }
        List<ToolAvailabilityTask> tasks = mapper.convertValue(root.path("tasks"), new TypeReference<>() { });
        if (tasks.size() != 12) throw new IOException("DEV must contain exactly 12 tasks");
        Set<String> ids = new HashSet<>();
        Set<String> fixtures = new HashSet<>();
        Map<ToolAvailabilityWorkspaceClass, Integer> classes = new EnumMap<>(ToolAvailabilityWorkspaceClass.class);
        Map<String, Integer> categories = new java.util.HashMap<>();
        for (ToolAvailabilityTask task : tasks) {
            if (!ids.add(task.id())) throw new IOException("Duplicate task id: " + task.id());
            if (!fixtures.add(task.fixture())) throw new IOException("Every task needs a unique fixture: " + task.fixture());
            classes.merge(task.workspaceClass(), 1, Integer::sum);
            categories.merge(task.workspaceClass() + ":" + task.taskCategory(), 1, Integer::sum);
            Path fixture = manifest.toAbsolutePath().normalize().getParent().resolve("fixtures")
                    .resolve(task.fixture()).normalize();
            if (!fixture.startsWith(manifest.toAbsolutePath().normalize().getParent().resolve("fixtures").normalize())
                    || !Files.isDirectory(fixture)) {
                throw new IOException("Missing or unsafe fixture for " + task.id());
            }
            if (task.maxProviderRequests() != 12 || task.maxSteps() != 10) {
                throw new IOException("Unfair task budget in " + task.id());
            }
        }
        requireCount(classes, ToolAvailabilityWorkspaceClass.STANDALONE_JAVA, 4);
        requireCount(classes, ToolAvailabilityWorkspaceClass.MAVEN_JAVA, 4);
        requireCount(classes, ToolAvailabilityWorkspaceClass.PYTHON, 2);
        requireCount(classes, ToolAvailabilityWorkspaceClass.JAVASCRIPT, 2);
        for (ToolAvailabilityWorkspaceClass javaClass : List.of(
                ToolAvailabilityWorkspaceClass.STANDALONE_JAVA, ToolAvailabilityWorkspaceClass.MAVEN_JAVA)) {
            for (String category : List.of("SIMPLE_METHOD_EDIT", "SIGNATURE_CHANGE", "STRUCTURAL_INSERT", "TWO_STAGE_EDIT")) {
                if (categories.getOrDefault(javaClass + ":" + category, 0) != 1) {
                    throw new IOException("Expected one " + category + " task in " + javaClass);
                }
            }
        }
        return List.copyOf(tasks);
    }

    private static void requireCount(Map<ToolAvailabilityWorkspaceClass, Integer> counts,
                                     ToolAvailabilityWorkspaceClass key, int expected) throws IOException {
        if (counts.getOrDefault(key, 0) != expected) {
            throw new IOException("Expected " + expected + " tasks for " + key);
        }
    }
}
