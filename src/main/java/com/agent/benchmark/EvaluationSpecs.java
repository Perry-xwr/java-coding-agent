package com.agent.benchmark;

import java.util.List;

public record EvaluationSpecs(List<EvaluationSpec> specs) {
    public EvaluationSpecs {
        specs = List.copyOf(specs);
    }
}
