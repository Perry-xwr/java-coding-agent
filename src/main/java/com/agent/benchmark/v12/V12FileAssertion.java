package com.agent.benchmark.v12;

import java.util.List;

public record V12FileAssertion(String path, boolean exists, List<String> contains, List<String> notContains,
                               List<String> anyContains) {
    public V12FileAssertion {
        contains = contains == null ? List.of() : List.copyOf(contains);
        notContains = notContains == null ? List.of() : List.copyOf(notContains);
        anyContains = anyContains == null ? List.of() : List.copyOf(anyContains);
    }
    public V12FileAssertion(String path,boolean exists,List<String> contains,List<String> notContains) {
        this(path,exists,contains,notContains,List.of());
    }
}
