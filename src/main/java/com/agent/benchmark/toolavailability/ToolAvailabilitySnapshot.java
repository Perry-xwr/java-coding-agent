package com.agent.benchmark.toolavailability;

/** Availability observed at one provider advertisement or model tool invocation. */
public record ToolAvailabilitySnapshot(
        int sequence,
        String event,
        boolean safeRootPomPresent,
        boolean runMavenTestAvailable
) { }
