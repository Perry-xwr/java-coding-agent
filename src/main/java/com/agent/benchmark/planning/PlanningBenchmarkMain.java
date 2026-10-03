package com.agent.benchmark.planning;

import com.agent.llm.LLMClient;
import com.agent.llm.LlmClientFactory;
import com.agent.llm.ModelProviderConfig;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicitly gated live DEV entry point; no live provider is created without --allow-real. */
public final class PlanningBenchmarkMain {
    private PlanningBenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (!"true".equals(options.get("allow-real"))) {
            throw new IllegalArgumentException("Live provider use requires --allow-real=true");
        }
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment();
        if (!ModelProviderConfig.GLM.equals(config.provider()) || !"glm-4-flash".equals(config.model())) {
            throw new IllegalStateException("planning-v1 live runs require GLM model glm-4-flash");
        }
        // Validate credentials/configuration before creating any task workspace or issuing a request.
        LlmClientFactory.createBenchmark(config);

        int round = Integer.parseInt(required(options, "round"));
        PlanningBenchmarkRunner.ExecutionOrder order = PlanningBenchmarkRunner.ExecutionOrder.valueOf(
                required(options, "order"));
        String runId = required(options, "run-id");
        String runtimeCommit = required(options, "runtime-commit");
        String benchmarkCommit = required(options, "benchmark-commit");
        Path manifest = Path.of("benchmark/planning-v1/manifest.json").toAbsolutePath().normalize();
        List<PlanningBenchmarkTask> tasks = new PlanningBenchmarkTaskLoader().load(manifest);
        int modeBudgetCeiling = tasks.stream().mapToInt(PlanningBenchmarkTask::maxProviderRequests).sum();
        System.out.println("planning-v1 live DEV preflight: provider=GLM model=glm-4-flash"
                + " tasks=" + tasks.size() + " modeRequestCeiling=" + modeBudgetCeiling
                + " output=benchmark-runs/planning-v1/" + runId + "/round-" + round);

        Path output = Path.of("benchmark-runs/planning-v1").toAbsolutePath().normalize();
        Path mavenRepository = Path.of(".m2/repository").toAbsolutePath().normalize();
        List<PlanningBenchmarkRunner.PlanningRunRecord> records = new PlanningBenchmarkRunner().runRound(
                tasks, round, order, runtimeCommit, benchmarkCommit, config.displayProvider(), config.model(),
                manifest.getParent(), output, runId, mavenRepository,
                task -> LlmClientFactory.createBenchmark(config));
        for (PlanningBenchmarkRunner.PlanningRunRecord record : records) {
            System.out.printf("round=%d task=%s mode=%s success=%s requests=%d tools=%d infrastructureError=%s%n",
                    record.round(), record.taskId(), record.mode(), record.success(), record.providerRequests(),
                    record.toolSteps(), record.infrastructureError() == null ? "none" : "recorded");
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --name=value option");
            }
            String[] pair = arg.substring(2).split("=", 2);
            if (result.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + pair[0]);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }
}
