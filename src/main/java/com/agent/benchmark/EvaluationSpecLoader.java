package com.agent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EvaluationSpecLoader {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, EvaluationSpec> load(Path path) throws IOException {
        EvaluationSpecs loaded = objectMapper.readValue(path.toFile(), EvaluationSpecs.class);
        return loaded.specs().stream().collect(Collectors.toUnmodifiableMap(
                EvaluationSpec::taskId,
                Function.identity()
        ));
    }
}
