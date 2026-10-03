package com.agent.environment.verification;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public final class BuiltInCodeVerifiers {
    private BuiltInCodeVerifiers() {
    }

    public static VerifierRegistry registry(ProcessRunner runner) {
        return new VerifierRegistry(List.of(
                new CommandVerifier("python-py-compile", ".py", runner, file ->
                        new Command(List.of("python", "-c", PYTHON_SYNTAX_CHECK, file.toString()), true)),
                new CommandVerifier("javascript-node-check", ".js", runner, file ->
                        new Command(List.of("node", "--check", file.toString()), false), ".mjs", ".cjs"),
                new CommandVerifier("c-gcc-syntax", ".c", runner, file ->
                        new Command(List.of("gcc", "-fsyntax-only", file.toString()), false)),
                new CommandVerifier("cpp-gxx-syntax", ".cpp", runner, file ->
                        new Command(List.of("g++", "-fsyntax-only", file.toString()), false), ".cc", ".cxx"),
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

    private record Command(List<String> arguments, boolean pythonSyntaxCheck) {
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
            try {
                ProcessExecutionResult result = runner.run(command.arguments(), workspaceRoot, TIMEOUT,
                        OUTPUT_LIMIT);
                if (result.timedOut()) {
                    return unavailable(file, sequence, id, "Verifier exceeded 15 second timeout");
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
            if (Files.isRegularFile(workspaceRoot.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
                return new VerificationResult(VerificationStatus.NOT_APPLICABLE, file,
                        "java-maven-project", "", "Project-level verification is delegated to run_maven_test",
                        sequence);
            }
            Path outputDirectory = null;
            try {
                outputDirectory = Files.createTempDirectory("agent-javac-verify-");
                ProcessExecutionResult result = runner.run(List.of("javac", "-d",
                                outputDirectory.toString(), file.toString()),
                        workspaceRoot, Duration.ofSeconds(15), 8 * 1024);
                if (result.timedOut()) {
                    return unavailable(file, sequence, id(), "Verifier exceeded 15 second timeout");
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
