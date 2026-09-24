package com.agent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class BenchmarkTaskLoader {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BenchmarkSuite load(Path path) throws IOException {
        BenchmarkSuite suite = objectMapper.readValue(path.toFile(), BenchmarkSuite.class);
        Set<String> ids = new HashSet<>();
        for (BenchmarkTask task : suite.tasks()) {
            if (!ids.add(task.id())) {
                throw new IOException("Duplicate benchmark task id: " + task.id());
            }
        }
        return suite;
    }
}
