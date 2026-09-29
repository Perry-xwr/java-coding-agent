package com.agent.benchmark.v12;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Loads and validates only the independent V1.2 manifest. */
public final class V12BenchmarkTaskLoader {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public V12BenchmarkSuite load(Path manifest) throws IOException {
        V12BenchmarkSuite suite = objectMapper.readValue(manifest.toFile(), V12BenchmarkSuite.class);
        Set<String> ids = new HashSet<>();
        int dev = 0;
        int test = 0;
        for (V12Task task : suite.tasks()) {
            if (!ids.add(task.id())) {
                throw new IOException("Duplicate V1.2 benchmark task id: " + task.id());
            }
            if (task.split() == V12Split.DEV) {
                dev++;
            } else {
                test++;
            }
            validateFixture(task);
        }
        if (dev != 10 || test != 20) {
            throw new IOException("V1.2 protocol requires exactly DEV=10 and TEST=20; got DEV=" + dev + ", TEST=" + test);
        }
        return suite;
    }

    private static void validateFixture(V12Task task) throws IOException {
        for (String path : task.initialFixture().keySet()) {
            if (path.isBlank() || Path.of(path).isAbsolute() || path.contains("..") || path.startsWith(".hidden/")) {
                throw new IOException("Unsafe fixture path in " + task.id() + ": " + path);
            }
        }
    }
}
