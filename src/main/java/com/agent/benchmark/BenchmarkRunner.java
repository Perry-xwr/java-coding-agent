package com.agent.benchmark;

import com.agent.agent.AgentRunResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BenchmarkRunner {
    private final FixtureWorkspaceManager workspaceManager;
    private final BenchmarkAgentExecutor agentExecutor;
    private final TaskEvaluator evaluator;
    private final BenchmarkResultWriter resultWriter;
    private final FailureClassifier failureClassifier = new FailureClassifier();
    private final BenchmarkMetricsCalculator metricsCalculator = new BenchmarkMetricsCalculator();

    public BenchmarkRunner(
            FixtureWorkspaceManager workspaceManager,
            BenchmarkAgentExecutor agentExecutor,
            TaskEvaluator evaluator,
            BenchmarkResultWriter resultWriter
    ) {
        this.workspaceManager = workspaceManager;
        this.agentExecutor = agentExecutor;
        this.evaluator = evaluator;
        this.resultWriter = resultWriter;
    }

    public BenchmarkExecutionResult run(
            ExperimentMetadata experiment,
            List<BenchmarkTask> tasks
    ) throws IOException {
        List<BenchmarkRunRecord> records = new ArrayList<>();
        for (BenchmarkTask task : tasks) {
            Path workspace = workspaceManager.reset(task, experiment.experimentId());
            AgentRunResult run = agentExecutor.run(task, workspace, experiment.baseline());
            EvaluationResult evaluation;
            boolean evaluatorError = false;
            try {
                evaluation = evaluator.evaluate(task, workspace, run);
            } catch (IOException | RuntimeException exception) {
                evaluatorError = true;
                evaluation = new EvaluationResult(
                        task.id(),
                        false,
                        false,
                        false,
                        false,
                        "Evaluator error: " + messageOrType(exception),
                        Map.of("evaluatorError", true)
                );
            }
            FailureCategory failure = failureClassifier.classify(
                    task,
                    run,
                    evaluation,
                    evaluatorError
            );
            BenchmarkRunRecord record = new BenchmarkRunRecord(
                    task,
                    experiment.baseline(),
                    run,
                    evaluation,
                    failure,
                    evaluatorError
            );
            records.add(record);
            resultWriter.writeTask(experiment.experimentId(), record);
        }
        BenchmarkMetrics metrics = metricsCalculator.calculate(records);
        resultWriter.writeSummary(experiment, metrics, records);
        return new BenchmarkExecutionResult(experiment, records, metrics);
    }

    private static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
