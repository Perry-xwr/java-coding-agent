package com.agent.benchmark.v12;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.TerminationReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic post-run evaluator; it never calls an LLM. */
public final class V12DeterministicEvaluator {
    private final V12HiddenMavenVerifier hiddenMavenVerifier;

    public V12DeterministicEvaluator() { this(null); }
    public V12DeterministicEvaluator(V12HiddenMavenVerifier hiddenMavenVerifier) { this.hiddenMavenVerifier=hiddenMavenVerifier; }
    public V12EvaluationResult evaluate(V12Task task, Path workspace, List<V12TurnResult> turns,
                                        V12EvaluationCheck check) throws IOException {
        List<String> failed = new ArrayList<>();
        List<String> tools = turns.stream().flatMap(t -> t.trajectory().steps().stream())
                .filter(s -> s.actionType() == AgentActionType.TOOL_CALL).map(AgentStep::toolName).toList();
        List<?> routes = turns.stream().map(V12TurnResult::selectedRoute).toList();
        if (!check.routes().isEmpty() && !routes.equals(check.routes())) failed.add("routes=" + routes);
        for (String tool : check.requiredTools()) if (!tools.contains(tool)) failed.add("missing tool " + tool);
        for (String tool : check.forbiddenTools()) if (tools.contains(tool)) failed.add("forbidden tool " + tool);
        if (!isSubsequence(check.orderedToolSubsequence(), tools)) failed.add("tool order " + tools);
        if (check.requireCompletion() && turns.stream().anyMatch(t -> !t.trajectory().completed())) failed.add("incomplete turn");
        for (V12FileAssertion assertion : check.files()) evaluateFile(workspace, assertion, failed);

        List<AgentStep> maven = turns.stream().flatMap(t -> t.trajectory().steps().stream())
                .filter(s -> "run_maven_test".equals(s.toolName())).toList();
        boolean mavenFailure = maven.stream().anyMatch(s -> s.toolResult() != null && !s.toolResult().success());
        boolean mavenFinalPass = !maven.isEmpty() && maven.get(maven.size() - 1).toolResult() != null
                && maven.get(maven.size() - 1).toolResult().success();
        boolean recovery = recoveryAfterFailure(turns, maven);
        Map<String,Object> details = new LinkedHashMap<>();
        if (check.requireMavenFailure() && !mavenFailure) failed.add("required Maven failure absent");
        if (check.requireMavenFinalPass() && !mavenFinalPass) failed.add("final Maven pass absent");
        if (check.requireRecoveryAfterFailure() && !recovery) failed.add("meaningful recovery after Maven failure absent");
        if (task.evaluator()==V12Evaluator.HIDDEN_MAVEN || task.evaluator()==V12Evaluator.HIDDEN_MAVEN_WITH_RECOVERY) {
            if(hiddenMavenVerifier==null) failed.add("hidden Maven verifier unavailable");
            else {
                V12HiddenMavenVerifier.Result hidden=hiddenMavenVerifier.verify(task.id(),workspace);
                if(!hidden.available()) failed.add(hidden.diagnosticSummary());
                else if(!hidden.passed()) failed.add("hidden Maven failed: "+hidden.diagnosticSummary());
                details.put("hiddenMavenAvailable",hidden.available()); details.put("hiddenMavenPassed",hidden.passed());
                details.put("hiddenMavenDiagnostic",hidden.diagnosticSummary());
            }
        }
        details.put("routes", routes); details.put("tools", tools); details.put("mavenFailure", mavenFailure);
        details.put("mavenFinalPass", mavenFinalPass); details.put("recoveryAfterFailure", recovery);
        return new V12EvaluationResult(failed.isEmpty(), failed, details);
    }

    private static void evaluateFile(Path workspace, V12FileAssertion assertion, List<String> failed) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path file = root.resolve(assertion.path()).normalize();
        if (!file.startsWith(root)) throw new IOException("Evaluator assertion escaped workspace");
        boolean exists = Files.isRegularFile(file);
        if (exists != assertion.exists()) { failed.add("file existence " + assertion.path()); return; }
        if (!exists) return;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (String value : assertion.contains()) if (!content.contains(value)) failed.add(assertion.path() + " missing: " + value);
        for (String value : assertion.notContains()) if (content.contains(value)) failed.add(assertion.path() + " forbidden: " + value);
        if (!assertion.anyContains().isEmpty() && assertion.anyContains().stream().noneMatch(content::contains))
            failed.add(assertion.path() + " missing any of: " + assertion.anyContains());
    }

    private static boolean isSubsequence(List<String> required, List<String> actual) {
        int index = 0;
        for (String value : actual) if (index < required.size() && required.get(index).equals(value)) index++;
        return index == required.size();
    }

    private static boolean recoveryAfterFailure(List<V12TurnResult> turns, List<AgentStep> maven) {
        List<AgentStep> all = turns.stream().flatMap(t -> t.trajectory().steps().stream()).toList();
        int failedIndex = -1;
        for (int i=0;i<all.size();i++) if ("run_maven_test".equals(all.get(i).toolName())
                && all.get(i).toolResult()!=null && !all.get(i).toolResult().success()) { failedIndex=i; break; }
        if (failedIndex < 0) return false;
        for (int i=failedIndex+1;i<all.size();i++) {
            String tool=all.get(i).toolName();
            if (tool!=null && List.of("read_file","search_code","apply_patch","insert_before","insert_after","run_maven_test").contains(tool)) return true;
        }
        return false;
    }
}
