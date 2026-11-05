package com.agent.tool.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEnvironmentPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void mavenProfileKeepsOnlyRequiredWindowsRuntimeVariablesAndControlledJavaOptions() {
        Map<String, String> parent = sensitiveAndWindowsEnvironment();

        Map<String, String> child = ProcessEnvironmentPolicy.create(
                ProcessEnvironmentProfile.MAVEN, parent, Map.of(), true);

        assertEquals("C:\\Windows", child.get("SystemRoot"));
        assertEquals("C:\\Windows", child.get("WINDIR"));
        assertEquals("C:\\tools;C:\\Windows\\System32", child.get("PATH"));
        assertEquals(".COM;.EXE;.BAT;.CMD", child.get("PATHEXT"));
        assertEquals("C:\\Temp", child.get("TEMP"));
        assertEquals("C:\\Temp", child.get("TMP"));
        assertEquals("C:\\jdk", child.get("JAVA_HOME"));
        assertEquals("C:\\Windows\\System32\\cmd.exe", child.get("COMSPEC"));
        assertEquals("-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8",
                child.get("JAVA_TOOL_OPTIONS"));
        assertFalse(child.containsKey("USERPROFILE"));
        assertFalse(child.containsKey("HTTP_PROXY"));
        assertFalse(child.containsKey("_JAVA_OPTIONS"));
        assertFalse(child.containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(child.containsKey("MAVEN_OPTS"));
        assertNoSensitiveNames(child);
    }

    @Test
    void scriptAndNativeProfilesDoNotReceiveJavaHomeOrUnknownVariables() {
        Map<String, String> parent = sensitiveAndWindowsEnvironment();

        Map<String, String> script = ProcessEnvironmentPolicy.create(
                ProcessEnvironmentProfile.SCRIPT_RUNTIME, parent, Map.of(), true);
        Map<String, String> nativeCompiler = ProcessEnvironmentPolicy.create(
                ProcessEnvironmentProfile.NATIVE_COMPILER, parent, Map.of(), true);

        assertTrue(script.containsKey("PATH"));
        assertFalse(script.containsKey("JAVA_HOME"));
        assertTrue(nativeCompiler.containsKey("PATH"));
        assertFalse(nativeCompiler.containsKey("JAVA_HOME"));
        assertFalse(script.containsKey("AGENT_TEST_UNKNOWN"));
        assertFalse(nativeCompiler.containsKey("AGENT_TEST_UNKNOWN"));
        assertNoSensitiveNames(script);
        assertNoSensitiveNames(nativeCompiler);
    }

    @Test
    void onlyCodeDefinedAllowlistedOverridesAreApplied() {
        Map<String, String> overrides = Map.of(
                "PATH", "C:\\approved-tools",
                "GLM_API_KEY", "fake-credential",
                "AGENT_TEST_UNKNOWN", "untrusted"
        );

        Map<String, String> child = ProcessEnvironmentPolicy.create(
                ProcessEnvironmentProfile.MAVEN, sensitiveAndWindowsEnvironment(), overrides, true);

        assertEquals("C:\\approved-tools", child.get("PATH"));
        assertFalse(child.containsKey("GLM_API_KEY"));
        assertFalse(child.containsKey("AGENT_TEST_UNKNOWN"));
    }

    @Test
    void missingEnvironmentValuesDoNotCauseFailureOrInventParentConfiguration() {
        Map<String, String> child = ProcessEnvironmentPolicy.create(
                ProcessEnvironmentProfile.SCRIPT_RUNTIME, Map.of(), Map.of(), true);

        assertEquals(1, child.size());
        assertTrue(child.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(child.containsKey("PATH"));
        assertFalse(child.containsKey("TEMP"));
    }

    @Test
    void actualJavaChildReceivesSanitizedEnvironment() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("GLM_API_KEY", "fake-glm-secret");
        parent.put("DEEPSEEK_API_KEY", "fake-deepseek-secret");
        parent.put("MODEL_API_KEY", "fake-model-secret");
        parent.put("AWS_SECRET_ACCESS_KEY", "fake-cloud-secret");
        parent.put("HTTP_PROXY", "http://user:password@proxy.invalid");
        parent.put("JAVA_TOOL_OPTIONS", "-Dagent.secret=must-not-survive");
        parent.put("JDK_JAVA_OPTIONS", "-Dagent.injected=must-not-survive");
        parent.put("_JAVA_OPTIONS", "-Dagent.injected=must-not-survive");
        parent.put("MAVEN_OPTS", "-Dagent.injected=must-not-survive");
        parent.put("AGENT_TEST_UNKNOWN", "must-not-survive");
        DefaultProcessRunner runner = new DefaultProcessRunner(parent);

        ProcessExecutionResult result = runner.run(
                List.of(javaExecutable(), "-cp", absoluteClassPath(), EnvironmentProbe.class.getName()),
                temporary,
                Duration.ofSeconds(15),
                8 * 1024,
                Map.of(),
                ProcessEnvironmentProfile.JAVA_TOOL
        );

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.output().contains("secretVariablesAbsent=true"));
        assertTrue(result.output().contains("baseVariablesPresent=true"));
        assertTrue(result.output().contains("javaOptionsControlled=true"));
        assertTrue(result.output().contains("userHomePropertyPresent=true"));
        assertFalse(result.output().contains("fake-glm-secret"));
        assertFalse(result.output().contains("proxy.invalid"));
    }

    private static Map<String, String> sensitiveAndWindowsEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put("SystemRoot", "C:\\Windows");
        values.put("WINDIR", "C:\\Windows");
        values.put("PATH", "C:\\tools;C:\\Windows\\System32");
        values.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        values.put("TEMP", "C:\\Temp");
        values.put("TMP", "C:\\Temp");
        values.put("JAVA_HOME", "C:\\jdk");
        values.put("USERPROFILE", "C:\\Users\\test");
        values.put("GLM_API_KEY", "fake-glm-secret");
        values.put("DEEPSEEK_API_KEY", "fake-deepseek-secret");
        values.put("MODEL_API_KEY", "fake-model-secret");
        values.put("AWS_ACCESS_KEY_ID", "fake-cloud-id");
        values.put("AWS_SECRET_ACCESS_KEY", "fake-cloud-secret");
        values.put("AZURE_CLIENT_SECRET", "fake-cloud-secret");
        values.put("GITHUB_TOKEN", "fake-token");
        values.put("HTTP_PROXY", "http://user:password@proxy.invalid");
        values.put("HTTPS_PROXY", "http://user:password@proxy.invalid");
        values.put("JAVA_TOOL_OPTIONS", "-Dagent.injected=true");
        values.put("JDK_JAVA_OPTIONS", "-Dagent.injected=true");
        values.put("_JAVA_OPTIONS", "-Dagent.injected=true");
        values.put("MAVEN_OPTS", "-Dagent.injected=true");
        values.put("AGENT_TEST_UNKNOWN", "unknown");
        return values;
    }

    private static void assertNoSensitiveNames(Map<String, String> environment) {
        for (String name : environment.keySet()) {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            assertFalse(normalized.contains("api_key"));
            assertFalse(normalized.contains("secret"));
            assertFalse(normalized.contains("token"));
            assertFalse(normalized.equals("http_proxy"));
            assertFalse(normalized.equals("https_proxy"));
            assertFalse(normalized.equals("userprofile"));
            assertFalse(normalized.equals("maven_opts"));
            assertFalse(normalized.equals("jdk_java_options"));
            assertFalse(normalized.equals("_java_options"));
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String absoluteClassPath() {
        String current = System.getProperty("java.class.path");
        String[] entries = current.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        List<String> absolute = new ArrayList<>();
        Path base = Path.of("").toAbsolutePath().normalize();
        for (String entry : entries) {
            Path path = Path.of(entry);
            absolute.add((path.isAbsolute() ? path : base.resolve(path)).normalize().toString());
        }
        return String.join(java.io.File.pathSeparator, absolute);
    }

    public static final class EnvironmentProbe {
        public static void main(String[] args) {
            Map<String, String> environment = System.getenv();
            boolean secretAbsent = List.of("GLM_API_KEY", "DEEPSEEK_API_KEY", "MODEL_API_KEY",
                            "AWS_SECRET_ACCESS_KEY", "HTTP_PROXY", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                            "MAVEN_OPTS", "AGENT_TEST_UNKNOWN")
                    .stream().noneMatch(environment::containsKey);
            boolean basePresent = environment.containsKey(isWindows() ? "PATH" : "PATH")
                    && environment.containsKey("JAVA_TOOL_OPTIONS");
            boolean controlled = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
                    .equals(environment.get("JAVA_TOOL_OPTIONS"));
            System.out.println("secretVariablesAbsent=" + secretAbsent);
            System.out.println("baseVariablesPresent=" + basePresent);
            System.out.println("javaOptionsControlled=" + controlled);
            System.out.println("userHomePropertyPresent=" + (System.getProperty("user.home") != null
                    && !System.getProperty("user.home").isBlank()));
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        }
    }
}
