package com.agent.benchmark.adaptiveplanning;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.LlmClientFactory;
import com.agent.llm.Message;
import com.agent.llm.ModelProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicitly gated real-provider launcher for the frozen adaptive-planning DEV experiment. */
public final class AdaptivePlanningBenchmarkMain {
    private static final String REQUIRED_RUNTIME_COMMIT = "fbf3be7";
    private static final String REQUIRED_MODEL = "glm-4-flash";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path MANIFEST = Path.of("benchmark/adaptive-planning-v1/manifest.json");

    private AdaptivePlanningBenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        requireRealOptIn(options);
        String runtimeCommit = required(options, "runtime-commit");
        String benchmarkCommit = required(options, "benchmark-commit");
        if (!REQUIRED_RUNTIME_COMMIT.equals(runtimeCommit)) {
            throw new IllegalArgumentException("This experiment requires runtime commit " + REQUIRED_RUNTIME_COMMIT);
        }
        String actualHead = git("rev-parse", "HEAD").trim();
        if (!actualHead.equals(benchmarkCommit)) {
            throw new IllegalStateException("benchmark-commit does not match current HEAD");
        }
        if (!git("status", "--porcelain").isBlank()) {
            throw new IllegalStateException("Live experiment requires a clean working tree");
        }

        ModelProviderConfig config = ModelProviderConfig.fromEnvironment();
        if (!ModelProviderConfig.GLM.equals(config.provider()) || !REQUIRED_MODEL.equals(config.model())) {
            throw new IllegalStateException("Adaptive live run requires GLM model " + REQUIRED_MODEL);
        }
        if (System.getenv("GLM_API_KEY") == null || System.getenv("GLM_API_KEY").isBlank()) {
            throw new IllegalStateException("GLM_API_KEY is not visible in this process");
        }
        verifyEndpointAndProxy(config);

        Path manifest = MANIFEST.toAbsolutePath().normalize();
        List<AdaptivePlanningTask> tasks = new AdaptivePlanningTaskLoader().load(manifest);
        int conditionCeiling = tasks.stream().mapToInt(AdaptivePlanningTask::maxProviderRequests).sum() * 3;
        Path outputRoot = Path.of("benchmark-runs/adaptive-planning-v1").toAbsolutePath().normalize();
        Path experimentRoot = outputRoot.resolve(benchmarkCommit).normalize();
        if (!experimentRoot.startsWith(outputRoot) || Files.exists(experimentRoot)) {
            throw new IOException("Result directory exists or escapes benchmark-runs; refusing to overwrite");
        }
        Map<String, String> frozenHashes = hashes(manifest);

        // Provider construction is deliberately after opt-in and all non-network preflight checks.
        LLMClient smokeClient = createProvider(options, () -> LlmClientFactory.createBenchmark(config));
        credentialSmoke(smokeClient);
        System.out.println("Live experiment: provider=GLM model=" + config.model() + " tasks=" + tasks.size()
                + " modes=3 perConditionCap=12 maxRequestsPerRound=" + conditionCeiling
                + " maxRequestsTwoRounds=" + (conditionCeiling * 2));

