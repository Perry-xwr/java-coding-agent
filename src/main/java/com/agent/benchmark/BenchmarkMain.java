package com.agent.benchmark;

import com.agent.llm.GlmClient;
import com.agent.tool.execution.DefaultProcessRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BenchmarkMain {
    private static final String VERSION = "v0.1";

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        Path benchmarkRoot = projectRoot.resolve("benchmark");
        BenchmarkSuite suite = new BenchmarkTaskLoader().load(
                benchmarkRoot.resolve("tasks/v0.1/tasks.json")
        );
        if (!VERSION.equals(suite.benchmarkVersion())) {
            throw new IllegalStateException("Unexpected benchmark version: " + suite.benchmarkVersion());
        }
        List<BenchmarkTask> selected = suite.tasks().stream()
                .filter(task -> options.category == null || task.category() == options.category)
                .filter(task -> options.difficulty == null || task.difficulty() == options.difficulty)
                .filter(task -> options.split == null || task.split() == options.split)
                .limit(options.limit)
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No tasks matched the requested filters");
        }

        String experimentId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(Instant.now()) + "-" + options.baseline.name().toLowerCase(Locale.ROOT);
        Path localRepository = projectRoot.resolve(".m2/repository");
        FixtureWorkspaceManager workspaceManager = new FixtureWorkspaceManager(
                benchmarkRoot.resolve("fixtures/v0.1"),
                projectRoot.resolve("target/benchmark-runs")
        );
        Map<String, EvaluationSpec> specs = new EvaluationSpecLoader().load(
                benchmarkRoot.resolve("tasks/v0.1/evaluation-checks.json")
        );
        BenchmarkRunner runner = new BenchmarkRunner(
                workspaceManager,
                new DefaultBaselineExecutor(GlmClient::new, localRepository),
                new DeterministicTaskEvaluator(
                        workspaceManager,
                        benchmarkRoot.resolve("hidden/v0.1"),
                        localRepository,
                        new DefaultProcessRunner(),
                        specs
                ),
                new BenchmarkResultWriter(options.outputDirectory)
        );
        int maxSteps = selected.stream().mapToInt(BenchmarkTask::maxSteps).max().orElse(0);
        ExperimentMetadata metadata = new ExperimentMetadata(
                experimentId,
                "glm-4-flash",
                "GLM",
                options.baseline,
                VERSION,
                "provider-default",
                maxSteps,
                Instant.now().toString(),
                System.getenv("GIT_COMMIT")
        );

        BenchmarkExecutionResult result = runner.run(metadata, selected);
        System.out.println("Experiment: " + experimentId);
        System.out.println("Tasks: " + result.metrics().totalTasks());
        System.out.println("Success rate: " + result.metrics().taskSuccessRate());
        System.out.println("Results: " + options.outputDirectory.resolve(experimentId));
    }

    private static final class Options {
        private BaselineType baseline = BaselineType.REACT;
        private TaskCategory category;
        private TaskDifficulty difficulty;
        private BenchmarkSplit split;
        private int limit = Integer.MAX_VALUE;
        private Path outputDirectory = Path.of("benchmark/results");

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for: " + args[index]);
                }
                String value = args[index + 1];
                switch (args[index]) {
                    case "--baseline" -> options.baseline = BaselineType.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--category" -> options.category = TaskCategory.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--difficulty" -> options.difficulty = TaskDifficulty.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--split" -> options.split = BenchmarkSplit.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--limit" -> options.limit = positiveInt(value, "limit");
                    case "--output" -> options.outputDirectory = Path.of(value);
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            return options;
        }

        private static int positiveInt(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        }
    }
}
