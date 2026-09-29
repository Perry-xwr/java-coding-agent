package com.agent.benchmark.v12;

import com.agent.CliMode;
import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V12BenchmarkProtocolTest {
    @TempDir Path temporary;

    @Test
    void loadsFrozenThirtyTaskProtocolWithDistinctFixturesAndCoverage() throws Exception {
        V12BenchmarkSuite suite = new V12BenchmarkTaskLoader().load(manifest());

        assertEquals("v1.2", suite.benchmarkVersion());
        assertEquals(30, suite.tasks().size());
        assertEquals(10, suite.tasks().stream().filter(t -> t.split() == V12Split.DEV).count());
        assertEquals(20, suite.tasks().stream().filter(t -> t.split() == V12Split.TEST).count());
        assertEquals(30, suite.tasks().stream().map(V12Task::id).distinct().count());
        Set<String> capabilities = suite.tasks().stream().flatMap(t -> t.capabilities().stream()).collect(java.util.stream.Collectors.toSet());
        assertTrue(capabilities.containsAll(Set.of("AUTO", "READ", "CODE", "FOLLOW_UP", "find_files", "search_code", "create_file", "apply_patch", "insert_before", "insert_after", "maven_verification", "maven_recovery", "ambiguous_discovery", "post_edit_convergence", "fresh_read_guard", "two_file")));
        assertTrue(suite.tasks().stream().anyMatch(t -> t.evaluator() == V12Evaluator.HIDDEN_MAVEN_WITH_RECOVERY));
        assertTrue(suite.tasks().stream().filter(t -> t.split() == V12Split.TEST).allMatch(t -> !t.successCriteria().isEmpty()));
        assertTrue(Files.isRegularFile(Path.of("benchmark", "v1.2", "evaluator", "README.md")));
        var checks = new V12EvaluationCheckLoader().load(Path.of("benchmark", "v1.2", "evaluator", "checks.json"));
        assertEquals(30, checks.size());
        assertEquals(suite.tasks().stream().map(V12Task::id).collect(java.util.stream.Collectors.toSet()), checks.keySet());
    }

    @Test
    void resetsFixturesWithoutPlacingEvaluatorMaterialInAgentWorkspace() throws Exception {
        V12BenchmarkSuite suite = new V12BenchmarkTaskLoader().load(manifest());
        V12Task task = suite.tasks().stream().filter(t -> t.id().equals("test_05_find_python_files")).findFirst().orElseThrow();
        V12FixtureWorkspace workspaces = new V12FixtureWorkspace();
        Path workspace = workspaces.reset(task, temporary.resolve("runs"), "offline");
        assertTrue(Files.isRegularFile(workspace.resolve("scripts/a.py")));
        assertFalse(Files.exists(workspace.resolve(".hidden")));
        Files.writeString(workspace.resolve("scripts/a.py"), "changed");
        Path reset = workspaces.reset(task, temporary.resolve("runs"), "offline");
        assertTrue(Files.readString(reset.resolve("scripts/a.py")).contains("def a"));
    }

    @Test
    void harnessReusesCliRouterAndThreeProfilesWithoutInteractiveStdin() {
        LLMClient unused = messages -> new LLMResponse("unused", java.util.List.of());
        V12RuntimeHarness harness = new V12RuntimeHarness(unused, temporary);
        assertEquals(CliMode.CHAT, harness.route("Java 和 C++ 有什么区别").mode());
        assertEquals(CliMode.READ, harness.route("读取 README.md").mode());
        assertEquals(CliMode.CODE, harness.route("修复 Calculator.java").mode());
        assertTrue(harness.sessionFor(harness.route("读取 README.md")) == harness.sessions().read());
    }

    private static Path manifest() {
        return Path.of("benchmark", "v1.2", "manifest.json").toAbsolutePath().normalize();
    }
}
