package com.agent.benchmark.repairreliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Captures and verifies the immutable source/task identity used by live rounds. */
public final class RepairReliabilityFreezeIdentity {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private RepairReliabilityFreezeIdentity() { }

    public static Path write(Path repository, String benchmarkCommit) throws IOException {
        Path repo = repository.toAbsolutePath().normalize();
        Path output = repo.resolve("benchmark-runs/repair-reliability-v1")
                .resolve(benchmarkCommit).resolve("pre-live/freeze-identities.json");
        if (Files.exists(output)) throw new IOException("freeze identity already exists: " + output);
        Map<String, String> hashes = collect(repo);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("benchmarkCommit", benchmarkCommit);
        document.put("algorithm", "SHA-256");
        document.put("files", hashes);
        Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), document);
        return output;
    }

    public static void verify(Path repository, String benchmarkCommit, Path identityFile) throws IOException {
        Path repo = repository.toAbsolutePath().normalize();
        @SuppressWarnings("unchecked") Map<String, Object> document = JSON.readValue(identityFile.toFile(), Map.class);
        if (!benchmarkCommit.equals(document.get("benchmarkCommit"))) {
            throw new IOException("freeze identity commit does not match requested benchmark commit");
        }
        @SuppressWarnings("unchecked") Map<String, String> expected = (Map<String, String>) document.get("files");
        if (expected == null || expected.isEmpty()) throw new IOException("freeze identity has no file hashes");
        Map<String, String> actual = collect(repo);
        if (!expected.equals(actual)) throw new IOException("frozen source, task, or fixture identity changed");
    }

    private static Map<String, String> collect(Path repo) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (String relative : new String[]{
                "src/main/java/com/agent/agent/Agent.java",
                "src/main/java/com/agent/agent/AgentProgress.java",
                "src/main/java/com/agent/agent/VerificationFailureContext.java",
                "src/main/java/com/agent/agent/RepairDirective.java",
                "src/main/java/com/agent/environment/verification/DiagnosticLocation.java",
                "src/main/java/com/agent/agent/VerificationRepairPolicy.java",
                "src/main/java/com/agent/CliAgentFactory.java",
                "src/main/java/com/agent/environment/verification/VerificationDiagnosticParser.java",
                "src/main/java/com/agent/environment/verification/VerificationCapability.java",
                "src/main/java/com/agent/environment/verification/PostEditVerificationService.java",
                "src/main/java/com/agent/environment/verification/BuiltInCodeVerifiers.java"
        }) add(repo, hashes, Path.of(relative));
        Path packageRoot = repo.resolve("src/main/java/com/agent/benchmark/repairreliability");
        try (var files = Files.list(packageRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                add(repo, hashes, repo.relativize(file));
            }
        }
        Path benchmarkRoot = repo.resolve("benchmark/repair-reliability-v1");
        try (var files = Files.walk(benchmarkRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                add(repo, hashes, repo.relativize(file));
            }
        }
        return hashes;
    }

    private static void add(Path repo, Map<String, String> target, Path relative) throws IOException {
        Path file = repo.resolve(relative).normalize();
        if (!file.startsWith(repo) || !Files.isRegularFile(file)) throw new IOException("required freeze file missing: " + relative);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            target.put(relative.toString().replace('\\', '/'), HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <repository> <benchmark-commit>");
        System.out.println(write(Path.of(args[0]), args[1]));
    }
}
