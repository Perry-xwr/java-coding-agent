package com.agent.benchmark.v12;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.TerminationReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class V12Metrics {
    private static final List<String> MUTATIONS = List.of("create_file","apply_patch","insert_before","insert_after","replace_lines");

    static Map<String,Object> from(List<V12TurnResult> turns, int maxSteps, long durationMs) {
        List<AgentStep> steps = turns.stream().flatMap(t -> t.trajectory().steps().stream()).toList();
        List<AgentStep> calls = steps.stream().filter(s -> s.actionType()==AgentActionType.TOOL_CALL).toList();
        Map<String,Integer> usage = new LinkedHashMap<>();
        LinkedHashSet<String> files = new LinkedHashSet<>();
        int errors=0, invalid=0, mutations=0, rereads=0, autoRereads=0, stale=0, convergence=0, mavenFailures=0;
        for (AgentStep step:calls) {
            usage.merge(step.toolName(),1,Integer::sum);
            if (step.toolResult()!=null && !step.toolResult().success()) {
                errors++;
                if (step.toolResult().errorCode()!=null && step.toolResult().errorCode().name().contains("INVALID")) invalid++;
            }
            if (MUTATIONS.contains(step.toolName()) && step.toolResult()!=null && step.toolResult().success()) mutations++;
            if ("read_file".equals(step.toolName())) rereads++;
            Object path=step.arguments().get("path"); if(path instanceof String p) files.add(p);
            if (step.toolResult()!=null && step.toolResult().errorCode()!=null
                    && "STALE_EDIT_CONTEXT".equals(step.toolResult().errorCode().name())) stale++;
            if ("run_maven_test".equals(step.toolName()) && step.toolResult()!=null && !step.toolResult().success()) mavenFailures++;
        }
        for (AgentStep step:steps) if (step.actionType()==AgentActionType.AUTO_REREAD) {
            rereads++;
            autoRereads++;
            Object path=step.arguments().get("path"); if(path instanceof String p) files.add(p);
            if (step.toolResult()!=null && !step.toolResult().success()) errors++;
        }
        for (AgentStep step:steps) if (step.actionType()==AgentActionType.RUNTIME_FEEDBACK && step.errorMessage()!=null
                && step.errorMessage().contains("POST_EDIT_CONVERGENCE")) convergence++;
        boolean mavenFinalPass = calls.stream().filter(s->"run_maven_test".equals(s.toolName())).reduce((a,b)->b)
                .map(s->s.toolResult()!=null && s.toolResult().success()).orElse(false);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("turns",turns.size()); out.put("steps",steps.size()); out.put("maxSteps",maxSteps);
        out.put("maxStepTermination",turns.stream().anyMatch(t->t.trajectory().terminationReason()==TerminationReason.MAX_STEPS));
        out.put("finalResponseProduced",turns.stream().allMatch(t->t.trajectory().finalAnswer()!=null));
        out.put("toolCallCount",calls.size()); out.put("toolErrorCount",errors); out.put("invalidToolCallCount",invalid);
        out.put("toolUsageDistribution",usage); out.put("mutationCount",mutations); out.put("filesTouched",List.copyOf(files));
        out.put("rereadCount",rereads); out.put("autoRereadCount",autoRereads);
        out.put("staleContextGuardCount",stale); out.put("convergenceGuardCount",convergence);
        out.put("mavenInvocationCount",usage.getOrDefault("run_maven_test",0)); out.put("mavenFailureCount",mavenFailures);
        out.put("mavenFinalPass",mavenFinalPass); out.put("recoveryAttempted",mavenFailures>0 && calls.size()>1);
        out.put("recoverySuccess",mavenFailures>0 && mavenFinalPass);
        out.put("durationMs",durationMs); out.put("providerUsage","UNAVAILABLE");
        return Map.copyOf(out);
    }
}
