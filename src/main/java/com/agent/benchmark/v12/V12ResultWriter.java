package com.agent.benchmark.v12;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class V12ResultWriter {
    private final ObjectMapper mapper=new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path runRoot,List<V12TaskResult> results,Map<String,Object> summary) throws IOException {
        Files.createDirectories(runRoot);
        mapper.writeValue(runRoot.resolve("run-summary.json").toFile(),summary);
        StringBuilder jsonl=new StringBuilder();
        for(V12TaskResult result:results) jsonl.append(new ObjectMapper().writeValueAsString(sanitized(result))).append('\n');
        Files.writeString(runRoot.resolve("task-results.jsonl"),jsonl,StandardCharsets.UTF_8);
        StringBuilder report=new StringBuilder("# V1.2 Failure Report\n\n");
        for(V12TaskResult r:results) if(!r.success()) report.append("## ").append(r.taskId()).append("\n\n")
                .append("- Task type: ").append(r.taskType())
                .append("\n- User instruction: ").append(r.turns().stream().map(V12TurnResult::userInput).toList())
                .append("\n- Route/profile: ").append(r.turns().stream().map(V12TurnResult::selectedRoute).toList())
                .append("\n- Step count: ").append(r.metrics().getOrDefault("steps",0))
                .append("\n- Tool call count: ").append(r.metrics().getOrDefault("toolCallCount",0))
                .append("\n- Tool usage: ").append(r.metrics().getOrDefault("toolUsageDistribution",Map.of()))
                .append("\n- Files touched: ").append(r.metrics().getOrDefault("filesTouched",List.of()))
                .append("\n- Maven invocations/failures: ").append(r.metrics().getOrDefault("mavenInvocationCount",0))
                .append("/").append(r.metrics().getOrDefault("mavenFailureCount",0))
                .append("\n- Final status: ").append(r.finalStatus())
                .append("\n- First observable failure: ").append(r.firstObservableFailure())
                .append("\n- Final failure: ").append(r.finalFailure()).append("\n- Failure owner: ").append(r.failureOwner())
                .append("\n- Evidence: ").append(r.shortEvidence()).append("\n\n");
        report.append("## Manual External Observation\n\n`fibonacci.py` is not a benchmark task. The tool-level create succeeded, but the generated Python had syntax/logic defects. The runtime has no Python execution verifier; tool success does not imply semantic task success.\n");
        Files.writeString(runRoot.resolve("failure-report.md"),report,StandardCharsets.UTF_8);
    }

    private static Map<String,Object> sanitized(V12TaskResult r) {
        Map<String,Object> out=new java.util.LinkedHashMap<>();
        out.put("taskId",r.taskId()); out.put("split",r.split()); out.put("taskType",r.taskType());
        out.put("evaluator",r.evaluator()); out.put("benchmarkVersion",r.benchmarkVersion());
        out.put("runtimeCommit",r.runtimeCommit()); out.put("provider",r.provider()); out.put("timestamp",r.timestamp());
        out.put("success",r.success()); out.put("completionState",r.completionState()); out.put("evaluatorPass",r.evaluatorPass());
        out.put("finalStatus",r.finalStatus()); out.put("metrics",r.metrics());
        out.put("routing",r.turns().stream().map(t->Map.of("turn",t.turn(),"requestedMode",t.requestedMode(),
                "selectedRoute",t.selectedRoute(),"confidence",t.routeConfidence(),"reason",t.routeReason(),
                "completed",t.trajectory().completed(),"steps",t.trajectory().steps().size())).toList());
        out.put("firstObservableFailure",r.firstObservableFailure()); out.put("finalFailure",r.finalFailure());
        out.put("failureOwner",r.failureOwner()); out.put("shortEvidence",r.shortEvidence());
        return out;
    }
}
