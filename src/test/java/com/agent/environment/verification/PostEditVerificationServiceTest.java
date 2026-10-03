package com.agent.environment.verification;

import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostEditVerificationServiceTest {
    @TempDir
    Path workspace;

    @Test
    void selectsFixedCommandsForSupportedLanguagesAndBoundsDiagnostics() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        ProcessRunner runner = (command, cwd, timeout, outputLimit) -> {
            commands.add(command);
            return new ProcessExecutionResult(0, false, "ok", false, 2);
        };
        PostEditVerificationService service = service(runner);
        for (String name : List.of("a.py", "b.mjs", "c.c", "d.cc", "e.java")) {
            Files.writeString(workspace.resolve(name), "valid");
        }
        // Standalone Java is checked with javac; other language command lines are fixed by the registry.
        assertEquals(VerificationStatus.PASS, service.verify("a.py", 1).status());
        assertEquals(VerificationStatus.PASS, service.verify("b.mjs", 2).status());
        assertEquals(VerificationStatus.PASS, service.verify("c.c", 3).status());
        assertEquals(VerificationStatus.PASS, service.verify("d.cc", 4).status());
        assertEquals(VerificationStatus.PASS, service.verify("e.java", 5).status());
        assertEquals(List.of("python", "-c"), commands.get(0).subList(0, 2));
        assertTrue(commands.get(0).get(2).contains("compile(source, sys.argv[1], 'exec')"));
        assertEquals(workspace.resolve("a.py").toString(), commands.get(0).get(3));
        assertEquals(List.of("node", "--check"), commands.get(1).subList(0, 2));
        assertEquals(List.of("gcc", "-fsyntax-only"), commands.get(2).subList(0, 2));
        assertEquals(List.of("g++", "-fsyntax-only"), commands.get(3).subList(0, 2));
        assertEquals("javac", commands.get(4).get(0));
    }

    @Test
    void exitCodeFailureIsFailWhileMissingExecutableAndTimeoutAreUnavailable() throws Exception {
        Files.writeString(workspace.resolve("broken.py"), "def x(:\n");
        ProcessRunner syntaxFailure = (command, cwd, timeout, outputLimit) ->
                new ProcessExecutionResult(10, false, "SyntaxError: invalid syntax\n".repeat(500), false, 4);
        VerificationResult failed = service(syntaxFailure).verify("broken.py", 7);
        assertEquals(VerificationStatus.FAIL, failed.status());
        assertTrue(failed.diagnosticSummary().contains("SyntaxError"));
        assertTrue(failed.diagnosticSummary().length() <= PostEditVerificationService.MAX_DIAGNOSTIC_CHARS + 16);

        ProcessRunner unavailable = (command, cwd, timeout, outputLimit) -> {
            throw new IOException("not installed");
        };
        VerificationResult missing = service(unavailable).verify("broken.py", 8);
        assertEquals(VerificationStatus.UNAVAILABLE, missing.status());
        assertFalse(missing.unavailableReason().isBlank());

        for (String name : List.of("broken.js", "broken.c", "broken.cpp", "Broken.java")) {
            Files.writeString(workspace.resolve(name), "invalid");
            assertEquals(VerificationStatus.UNAVAILABLE, service(unavailable).verify(name, 8).status(), name);
        }

        ProcessRunner timeoutRunner = (command, cwd, timeout, outputLimit) ->
                new ProcessExecutionResult(-1, true, "", false, timeout.toMillis());
        assertEquals(VerificationStatus.UNAVAILABLE,
                service(timeoutRunner).verify("broken.py", 9).status());
    }

    @Test
    void nonZeroCompilerExitIsFailForEachSupportedLanguage() throws Exception {
        ProcessRunner invalid = (command, cwd, timeout, outputLimit) ->
                new ProcessExecutionResult(command.get(0).equals("python") ? 10 : 1,
                        false, "syntax error", false, 1);
        for (String name : List.of("invalid.py", "invalid.js", "invalid.c", "invalid.cpp", "Invalid.java")) {
            Files.writeString(workspace.resolve(name), "invalid");
            assertEquals(VerificationStatus.FAIL, service(invalid).verify(name, 1).status(), name);
        }
    }

    @Test
    void diagnosticsAreRedactedAndDeduplicated() throws Exception {
        Files.writeString(workspace.resolve("secret.py"), "pass\n");
        ProcessRunner runner = (command, cwd, timeout, outputLimit) ->
                new ProcessExecutionResult(1, false,
                        "api_key=DO_NOT_LEAK\nAuthorization: Bearer hidden-token\n"
                                + "SyntaxError: bad\nSyntaxError: bad\n",
                        false, 1);
        String diagnostic = service(runner).verify("secret.py", 1).diagnosticSummary();
        assertFalse(diagnostic.contains("DO_NOT_LEAK"));
        assertFalse(diagnostic.contains("hidden-token"));
        assertEquals(1, diagnostic.split("SyntaxError: bad", -1).length - 1);
    }

    @Test
    void nonCodeAndMavenJavaFilesAreNotApplicable() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "text");
        Files.writeString(workspace.resolve("App.java"), "class App {}\n");
        assertEquals(VerificationStatus.NOT_APPLICABLE,
                service((c, d, t, l) -> failIfCalled()).verify("README.md", 1).status());

        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        assertEquals(VerificationStatus.NOT_APPLICABLE,
                service((c, d, t, l) -> failIfCalled()).verify("App.java", 2).status());
    }

    @Test
    void traversalAndDiagnosticsDoNotEscapeWorkspaceOrExceedBound() throws Exception {
        Path outside = Files.createFile(workspace.getParent().resolve("outside.py"));
        try {
            assertEquals(VerificationStatus.UNAVAILABLE,
                    service((c, d, t, l) -> failIfCalled()).verify("../outside.py", 1).status());
        } finally {
            Files.deleteIfExists(outside);
        }
        Files.writeString(workspace.resolve("long.py"), "pass\n");
        ProcessRunner longOutput = (command, cwd, timeout, outputLimit) ->
                new ProcessExecutionResult(1, false, "x".repeat(outputLimit * 4), false, 1);
        assertTrue(service(longOutput).verify("long.py", 2).diagnosticSummary().length()
                <= PostEditVerificationService.MAX_DIAGNOSTIC_CHARS + 16);
    }

    @Test
    void pythonVerifierDoesNotSetCacheEnvironmentOverrides() throws Exception {
        Files.writeString(workspace.resolve("cache.py"), "pass\n");
        AtomicReference<Map<String, String>> seen = new AtomicReference<>(Map.of());
        ProcessRunner runner = new ProcessRunner() {
            @Override public ProcessExecutionResult run(List<String> command, Path cwd, Duration timeout, int limit) {
                seen.set(Map.of());
                return new ProcessExecutionResult(0, false, "", false, 1);
            }
            @Override public ProcessExecutionResult run(List<String> command, Path cwd, Duration timeout, int limit,
                                                        Map<String, String> environment) {
                seen.set(environment);
                return new ProcessExecutionResult(0, false, "", false, 1);
            }
        };
        assertEquals(VerificationStatus.PASS, service(runner).verify("cache.py", 1).status());
        assertTrue(seen.get().isEmpty());
    }

    @Test
    void localPythonVerifierChecksValidAndInvalidSourceWhenAvailable() throws Exception {
        Files.writeString(workspace.resolve("valid.py"), "def add(a, b):\n    return a + b\n");
        Files.writeString(workspace.resolve("invalid.py"), "def add(a, b\n    return a + b\n");
        PostEditVerificationService service = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(new com.agent.tool.execution.DefaultProcessRunner()));
        VerificationResult valid = service.verify("valid.py", 1);
        assumeTrue(valid.status() != VerificationStatus.UNAVAILABLE, valid.unavailableReason());
        assertEquals(VerificationStatus.PASS, valid.status());
        assertEquals(VerificationStatus.FAIL, service.verify("invalid.py", 2).status());
        assertFalse(Files.exists(workspace.resolve("__pycache__")));
        try (var paths = Files.walk(workspace)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".pyc")));
        }
    }

    @Test
    void pythonHelperMapsOnlySyntaxExitToFailAndInfrastructureExitToUnavailable() throws Exception {
        Files.writeString(workspace.resolve("source.py"), "pass\n");
        ProcessRunner syntax = (command, cwd, timeout, limit) ->
                new ProcessExecutionResult(10, false, "SyntaxError: invalid syntax", false, 1);
        ProcessRunner infra = (command, cwd, timeout, limit) ->
                new ProcessExecutionResult(20, false, "PermissionError: denied", false, 1);
        ProcessRunner unexpected = (command, cwd, timeout, limit) ->
                new ProcessExecutionResult(1, false, "unexpected", false, 1);
        assertEquals(VerificationStatus.FAIL, service(syntax).verify("source.py", 1).status());
        assertEquals(VerificationStatus.UNAVAILABLE, service(infra).verify("source.py", 1).status());
        assertEquals(VerificationStatus.UNAVAILABLE, service(unexpected).verify("source.py", 1).status());
    }

    @Test
    void pythonVerifierWorksOnDeepLongPathsWithoutCreatingCacheArtifacts() throws Exception {
        Path deep = workspace;
        for (int index = 0; index < 5; index++) {
            deep = deep.resolve("long-fixture-directory-segment-" + index);
        }
        Files.createDirectories(deep);
        Path valid = deep.resolve("valid_source.py");
        Path invalid = deep.resolve("invalid_source.py");
        Files.writeString(valid, "# coding: utf-8\ndef café():\n    return 'ok'\n");
        Files.writeString(invalid, "def café(:\n    return 'broken'\n");
        assertTrue(valid.toString().length() > 200, "test path should exercise deep-path behavior");
        assertTrue(valid.toString().length() < 260,
                "source path itself stays in legacy Windows path range; old pycache mirroring exceeded it");

        PostEditVerificationService local = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(new com.agent.tool.execution.DefaultProcessRunner()));
        VerificationResult validResult = local.verify(workspace.relativize(valid).toString(), 1);
        VerificationResult invalidResult = local.verify(workspace.relativize(invalid).toString(), 2);
        assumeTrue(validResult.status() != VerificationStatus.UNAVAILABLE, validResult.unavailableReason());
        assertEquals(VerificationStatus.PASS, validResult.status(), validResult.toString());
        assertEquals(VerificationStatus.FAIL, invalidResult.status(), invalidResult.toString());
        try (var paths = Files.walk(workspace)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".pyc")
                    || path.getFileName().toString().equals("__pycache__")));
        }
    }

    @Test
    void localNodeVerifierChecksValidAndInvalidSourceWhenAvailable() throws Exception {
        Files.writeString(workspace.resolve("valid.mjs"), "const add = (a, b) => a + b;\n");
        Files.writeString(workspace.resolve("invalid.mjs"), "const add = (a, b) => ;\n");
        PostEditVerificationService service = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(new com.agent.tool.execution.DefaultProcessRunner()));
        VerificationResult valid = service.verify("valid.mjs", 1);
        assumeTrue(valid.status() != VerificationStatus.UNAVAILABLE, valid.unavailableReason());
        assertEquals(VerificationStatus.PASS, valid.status());
        assertEquals(VerificationStatus.FAIL, service.verify("invalid.mjs", 2).status());
    }

    @Test
    void localJavacVerifierChecksValidAndInvalidSourceWhenAvailable() throws Exception {
        Files.writeString(workspace.resolve("Valid.java"), "class Valid { int add(int a, int b) { return a+b; } }\n");
        Files.writeString(workspace.resolve("Invalid.java"), "class Invalid { int add( { return 1; } }\n");
        PostEditVerificationService service = new PostEditVerificationService(workspace,
                BuiltInCodeVerifiers.registry(new com.agent.tool.execution.DefaultProcessRunner()));
        VerificationResult valid = service.verify("Valid.java", 1);
        assumeTrue(valid.status() != VerificationStatus.UNAVAILABLE, valid.unavailableReason());
        assertEquals(VerificationStatus.PASS, valid.status());
        assertEquals(VerificationStatus.FAIL, service.verify("Invalid.java", 2).status());
        try (var paths = Files.list(workspace)) {
            assertEquals(List.of(), paths.filter(path -> path.getFileName().toString()
                    .endsWith(".class")).toList());
        }
    }

    private PostEditVerificationService service(ProcessRunner runner) {
        return new PostEditVerificationService(workspace, BuiltInCodeVerifiers.registry(runner));
    }

    private static ProcessExecutionResult failIfCalled() {
        throw new AssertionError("No process should run for this file");
    }
}
