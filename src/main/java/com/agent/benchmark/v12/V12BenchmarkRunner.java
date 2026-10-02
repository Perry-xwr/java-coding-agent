package com.agent.benchmark.v12;

import com.agent.agent.TerminationReason;
import com.agent.llm.LLMClient;
import com.agent.trajectory.TrajectoryJsonWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class V12BenchmarkRunner {
    private final V12FixtureWorkspace workspaces = new V12FixtureWorkspace();
    private final V12DeterministicEvaluator evaluator;

    public V12BenchmarkRunner() {
        this.evaluator=new V12DeterministicEvaluator(new V12HiddenMavenVerifier(
                Path.of("benchmark/v1.2/evaluator/hidden"),Path.of(".m2/repository")));
    }

    public List<V12TaskResult> run(String runId, List<V12Task> tasks, Path runsRoot,
                                   Map<String,V12EvaluationCheck> checks, String provider,
                                   String runtimeCommit, ProviderBudget budget,
                                   V12ProviderFactory providers) throws Exception {
        List<V12TaskResult> results=new ArrayList<>();
        Path resultRoot=runsRoot.resolve(runId);
        for(V12Task task:tasks) {
            if (budget.used() >= budget.cap()) {
                results.add(failed(task,runtimeCommit,provider,"BUDGET_CAP_REACHED",V12FailureCategory.BUDGET_CAP_REACHED,V12FailureOwner.RUNTIME));
                continue;
            }
            long start=System.nanoTime();
            try {
                Path workspace=workspaces.reset(task,runsRoot.resolve("workspaces"),runId);
                int taskCap=task.maxSteps()*task.userInstructions().size();
                LLMClient client=new BudgetedLlmClient(providers.create(task),budget,taskCap);
                V12RuntimeHarness runtime=new V12RuntimeHarness(client,workspace);
                List<V12TurnResult> turns=runtime.run(task);
                for(V12TurnResult turn:turns) new TrajectoryJsonWriter(resultRoot.resolve("trajectories").resolve(task.id())).write(turn.trajectory());
                V12EvaluationCheck check=checks.get(task.id());
                if(check==null) throw new IllegalStateException("Missing evaluator check for "+task.id());
                V12EvaluationResult evaluation=evaluator.evaluate(task,workspace,turns,check);
                long duration=(System.nanoTime()-start)/1_000_000L;
                Map<String,Object> metrics=V12Metrics.from(turns,task.maxSteps(),duration);
                V12FailureDiagnosis.Diagnosis diagnosis=V12FailureDiagnosis.classify(turns,evaluation);
                boolean completed=turns.stream().allMatch(t->t.trajectory().completed());
                String completion=turns.stream().map(t->t.trajectory().terminationReason().name()).distinct().toList().toString();
                results.add(new V12TaskResult(task.id(),task.split(),task.taskType(),task.evaluator(),"v1.2",
                        runtimeCommit,provider,Instant.now().toString(),completed&&evaluation.passed(),completion,
                        evaluation.passed(),evaluation.passed()?"PASS":"FAIL",turns,metrics,diagnosis.first(),
                        diagnosis.last(),diagnosis.owner(),diagnosis.evidence()));
            } catch(Exception exception) {
                V12FailureCategory category=exception.getMessage()!=null&&exception.getMessage().contains("BUDGET_CAP_REACHED")
                        ? V12FailureCategory.BUDGET_CAP_REACHED:V12FailureCategory.RUNTIME_FAILURE;
                results.add(failed(task,runtimeCommit,provider,exception.getClass().getSimpleName()+": "+safe(exception.getMessage()),category,V12FailureOwner.RUNTIME));
            }
        }
        long success=results.stream().filter(V12TaskResult::success).count();
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("benchmarkVersion","v1.2"); summary.put("runId",runId); summary.put("provider",provider);
        summary.put("runtimeCommit",runtimeCommit); summary.put("tasksRequested",tasks.size());
        summary.put("tasksExecuted",results.size()); summary.put("successful",success);
        summary.put("providerRequests",budget.used()); summary.put("providerRequestCap",budget.cap());
        summary.put("stoppedByBudgetCap",results.stream().anyMatch(r->r.finalFailure()==V12FailureCategory.BUDGET_CAP_REACHED));
        summary.put("byMode",breakdown(results));
        summary.put("byTaskType",results.stream().collect(java.util.stream.Collectors.groupingBy(V12TaskResult::taskType,
                java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),list->Map.of(
                        "total",list.size(),"passed",list.stream().filter(V12TaskResult::success).count())))));
        new V12ResultWriter().write(resultRoot,results,summary);
        return List.copyOf(results);
    }

    private static V12TaskResult failed(V12Task task,String commit,String provider,String evidence,
                                        V12FailureCategory category,V12FailureOwner owner) {
        return new V12TaskResult(task.id(),task.split(),task.taskType(),task.evaluator(),"v1.2",commit,provider,
                Instant.now().toString(),false,"NOT_COMPLETED",false,"FAIL",List.of(),Map.of(),category,category,owner,evidence);
    }

    private static String safe(String value) {
        if(value==null)return "unknown";
        return value.replaceAll("(?i)(Bearer\\s+)[^\\s]+","$1[REDACTED]")
                .replaceAll("(?i)(GLM_API_KEY\\s*[:=]\\s*)[^\\s]+","$1[REDACTED]");
    }

    private static Map<String,Map<String,Long>> breakdown(List<V12TaskResult> results) {
        Map<String,Map<String,Long>> out=new LinkedHashMap<>();
        for(String mode:List.of("CHAT","READ","CODE")) {
            long total=results.stream().filter(r->r.turns().stream().anyMatch(t->t.selectedRoute().name().equals(mode))).count();
            long passed=results.stream().filter(V12TaskResult::success).filter(r->r.turns().stream().anyMatch(t->t.selectedRoute().name().equals(mode))).count();
            out.put(mode,Map.of("total",total,"passed",passed));
        }
        return out;
    }
}
