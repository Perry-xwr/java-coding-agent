package com.agent.benchmark.adaptiveplanning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads and validates the standalone adaptive-planning DEV manifest. */
public final class AdaptivePlanningTaskLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<AdaptivePlanningTask> load(Path manifest) throws IOException {
        JsonNode root = mapper.readTree(manifest.toFile());
        if (!"adaptive-planning-v1".equals(root.path("protocol").asText())
                || !"DEV".equals(root.path("split").asText())) {
            throw new IOException("Expected adaptive-planning-v1 DEV manifest");
        }
        List<AdaptivePlanningTask> tasks = mapper.convertValue(root.path("tasks"), new TypeReference<>() { });
        if (tasks.size() != 9) throw new IOException("adaptive-planning-v1 DEV must contain exactly 9 tasks");
        Set<String> ids = new HashSet<>();
        Map<AdaptivePlanningTaskClass, Integer> counts = new EnumMap<>(AdaptivePlanningTaskClass.class);
        for (AdaptivePlanningTask task : tasks) {
            if (!ids.add(task.id())) throw new IOException("Duplicate adaptive task id: " + task.id());
            counts.merge(task.taskClass(), 1, Integer::sum);
            if (task.expectedAdaptiveMode() != expectedRoute(task.taskClass())) {
                throw new IOException("Invalid expected adaptive route for " + task.id());
            }
        }
        for (AdaptivePlanningTaskClass taskClass : AdaptivePlanningTaskClass.values()) {
            if (counts.getOrDefault(taskClass, 0) != 3) {
                throw new IOException("DEV must contain exactly 3 " + taskClass + " tasks");
            }
        }
        return List.copyOf(tasks);
    }

    private static com.agent.agent.PlanningMode expectedRoute(AdaptivePlanningTaskClass taskClass) {
        return taskClass == AdaptivePlanningTaskClass.COMPLEX
                ? com.agent.agent.PlanningMode.PLAN_EXECUTE : com.agent.agent.PlanningMode.REACTIVE;
    }
}
