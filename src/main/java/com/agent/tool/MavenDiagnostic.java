package com.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

public record MavenDiagnostic(
        DiagnosticType type,
        String summary,
        String file,
        Integer line,
        String symbol,
        String testClass,
        String testMethod,
        String expected,
        String actual,
        String message,
        String rawFragment
) {
    public Map<String, Object> metadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("diagnosticType", type.name());
        values.put("diagnosticSummary", summary);
        put(values, "diagnosticFile", file);
        put(values, "diagnosticLine", line);
        put(values, "diagnosticSymbol", symbol);
        put(values, "diagnosticTestClass", testClass);
        put(values, "diagnosticTestMethod", testMethod);
        put(values, "diagnosticExpected", expected);
        put(values, "diagnosticActual", actual);
        put(values, "diagnosticMessage", message);
        put(values, "diagnosticRawFragment", rawFragment);
        return Map.copyOf(values);
    }

    private static void put(Map<String, Object> values, String name, Object value) {
        if (value != null) {
            values.put(name, value);
        }
    }
}
