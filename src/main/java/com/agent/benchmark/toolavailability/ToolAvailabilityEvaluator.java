package com.agent.benchmark.toolavailability;

import com.agent.agent.AgentTrajectory;
import com.agent.environment.verification.BuiltInCodeVerifiers;
import com.agent.environment.verification.PostEditVerificationService;
import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.RunMavenTestTool;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Evaluates workspace state and runs a fresh independent syntax/build check; it ignores trajectory verification claims. */
public final class ToolAvailabilityEvaluator {
    private final ProcessRunner processRunner;

    public ToolAvailabilityEvaluator() { this(new DefaultProcessRunner()); }

    ToolAvailabilityEvaluator(ProcessRunner processRunner) { this.processRunner = processRunner; }

    public ToolAvailabilityEvaluation evaluate(ToolAvailabilityTask task, Path workspace, Path fixture,
                                               boolean conversationalCompletion, Path localMavenRepository) {
        List<String> failures = new ArrayList<>();
        boolean targetDrift = false;
        try {
            for (Map.Entry<String, List<String>> assertion : task.expectedFileContains().entrySet()) {
                Path file = safeResolve(workspace, assertion.getKey());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    failures.add("missing expected file " + assertion.getKey());
                    continue;
                }
                String content = Files.readString(file);
                for (String expected : assertion.getValue()) {
                    if (!content.contains(expected)) failures.add("expected content absent in " + assertion.getKey());
                }
            }
            Set<String> allowed = task.allowedMutationTargets();
            List<String> changed = changedFiles(fixture, workspace);
            for (String relative : changed) {
                if (isGenerated(relative)) continue;
                if (!allowed.contains(relative)) {
                    failures.add("unexpected workspace change " + relative);
                    targetDrift = true;
                }
            }
            boolean finalPom = Files.isRegularFile(workspace.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
            boolean initialPom = Files.isRegularFile(fixture.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
            if (!initialPom && finalPom && !task.allowPomCreation()) {
                failures.add("unexpected pom.xml creation in non-Maven task");
                targetDrift = true;
            }
            if (!conversationalCompletion) failures.add("Agent did not complete conversationally");

            Validation validation = validateIndependently(task, workspace, localMavenRepository);
            if (!"PASS".equals(validation.status())) failures.add("independent validation " + validation.status());
            String workspaceOutcome = targetDrift ? "TARGET_DRIFT" : failures.isEmpty() ? "PASS" : "CONTENT_OR_COMPLETION_FAILURE";
            return new ToolAvailabilityEvaluation(failures.isEmpty() && "PASS".equals(validation.status()),
                    conversationalCompletion, workspaceOutcome, validation.status(), targetDrift,
                    failures, validation.infrastructureError(), validation.infrastructureCategory());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new ToolAvailabilityEvaluation(false, conversationalCompletion, "EVALUATOR_ERROR", "UNAVAILABLE",
                    targetDrift, append(failures, "evaluator error: " + message),
                    message, "EVALUATOR_OR_WORKSPACE_FAILURE");
        }
    }

    private Validation validateIndependently(ToolAvailabilityTask task, Path workspace, Path localRepository) {
        if (task.workspaceClass() == ToolAvailabilityWorkspaceClass.MAVEN_JAVA) {
            var result = new RunMavenTestTool(workspace, processRunner, localRepository)
                    .execute("{}");
            if (result.success()) return new Validation("PASS", null, null);
            String output = (result.errorMessage() + " " + result.output()).toLowerCase(java.util.Locale.ROOT);
            boolean dependencyInfra = output.contains("could not resolve dependencies")
                    || output.contains("could not transfer artifact")
                    || output.contains("plugin could not be resolved")
                    || output.contains("permission denied")
                    || output.contains("access is denied");
            return dependencyInfra
                    ? new Validation("UNAVAILABLE", result.errorMessage(), "MAVEN_DEPENDENCY_INFRASTRUCTURE")
                    : new Validation("FAIL", null, null);
        }
        if (task.workspaceClass() == ToolAvailabilityWorkspaceClass.STANDALONE_JAVA) {
            Path output = null;
            try {
                Path scratch = Path.of("target").toAbsolutePath().normalize();
                Files.createDirectories(scratch);
                output = Files.createTempDirectory(scratch, "tool-availability-javac-");
                for (String relative : task.expectedFileContains().keySet()) {
                    Path source = safeResolve(workspace, relative);
                    ProcessExecutionResult result = processRunner.run(
                            List.of("javac", "-d", output.toString(), source.toString()),
                            workspace, Duration.ofSeconds(30), 16 * 1024);
                    if (result.timedOut()) return new Validation("UNAVAILABLE", "javac timed out", "VERIFIER_UNAVAILABLE");
                    if (result.exitCode() != 0) return new Validation("FAIL", null, null);
                }
                return new Validation("PASS", null, null);
            } catch (IOException exception) {
                return new Validation("UNAVAILABLE", exception.getClass().getSimpleName(), "VERIFIER_UNAVAILABLE");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new Validation("UNAVAILABLE", "javac interrupted", "VERIFIER_UNAVAILABLE");
            } finally {
                if (output != null) {
                    try (Stream<Path> paths = Files.walk(output)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    } catch (IOException ignored) { }
                }
            }
        }
        PostEditVerificationService verifier = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(processRunner));
        for (String relative : task.expectedFileContains().keySet()) {
            VerificationResult result = verifier.verify(relative, 0);
            if (result.status() == VerificationStatus.UNAVAILABLE) {
                return new Validation("UNAVAILABLE", result.unavailableReason(), "VERIFIER_UNAVAILABLE");
            }
            if (result.status() != VerificationStatus.PASS) {
                return new Validation("FAIL", null, null);
            }
        }
        return new Validation("PASS", null, null);
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path path = Path.of(relative);
        if (path.isAbsolute()) throw new IOException("Evaluator target must be relative");
        Path resolved = normalizedRoot.resolve(path).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IOException("Evaluator target escaped workspace");
        return resolved;
    }

    private static List<String> changedFiles(Path fixture, Path workspace) throws IOException {
        Map<String, byte[]> before = contents(fixture);
        Map<String, byte[]> after = contents(workspace);
        List<String> paths = new ArrayList<>();
        java.util.Set<String> all = new java.util.HashSet<>(before.keySet());
        all.addAll(after.keySet());
        for (String path : all) {
            if (!java.util.Arrays.equals(before.get(path), after.get(path))) paths.add(path);
        }
        paths.sort(String::compareTo);
        return paths;
    }

    private static Map<String, byte[]> contents(Path root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!isGenerated(relative)) files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    private static boolean isGenerated(String path) {
        return path.startsWith("target/") || path.startsWith(".m2/")
                || path.startsWith(".git/") || path.startsWith("__pycache__/")
                || path.contains("/__pycache__/");
    }

    private static List<String> append(List<String> values, String value) {
        List<String> all = new ArrayList<>(values);
        all.add(value);
        return all;
    }

    private record Validation(String status, String infrastructureError, String infrastructureCategory) { }
}
