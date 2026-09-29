package com.agent.benchmark.v12;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.TerminationReason;
import com.agent.tool.ToolErrorCode;
import java.util.List;

final class V12FailureDiagnosis {
    record Diagnosis(V12FailureCategory first, V12FailureCategory last, V12FailureOwner owner, String evidence) {}

    static Diagnosis classify(List<V12TurnResult> turns, V12EvaluationResult evaluation) {
        if (evaluation.passed()) return new Diagnosis(V12FailureCategory.NONE,V12FailureCategory.NONE,V12FailureOwner.NONE,"");
        List<AgentStep> steps=turns.stream().flatMap(t->t.trajectory().steps().stream()).toList();
        for (AgentStep step:steps) if (step.actionType()==AgentActionType.TOOL_CALL && step.toolResult()!=null && !step.toolResult().success()) {
            ToolErrorCode code=step.toolResult().errorCode();
            if (code==ToolErrorCode.INVALID_ARGUMENTS) return d(V12FailureCategory.INVALID_TOOL_ARGUMENTS,V12FailureOwner.MODEL_CAPABILITY,step);
            if (code==ToolErrorCode.TEXT_NOT_FOUND || code==ToolErrorCode.MULTIPLE_MATCHES || code==ToolErrorCode.AMBIGUOUS_MATCH)
                return d(V12FailureCategory.EDIT_ANCHOR_FAILURE,V12FailureOwner.MODEL_CAPABILITY,step);
            if (code==ToolErrorCode.STALE_EDIT_CONTEXT) return d(V12FailureCategory.STALE_CONTEXT_BLOCKED,V12FailureOwner.AGENT_POLICY,step);
            if (code==ToolErrorCode.PROCESS_START_FAILED || code==ToolErrorCode.PROCESS_TIMEOUT)
                return d(V12FailureCategory.ENVIRONMENT_FAILURE,V12FailureOwner.ENVIRONMENT,step);
        }
        if (turns.stream().anyMatch(t->t.trajectory().terminationReason()==TerminationReason.MAX_STEPS))
            return new Diagnosis(V12FailureCategory.UNKNOWN,V12FailureCategory.MAX_STEP_TERMINATION,V12FailureOwner.UNKNOWN,"Agent reached max steps after an unclassified earlier deviation");
        if (turns.stream().anyMatch(t->t.trajectory().terminationReason()==TerminationReason.LLM_ERROR))
            return new Diagnosis(V12FailureCategory.MODEL_PROVIDER_FAILURE,V12FailureCategory.MODEL_PROVIDER_FAILURE,V12FailureOwner.MODEL_CAPABILITY,"Provider call failed");
        if (evaluation.failedCriteria().stream().anyMatch(s->s.startsWith("routes=")))
            return new Diagnosis(V12FailureCategory.ROUTING_FAILURE,V12FailureCategory.ROUTING_FAILURE,V12FailureOwner.AGENT_POLICY,evaluation.failedCriteria().get(0));
        if (evaluation.failedCriteria().stream().anyMatch(s->s.contains("Maven")))
            return new Diagnosis(V12FailureCategory.MAVEN_RECOVERY_FAILURE,V12FailureCategory.MAVEN_RECOVERY_FAILURE,V12FailureOwner.MODEL_CAPABILITY,String.join("; ",evaluation.failedCriteria()));
        return new Diagnosis(V12FailureCategory.UNKNOWN,V12FailureCategory.UNKNOWN,V12FailureOwner.UNKNOWN,String.join("; ",evaluation.failedCriteria()));
    }

    private static Diagnosis d(V12FailureCategory c,V12FailureOwner o,AgentStep s){return new Diagnosis(c,c,o,s.toolName()+": "+s.toolResult().errorCode());}
}
