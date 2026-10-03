package com.agent.benchmark.repairreliability;

import com.agent.trajectory.TrajectoryJsonWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Writes redacted per-condition metrics and trajectory artifacts into ignored benchmark-runs output. */
public final class RepairReliabilityResultWriter {
    private final Path directory;
    private final Path workspacesRoot;
    private final Path jsonl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TrajectoryJsonWriter trajectories;

    public RepairReliabilityResultWriter(Path directory, Path workspacesRoot) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.workspacesRoot = workspacesRoot.toAbsolutePath().normalize();
        if (Files.exists(this.directory)) throw new IOException("result directory already exists: " + this.directory);
        Files.createDirectories(this.directory.resolve("trajectories"));
        Files.createDirectories(this.workspacesRoot);
        this.jsonl = this.directory.resolve("task-results.jsonl");
        this.trajectories = new TrajectoryJsonWriter(this.directory.resolve("trajectories"));
    }

    public synchronized void write(String round, RepairReliabilityRoundOrder order,
                                   RepairReliabilityRuntimeHarness.RunResult result) throws IOException {
        Path trajectory = trajectories.write(result.agentResult().trajectory());
        ObjectNode line = mapper.createObjectNode();
        line.put("round", round);
        line.put("order", order.name());
        line.put("taskId", result.task().id());
        line.put("mode", result.mode().name());
        line.put("providerRequests", result.providerRequests());
        line.put("workspace", this.workspacesRoot.relativize(result.workspace().toAbsolutePath().normalize())
                .toString().replace('\\', '/'));
        line.put("trajectory", this.directory.relativize(trajectory.toAbsolutePath().normalize())
                .toString().replace('\\', '/'));
        line.set("metrics", mapper.valueToTree(result.metrics()));
        line.set("evaluation", mapper.valueToTree(result.evaluation()));
        append(line);
    }

    public synchronized void writeInfrastructureFailure(String round, RepairReliabilityRoundOrder order,
                                                          RepairReliabilityTask task,
                                                          RepairReliabilityMode mode,
                                                          String category) throws IOException {
        ObjectNode line = mapper.createObjectNode();
        line.put("round", round);
        line.put("order", order.name());
        line.put("taskId", task.id());
        line.put("mode", mode.name());
        line.put("infrastructureError", true);
        line.put("infrastructureErrorCategory", category);
        append(line);
    }

    public synchronized void writeRoundSummary(String round, RepairReliabilityRoundOrder order,
                                                List<RepairReliabilityRuntimeHarness.RunResult> results,
                                                List<RepairReliabilityBenchmarkRunner.ConditionInfrastructureFailure> errors,
                                                boolean stopped) throws IOException {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("round", round);
        summary.put("order", order.name());
        summary.put("completedConditions", results.size());
        summary.put("infrastructureFailures", errors.size());
        summary.put("stoppedAtInfrastructureThreshold", stopped);
        ObjectNode modes = summary.putObject("modes");
        for (RepairReliabilityMode mode : RepairReliabilityMode.values()) {
            List<RepairReliabilityRuntimeHarness.RunResult> selected = results.stream()
                    .filter(result -> result.mode() == mode).toList();
            ObjectNode values = modes.putObject(mode.name());
            values.put("runs", selected.size());
            values.put("taskSuccesses", selected.stream().filter(result -> result.metrics().taskSuccess()).count());
            values.put("repairEligibleRuns", selected.stream()
                    .filter(result -> result.metrics().repairEligibleRun()).count());
            values.put("recoveredRuns", selected.stream().filter(result -> result.metrics().recoveredRun()).count());
            values.put("providerRequests", selected.stream().mapToInt(RepairReliabilityRuntimeHarness.RunResult::providerRequests).sum());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("round-summary.json").toFile(), summary);
    }

    private void append(ObjectNode line) throws IOException {
        Files.writeString(jsonl, mapper.writeValueAsString(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
