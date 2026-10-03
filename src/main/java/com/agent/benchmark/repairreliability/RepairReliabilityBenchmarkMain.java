package com.agent.benchmark.repairreliability;

import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationStatus;
import com.agent.llm.LLMClient;
import com.agent.llm.LlmClientFactory;
import com.agent.llm.ModelProviderConfig;
import com.agent.tool.execution.DefaultProcessRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Opt-in live entry point; it never creates a provider without explicit confirmation flags. */
public final class RepairReliabilityBenchmarkMain {
    private static final String REQUIRED_PROXY = "http://127.0.0.1:7897";
    private RepairReliabilityBenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Args options = Args.parse(args);
        if (!options.allowReal || !options.confirmLive) {
            throw new IllegalArgumentException("Live benchmark requires --allow-real and --confirm-live");
        }
        Path repo = Path.of("").toAbsolutePath().normalize();
        Path benchmarkRoot = repo.resolve("benchmark-runs/repair-reliability-v1")
                .resolve(options.benchmarkCommit);
        Path identityFile = benchmarkRoot.resolve("pre-live/freeze-identities.json");
        verifyGitCheckpoint(repo, options.benchmarkCommit);
        RepairReliabilityFreezeIdentity.verify(repo, options.benchmarkCommit, identityFile);
        ModelProviderConfig config = preflightProvider();
        RepairReliabilityManifest manifest = new RepairReliabilityManifestLoader()
                .load(repo.resolve("benchmark/repair-reliability-v1/manifest.json"));
        if (manifest.providerRequestCap() != 12
                || manifest.tasks().stream().anyMatch(task -> task.maxProviderRequests() != 12)) {
            throw new IllegalStateException("Frozen repair benchmark request cap is not 12");
        }
        verifyFixtures(repo, manifest);
        verifyInitialFixtures(repo, manifest);
        verifyLocalVerifiers();
        if (options.preflightOnly) {
            System.out.println("Preflight PASS: GLM glm-4-flash, explicit proxy TCP, fixtures and local verifiers.");
            return;
        }
        Path resultDirectory = benchmarkRoot.resolve("round-" + options.round);
        Path workspaceRoot = benchmarkRoot.resolve("workspaces");
        RepairReliabilityRoundOrder order = options.round == 1
                ? RepairReliabilityRoundOrder.VERIFICATION_THEN_GUIDED
                : RepairReliabilityRoundOrder.GUIDED_THEN_VERIFICATION;
        RepairReliabilityBenchmarkRunner runner = new RepairReliabilityBenchmarkRunner();
        RepairReliabilityBenchmarkRunner.RoundResult result = runner.runRound(manifest,
                (task, mode) -> createProvider(config), repo.resolve("benchmark/repair-reliability-v1/fixtures"),
                workspaceRoot, "round-" + options.round, repo.resolve(".m2/repository"),
                resultDirectory, order);
        int requests = result.results().stream()
                .mapToInt(RepairReliabilityRuntimeHarness.RunResult::providerRequests).sum();
        System.out.println("Round " + options.round + " finished: conditions="
                + result.results().size() + ", providerRequests=" + requests
                + ", infrastructureFailures=" + result.infrastructureFailures().size()
                + ", stopped=" + result.stoppedAtInfrastructureThreshold());
    }

    private static ModelProviderConfig preflightProvider() {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment();
        if (!ModelProviderConfig.GLM.equals(config.provider())
                || !ModelProviderConfig.DEFAULT_GLM_MODEL.equals(config.model())) {
            throw new IllegalStateException("Preflight requires GLM glm-4-flash");
        }
        String proxy = System.getenv("MODEL_PROXY");
        if (!REQUIRED_PROXY.equals(proxy)) throw new IllegalStateException("Required explicit proxy is not configured");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 7897), 2_000);
        } catch (IOException exception) {
            throw new IllegalStateException("Explicit proxy TCP preflight failed", exception);
        }
        // Factory construction validates that the key is present without making a request or printing it.
        LlmClientFactory.createBenchmark(config);
        return config;
    }

    private static void verifyGitCheckpoint(Path repository, String requestedCommit) throws IOException {
        String head = git(repository, "rev-parse", "HEAD").trim().toLowerCase(Locale.ROOT);
        if (!head.matches("[0-9a-f]{40}") || !head.startsWith(requestedCommit)) {
            throw new IllegalStateException("Current HEAD does not match the frozen benchmark commit");
        }
        String status = git(repository, "status", "--porcelain", "--untracked-files=all");
        if (!status.isBlank()) throw new IllegalStateException("Working tree must be clean before live evaluation");
    }

    private static String git(Path repository, String... arguments) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("git checkpoint validation failed");
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("git checkpoint validation interrupted", exception);
        }
    }

    private static LLMClient createProvider(ModelProviderConfig config) {
        return LlmClientFactory.createBenchmark(config);
    }

    private static void verifyFixtures(Path repo, RepairReliabilityManifest manifest) throws IOException {
        Path fixtures = repo.resolve("benchmark/repair-reliability-v1/fixtures").toAbsolutePath().normalize();
        for (RepairReliabilityTask task : manifest.tasks()) {
            Path source = fixtures.resolve(task.fixture()).normalize();
            if (!source.startsWith(fixtures) || !Files.isDirectory(source)) {
                throw new IllegalStateException("Missing or unsafe fixture: " + task.id());
            }
            for (String relative : task.expectedFiles().keySet()) {
                if (!Files.isRegularFile(source.resolve(relative).normalize())) {
                    throw new IllegalStateException("Missing fixture source: " + task.id());
                }
            }
        }
    }

    private static void verifyInitialFixtures(Path repo, RepairReliabilityManifest manifest) throws IOException {
        Path fixtures = repo.resolve("benchmark/repair-reliability-v1/fixtures").toAbsolutePath().normalize();
        var service = new PostEditVerificationService(fixtures,
                BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
        for (RepairReliabilityTask task : manifest.tasks()) {
            for (String relative : task.expectedFiles().keySet()) {
                Path file = fixtures.resolve(task.fixture()).resolve(relative).normalize();
                if (!file.startsWith(fixtures)) throw new IllegalStateException("Unsafe fixture file: " + task.id());
                var result = service.verify(Path.of(task.fixture()).resolve(relative).toString(), 0);
                if (result.status() != VerificationStatus.PASS) {
                    throw new IllegalStateException("Initial fixture verifier expected PASS for " + task.id()
                            + " but observed " + result.status() + " (" + result.verifierId() + ")");
                }
            }
        }
    }

    private static void verifyLocalVerifiers() throws IOException {
        Path temp = Files.createTempDirectory("repair-reliability-verifier-preflight-");
        try {
            Path pyGood = Files.writeString(temp.resolve("good.py"), "def good():\n    return 1\n");
            Path pyBad = Files.writeString(temp.resolve("bad.py"), "def broken(:\n    return 1\n");
            Path jsGood = Files.writeString(temp.resolve("good.js"), "function good() { return 1; }\n");
            Path jsBad = Files.writeString(temp.resolve("bad.js"), "function broken( { return 1; }\n");
            Path javaGood = Files.writeString(temp.resolve("Good.java"), "class Good {}\n");
            Path javaBad = Files.writeString(temp.resolve("Bad.java"), "class Bad {\n");
            PostEditVerificationService verification = new PostEditVerificationService(temp,
                    BuiltInCodeVerifiers.registry(new DefaultProcessRunner()));
            expect(verification, pyGood, VerificationStatus.PASS);
            expect(verification, pyBad, VerificationStatus.FAIL);
            expect(verification, jsGood, VerificationStatus.PASS);
            expect(verification, jsBad, VerificationStatus.FAIL);
            expect(verification, javaGood, VerificationStatus.PASS);
            expect(verification, javaBad, VerificationStatus.FAIL);
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void expect(PostEditVerificationService verification, Path file, VerificationStatus expected) {
        VerificationStatus actual = verification.verify(file.getFileName().toString(), 0).status();
        if (actual != expected) throw new IllegalStateException("Verifier preflight expected " + expected
                + " but observed " + actual + " for " + file.getFileName());
    }

    private record Args(int round, String benchmarkCommit, boolean allowReal, boolean confirmLive,
                        boolean preflightOnly) {
        private static Args parse(String[] args) {
            Integer round = null;
            String commit = null;
            boolean allowReal = false, confirm = false, preflight = false;
            for (String arg : args) {
                if (arg.startsWith("--round=")) round = Integer.parseInt(arg.substring(8));
                else if (arg.startsWith("--benchmark-commit=")) commit = arg.substring(19);
                else if ("--allow-real".equals(arg)) allowReal = true;
                else if ("--confirm-live".equals(arg)) confirm = true;
                else if ("--preflight-only".equals(arg)) preflight = true;
                else throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            if (round == null || (round != 1 && round != 2)) throw new IllegalArgumentException("--round must be 1 or 2");
            if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) {
                throw new IllegalArgumentException("--benchmark-commit must be a commit hash");
            }
            return new Args(round, commit.toLowerCase(Locale.ROOT), allowReal, confirm, preflight);
        }
    }
}
