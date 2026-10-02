package com.agent.benchmark.v12;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class V12EvaluationCheckLoader {
    public Map<String, V12EvaluationCheck> load(Path path) throws IOException {
        List<V12EvaluationCheck> checks = new ObjectMapper().readValue(path.toFile(), new TypeReference<>() {});
        Map<String, V12EvaluationCheck> byId = new LinkedHashMap<>();
        for (V12EvaluationCheck check : checks) {
            if (byId.put(check.taskId(), check) != null) throw new IOException("Duplicate evaluator check: " + check.taskId());
        }
        return Map.copyOf(byId);
    }
}
