package com.agent.environment.verification;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import com.agent.tool.execution.ProcessEnvironmentProfile;
import com.agent.llm.LiveTestControl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class BuiltInCodeVerifiers {
    private BuiltInCodeVerifiers() {
    }

    public static VerifierRegistry registry(ProcessRunner runner) {
        return new VerifierRegistry(List.of(
                new CommandVerifier("python-py-compile", ".py", runner, file ->
                        new Command(List.of("python", "-c", PYTHON_SYNTAX_CHECK, file.toString()), true,
                                ProcessEnvironmentProfile.SCRIPT_RUNTIME)),
                new CommandVerifier("javascript-node-check", ".js", runner, file ->
                        new Command(List.of("node", "--check", file.toString()), false,
                                ProcessEnvironmentProfile.SCRIPT_RUNTIME), ".mjs", ".cjs"),
                new CommandVerifier("c-gcc-syntax", ".c", runner, file ->
                        new Command(List.of("gcc", "-fsyntax-only", file.toString()), false,
                                ProcessEnvironmentProfile.NATIVE_COMPILER)),
                new CommandVerifier("cpp-gxx-syntax", ".cpp", runner, file ->
                        new Command(List.of("g++", "-fsyntax-only", file.toString()), false,
                                ProcessEnvironmentProfile.NATIVE_COMPILER), ".cc", ".cxx"),
                new JavaVerifier(runner)
        ));
    }

    private static final String PYTHON_SYNTAX_CHECK = "import sys, tokenize\n"
            + "try:\n"
            + "    with tokenize.open(sys.argv[1]) as source_file:\n"
            + "        source = source_file.read()\n"
            + "    compile(source, sys.argv[1], 'exec')\n"
            + "except SyntaxError as error:\n"
            + "    print(f'{type(error).__name__}: {error}', file=sys.stderr)\n"
            + "    raise SystemExit(10)\n"
            + "except Exception as error:\n"
            + "    print(f'{type(error).__name__}: {error}', file=sys.stderr)\n"
            + "    raise SystemExit(20)\n";

    @FunctionalInterface
    private interface CommandFactory {
        Command command(Path file);
    }

    private record Command(List<String> arguments, boolean pythonSyntaxCheck,
                           ProcessEnvironmentProfile environmentProfile) {
    }

    private static final class CommandVerifier implements CodeVerifier {
        private static final Duration TIMEOUT = Duration.ofSeconds(15);
        private static final int OUTPUT_LIMIT = 8 * 1024;
        private final String id;
        private final List<String> extensions;
        private final ProcessRunner runner;
        private final CommandFactory commandFactory;

        private CommandVerifier(String id, String extension, ProcessRunner runner,
                                CommandFactory commandFactory, String... extraExtensions) {
            this.id = id;
            this.extensions = new java.util.ArrayList<>();
            this.extensions.add(extension);
            this.extensions.addAll(List.of(extraExtensions));
            this.runner = runner;
            this.commandFactory = commandFactory;
        }

        @Override
        public String id() { return id; }

        @Override
        public boolean supports(Path file, Path workspaceRoot) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return extensions.stream().anyMatch(name::endsWith);
        }

        @Override
        public VerificationResult verify(Path file, Path workspaceRoot, long sequence) {
            Command command = commandFactory.command(file);
            Duration effectiveTimeout = LiveTestControl.boundedProcessTimeout(TIMEOUT);
            if (effectiveTimeout != null && effectiveTimeout.isZero()) {
                return unavailable(file, sequence, id, "Live-test deadline is too close to start verifier");
            }
            if (effectiveTimeout == null) effectiveTimeout = TIMEOUT;
            try {
                ProcessExecutionResult result = runner.run(command.arguments(), workspaceRoot, effectiveTimeout,
                        OUTPUT_LIMIT, java.util.Map.of(), command.environmentProfile());
                if (result.timedOut()) {
                    return unavailable(file, sequence, id, "Verifier exceeded "
                            + effectiveTimeout.toSeconds() + " second timeout");
                }
                if (command.pythonSyntaxCheck() && result.exitCode() == 20) {
                    return new VerificationResult(VerificationStatus.UNAVAILABLE, file, id,
                            result.output(), "Python verifier could not read the source or failed internally", sequence);
                }
                if (command.pythonSyntaxCheck() && result.exitCode() != 0 && result.exitCode() != 10) {
                    return new VerificationResult(VerificationStatus.UNAVAILABLE, file, id,
                            result.output(), "Python verifier exited unexpectedly with code " + result.exitCode(), sequence);
                }
                return new VerificationResult(result.exitCode() == 0 ? VerificationStatus.PASS
                        : VerificationStatus.FAIL, file, id, result.output(), "", sequence);
            } catch (IOException exception) {
                return unavailable(file, sequence, id, "Verifier executable unavailable or could not start");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return unavailable(file, sequence, id, "Verifier execution was interrupted");
            } catch (RuntimeException exception) {
                return unavailable(file, sequence, id, "Verifier failed internally");
            }
        }
    }

    private static final class JavaVerifier implements CodeVerifier {
        private static final Pattern UNCERTAIN_MAVEN_SOURCE_LAYOUT = Pattern.compile(
                "(?is)<parent\\b|<(?:sourcedirectory|testsourcedirectory)\\b|"
                        + "build-helper-maven-plugin|<goal>\\s*add-(?:test-)?source\\s*</goal>"
        );
        private final ProcessRunner runner;

        private JavaVerifier(ProcessRunner runner) { this.runner = runner; }

        @Override
        public String id() { return "java-javac"; }

        @Override
        public boolean supports(Path file, Path workspaceRoot) {
            return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
        }

        @Override
        public VerificationResult verify(Path file, Path workspaceRoot, long sequence) {
            MavenScope scope = mavenScope(file, workspaceRoot);
            if (scope == MavenScope.COVERED_BY_DEFAULT_MAVEN_SOURCES) {
                return new VerificationResult(VerificationStatus.NOT_APPLICABLE, file,
                        "java-maven-project", "", "Project-level verification is delegated to run_maven_test",
                        sequence);
            }
            if (scope == MavenScope.CUSTOM_SOURCE_SCOPE_UNCERTAIN) {
                return new VerificationResult(VerificationStatus.UNAVAILABLE, file,
                        "maven-source-scope-unknown", "",
                        "The Maven source layout is customized or inherited; target coverage cannot be established safely",
                        sequence);
            }
            Path outputDirectory = null;
            try {
                Duration configuredTimeout = Duration.ofSeconds(15);
                Duration effectiveTimeout = LiveTestControl.boundedProcessTimeout(configuredTimeout);
                if (effectiveTimeout != null && effectiveTimeout.isZero()) {
                    return unavailable(file, sequence, id(),
                            "Live-test deadline is too close to start verifier");
                }
                if (effectiveTimeout == null) effectiveTimeout = configuredTimeout;
                outputDirectory = Files.createTempDirectory("agent-javac-verify-");
                ProcessExecutionResult result = runner.run(List.of("javac", "-J-Duser.language=en",
                                "-J-Duser.country=US", "-d",
                                outputDirectory.toString(), file.toString()),
                        workspaceRoot, effectiveTimeout, 8 * 1024, java.util.Map.of(),
                        ProcessEnvironmentProfile.JAVA_TOOL);
                if (result.timedOut()) {
                    return unavailable(file, sequence, id(), "Verifier exceeded "
                            + effectiveTimeout.toSeconds() + " second timeout");
                }
                return new VerificationResult(result.exitCode() == 0 ? VerificationStatus.PASS
                        : VerificationStatus.FAIL, file, id(), result.output(), "", sequence);
            } catch (IOException exception) {
                return unavailable(file, sequence, id(), "Verifier executable unavailable or could not start");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return unavailable(file, sequence, id(), "Verifier execution was interrupted");
            } catch (RuntimeException exception) {
                return unavailable(file, sequence, id(), "Verifier failed internally");
            } finally {
                deleteTemporaryOutput(outputDirectory);
            }
        }

        private static MavenScope mavenScope(Path file, Path workspaceRoot) {
            Path moduleRoot = nearestPomDirectory(file, workspaceRoot);
            if (moduleRoot == null) {
                return MavenScope.NOT_IN_MAVEN_SOURCE_SET;
            }
            Path pom = moduleRoot.resolve("pom.xml");
            String pomContent;
            try {
                pomContent = Files.readString(pom);
            } catch (IOException exception) {
                return MavenScope.CUSTOM_SOURCE_SCOPE_UNCERTAIN;
            }
            if (UNCERTAIN_MAVEN_SOURCE_LAYOUT.matcher(pomContent).find()) {
                return MavenScope.CUSTOM_SOURCE_SCOPE_UNCERTAIN;
            }
            Path relative = moduleRoot.relativize(file.toAbsolutePath().normalize());
            if (startsWithSegments(relative, "src", "main", "java")
                    || startsWithSegments(relative, "src", "test", "java")) {
                return MavenScope.COVERED_BY_DEFAULT_MAVEN_SOURCES;
            }
            return MavenScope.NOT_IN_MAVEN_SOURCE_SET;
        }

        private static Path nearestPomDirectory(Path file, Path workspaceRoot) {
            Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
            Path current = file.toAbsolutePath().normalize().getParent();
            while (current != null && current.startsWith(normalizedRoot)) {
                if (Files.isRegularFile(current.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
                    return current;
                }
                if (current.equals(normalizedRoot)) {
                    break;
                }
                current = current.getParent();
            }
            return null;
        }

        private static boolean startsWithSegments(Path path, String... segments) {
            if (path.getNameCount() < segments.length) {
                return false;
            }
            for (int index = 0; index < segments.length; index++) {
                if (!segments[index].equals(path.getName(index).toString())) {
                    return false;
                }
            }
            return true;
        }

        private enum MavenScope {
            COVERED_BY_DEFAULT_MAVEN_SOURCES,
            NOT_IN_MAVEN_SOURCE_SET,
            CUSTOM_SOURCE_SCOPE_UNCERTAIN
        }
    }

    private static void deleteTemporaryOutput(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Isolated compiler output cleanup must not change the verification result.
        }
    }

    private static VerificationResult unavailable(Path file, long sequence, String id, String reason) {
        return new VerificationResult(VerificationStatus.UNAVAILABLE, file, id, "", reason, sequence);
    }
}