        writeIdentity(experimentRoot, runtimeCommit, benchmarkCommit, config, frozenHashes);
        Path localMavenRepository = Path.of(".m2/repository").toAbsolutePath().normalize();
        AdaptivePlanningBenchmarkRunner runner = new AdaptivePlanningBenchmarkRunner();
        List<AdaptivePlanningBenchmarkRunner.RunRecord> all = new ArrayList<>();
        List<AdaptivePlanningBenchmarkRunner.RunRecord> first = runner.runRound(tasks, 1,
                AdaptivePlanningBenchmarkRunner.ExecutionOrder.R_P_A, runtimeCommit, benchmarkCommit,
                config.displayProvider(), config.model(), manifest.getParent(), outputRoot,
                localMavenRepository, ignored -> LlmClientFactory.createBenchmark(config));
        all.addAll(first);
        int firstInfraFailures = infrastructureFailures(first);
        if (firstInfraFailures >= 3) {
            System.out.println("Stopping after round 1: infrastructure failures=" + firstInfraFailures);
        } else {
            List<AdaptivePlanningBenchmarkRunner.RunRecord> second = runner.runRound(tasks, 2,
                    AdaptivePlanningBenchmarkRunner.ExecutionOrder.A_P_R, runtimeCommit, benchmarkCommit,
                    config.displayProvider(), config.model(), manifest.getParent(), outputRoot,
                    localMavenRepository, ignored -> LlmClientFactory.createBenchmark(config));
            all.addAll(second);
            int secondInfraFailures = infrastructureFailures(second);
            if (secondInfraFailures >= 3) {
                System.out.println("Infrastructure failure threshold reached in round 2: " + secondInfraFailures);
            }
        }
        Map<String, String> finalHashes = hashes(manifest);
        if (!frozenHashes.equals(finalHashes)) throw new IllegalStateException("Frozen protocol files changed during run");
        AdaptivePlanningReportWriter.write(experimentRoot.resolve("summary"), all, frozenHashes,
                runtimeCommit, benchmarkCommit, config.displayProvider(), config.model());
        System.out.println("Results: " + experimentRoot);
    }

    /** Testable opt-in gate: missing approval rejects before the supplied provider can be constructed. */
    static LLMClient createProvider(String[] args, Supplier<LLMClient> providerFactory) {
        Map<String, String> options = parse(args);
        requireRealOptIn(options);
        return Objects.requireNonNull(providerFactory.get(), "provider factory returned null");
    }

    private static LLMClient createProvider(Map<String, String> options, Supplier<LLMClient> providerFactory) {
        requireRealOptIn(options);
        return Objects.requireNonNull(providerFactory.get(), "provider factory returned null");
    }

    private static void credentialSmoke(LLMClient client) throws IOException {
        LLMResponse response = client.chat(List.of(Message.user("Reply with exactly the single word OK.")), List.of());
        if (response == null || response.content() == null
                || !response.content().trim().matches("(?i)OK[.!]?")) {
            throw new IOException("Credential smoke returned an unexpected response; experiment stopped");
        }
        System.out.println("Credential smoke: PASS (one request, no tools or workspace)");
    }

    private static void verifyEndpointAndProxy(ModelProviderConfig config) throws Exception {
        URI endpoint = URI.create(config.baseUrl());
        if (endpoint.getHost() == null || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("Configured GLM endpoint must be an HTTPS URL");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 7897), 2_000);
        }
        System.out.println("Provider preflight: GLM endpoint configured; HTTP proxy 127.0.0.1:7897 reachable");
    }

    private static Map<String, String> hashes(Path manifest) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path file : List.of(manifest,
                Path.of("src/main/java/com/agent/agent/AdaptivePlanningRouter.java"),
                Path.of("src/main/java/com/agent/agent/AdaptivePlanningDecision.java"),
                Path.of("src/main/java/com/agent/agent/PlanningMode.java"),
                Path.of("src/main/java/com/agent/agent/Agent.java"),
                Path.of("src/main/java/com/agent/agent/AgentPlanner.java"),
                Path.of("src/main/java/com/agent/benchmark/adaptiveplanning/AdaptivePlanningEvaluator.java"),
                Path.of("src/main/java/com/agent/benchmark/adaptiveplanning/AdaptivePlanningMetrics.java"),
                Path.of("src/main/java/com/agent/benchmark/adaptiveplanning/AdaptivePlanningBenchmarkRunner.java"))) {
            hashes.put(file.toString().replace('\\', '/'), sha256(file));
        }
        return Map.copyOf(hashes);
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }

    private static void writeIdentity(Path root, String runtimeCommit, String benchmarkCommit,
                                      ModelProviderConfig config, Map<String, String> hashes) throws IOException {
        Path summary = root.resolve("summary");
        Files.createDirectories(summary);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("runtimeCommit", runtimeCommit);
        identity.put("benchmarkCommit", benchmarkCommit);
        identity.put("provider", config.displayProvider());
        identity.put("model", config.model());
        identity.put("filesSha256", hashes);
        Files.writeString(summary.resolve("freeze-identity.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(identity), StandardCharsets.UTF_8);
    }

    private static int infrastructureFailures(List<AdaptivePlanningBenchmarkRunner.RunRecord> records) {
        return (int) records.stream().filter(record -> record.infrastructureError() != null).count();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --name=value option");
            }
            String[] pair = arg.substring(2).split("=", 2);
            if (options.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + pair[0]);
            }
        }
        return options;
    }

    private static void requireRealOptIn(Map<String, String> options) {
        if (!"true".equals(options.get("allow-real"))) {
            throw new IllegalArgumentException("Live provider use requires --allow-real=true");
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("git preflight failed");
        return output;
    }
}
