package com.agent.benchmark;

public record ToolUsageMetrics(int calls, int successful, int failed, double successRate) {
}
