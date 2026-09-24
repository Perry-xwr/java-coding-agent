package com.agent.benchmark;

import com.agent.trajectory.TrajectoryJsonWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BenchmarkResultWriter {
    private final Path resultsRoot;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BenchmarkResultWriter(Path resultsRoot) {
        this.resultsRoot = resultsRoot.toAbsolutePath().normalize();
    }

    public void writeTask(String experimentId, BenchmarkRunRecord record) throws IOException {
        Path directory = taskDirectory(experimentId, record.task().id());
        Files.createDirectories(directory);
        Path generated = new TrajectoryJsonWriter(directory).write(record.run().trajectory());
        Files.move(
                generated,
                directory.resolve("trajectory.json"),
                StandardCopyOption.REPLACE_EXISTING
        );
        Map<String, Object> persistedEvaluation = new LinkedHashMap<>();
        persistedEvaluation.put("taskId", record.task().id());
        persistedEvaluation.put("baseline", record.baseline());
        persistedEvaluation.put("evaluation", record.evaluation());
        persistedEvaluation.put("failureCategory", record.failureCategory());
        persistedEvaluation.put("evaluatorError", record.evaluatorError());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("evaluation.json").toFile(),
                persistedEvaluation
        );
    }

    public void writeSummary(
            ExperimentMetadata metadata,
            BenchmarkMetrics metrics,
            List<BenchmarkRunRecord> records
    ) throws IOException {
        Path directory = experimentDirectory(metadata.experimentId());
        Files.createDirectories(directory);
        BenchmarkSummary summary = new BenchmarkSummary(
                metadata,
                metrics,
                records.stream().map(record -> record.task().id()).toList()
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("summary.json").toFile(),
                summary
        );
        Files.writeString(
                directory.resolve("summary.md"),
                summaryMarkdown(metadata, metrics),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                directory.resolve("failure_analysis.md"),
                failureMarkdown(records),
                StandardCharsets.UTF_8
        );
    }

    private Path experimentDirectory(String experimentId) throws IOException {
        Path directory = resultsRoot.resolve(experimentId).normalize();
        if (!directory.startsWith(resultsRoot)) {
            throw new IOException("Experiment output escaped results root");
        }
        return directory;
    }

    private Path taskDirectory(String experimentId, String taskId) throws IOException {
        Path experiment = experimentDirectory(experimentId);
        Path directory = experiment.resolve(taskId).normalize();
        if (!directory.startsWith(experiment)) {
            throw new IOException("Task output escaped experiment root");
        }
        return directory;
    }

    private static String summaryMarkdown(
            ExperimentMetadata metadata,
            BenchmarkMetrics metrics
    ) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Benchmark Summary\n\n")
                .append("| Baseline | Tasks | Success | Success Rate | Avg Steps | Recovery Rate |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: |\n")
                .append("| ").append(metadata.baseline()).append(" | ")
                .append(metrics.evaluableTasks()).append(" | ")
                .append(metrics.successfulTasks()).append(" | ")
                .append(format(metrics.taskSuccessRate())).append(" | ")
                .append(format(metrics.averageSteps())).append(" | ")
                .append(format(metrics.testFailureRecoveryRate())).append(" |\n\n");
        appendBreakdown(markdown, "Category", metrics.categoryBreakdown());
        appendBreakdown(markdown, "Difficulty", metrics.difficultyBreakdown());
        return markdown.toString();
    }

    private static void appendBreakdown(
            StringBuilder markdown,
            String title,
            Map<String, BreakdownMetrics> breakdown
    ) {
        markdown.append("## ").append(title).append(" Breakdown\n\n")
                .append("| ").append(title).append(" | Total | Success | Rate | Avg Steps |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n");
        breakdown.forEach((name, value) -> markdown.append("| ").append(name).append(" | ")
                .append(value.total()).append(" | ")
                .append(value.successful()).append(" | ")
                .append(format(value.successRate())).append(" | ")
                .append(format(value.averageSteps())).append(" |\n"));
        markdown.append('\n');
    }

    private static String failureMarkdown(List<BenchmarkRunRecord> records) {
        StringBuilder markdown = new StringBuilder("# Failure Analysis\n\n")
                .append("| Failure Category | Count | Rate | Representative Task IDs |\n")
                .append("| --- | ---: | ---: | --- |\n");
        int total = records.size();
        for (FailureCategory category : FailureCategory.values()) {
            List<String> ids = records.stream()
                    .filter(record -> record.failureCategory() == category)
                    .map(record -> record.task().id())
                    .sorted()
                    .toList();
            if (!ids.isEmpty()) {
                markdown.append("| ").append(category).append(" | ")
                        .append(ids.size()).append(" | ")
                        .append(format(total == 0 ? 0.0 : (double) ids.size() / total)).append(" | ")
                        .append(String.join(", ", ids.stream().limit(3).toList()))
                        .append(" |\n");
            }
        }
        return markdown.toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
