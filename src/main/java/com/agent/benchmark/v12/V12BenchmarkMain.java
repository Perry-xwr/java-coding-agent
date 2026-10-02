package com.agent.benchmark.v12;

import com.agent.llm.GlmClient;
import com.agent.llm.LLMResponse;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Safe-by-default V1.2 benchmark entry point. */
public final class V12BenchmarkMain {
    private V12BenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        Map<String,String> options=Arrays.stream(args).filter(a->a.startsWith("--")&&a.contains("="))
                .map(a->a.substring(2).split("=",2)).collect(java.util.stream.Collectors.toMap(a->a[0],a->a[1]));
        String provider=options.getOrDefault("provider","fake").toLowerCase();
        boolean allowReal=Arrays.asList(args).contains("--allow-real");
        boolean confirmTest=Arrays.asList(args).contains("--confirm-test");
        V12BenchmarkSuite suite=new V12BenchmarkTaskLoader().load(Path.of("benchmark/v1.2/manifest.json"));
        List<V12Task> selected=select(suite,options);
        if(selected.stream().anyMatch(t->t.split()==V12Split.TEST) && !confirmTest)
            throw new IllegalArgumentException("TEST requires --confirm-test");
        if("real".equals(provider) && !allowReal)
            throw new IllegalArgumentException("Real provider requires --allow-real");
        if(!List.of("fake","real").contains(provider)) throw new IllegalArgumentException("provider must be fake or real");
        int theoretical=selected.stream().mapToInt(t->t.maxSteps()*t.userInstructions().size()).sum();
        int runCap=Integer.parseInt(options.getOrDefault("max-provider-requests",String.valueOf(theoretical)));
        ProviderBudget budget=new ProviderBudget(runCap);
        V12ProviderFactory factory="real".equals(provider)
                ? task->GlmClient.forBenchmark()
                : task->messages->new LLMResponse("Fake provider: no scripted action for "+task.id(),List.of());
        String runId=options.getOrDefault("run-id",DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now())+"-"+provider);
        Map<String,V12EvaluationCheck> checks=new V12EvaluationCheckLoader().load(Path.of("benchmark/v1.2/evaluator/checks.json"));
        List<V12TaskResult> results=new V12BenchmarkRunner().run(runId,selected,Path.of("benchmark-runs/v1.2"),
                checks,provider,options.getOrDefault("runtime-commit","b13bc06"),budget,factory);
        System.out.println("V1.2 tasks executed="+results.size()+", passed="+results.stream().filter(V12TaskResult::success).count()
                +", providerRequests="+budget.used()+"/"+budget.cap());
    }

    private static List<V12Task> select(V12BenchmarkSuite suite,Map<String,String> options) {
        if(options.containsKey("task")) return suite.tasks().stream().filter(t->t.id().equals(options.get("task"))).toList();
        String split=options.getOrDefault("split","dev");
        V12Split selected=V12Split.valueOf(split.toUpperCase());
        return suite.tasks().stream().filter(t->t.split()==selected).toList();
    }
}
