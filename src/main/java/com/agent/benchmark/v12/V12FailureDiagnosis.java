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
        AgentStep verificationGuard = javaVerificationGuardAfterMutationAndReread(steps);
        if (verificationGuard != null) {
            V12FailureCategory last = turns.stream().anyMatch(t -> t.trajectory().terminationReason() == TerminationReason.MAX_STEPS)
                    ? V12FailureCategory.MAX_STEP_TERMINATION
                    : V12FailureCategory.PREMATURE_FINAL;
            return new Diagnosis(V12FailureCategory.PREMATURE_FINAL,last,V12FailureOwner.AGENT_POLICY,
                    verificationGuard.errorMessage());
        }
        AgentStep rereadGuard=steps.stream()
                .filter(s->s.actionType()==AgentActionType.RUNTIME_FEEDBACK && s.errorMessage()!=null)
                .filter(s->s.errorMessage().startsWith("POST_MUTATION_READ_GUARD:")
                        || s.errorMessage().startsWith("POST_MUTATION_READ_FAILURE:"))
                .findFirst().orElse(null);
        if (rereadGuard!=null) {
            V12FailureCategory last=turns.stream().anyMatch(t->t.trajectory().terminationReason()==TerminationReason.MAX_STEPS)
                    ? V12FailureCategory.MAX_STEP_TERMINATION : V12FailureCategory.PREMATURE_FINAL;
            return new Diagnosis(V12FailureCategory.PREMATURE_FINAL,last,V12FailureOwner.AGENT_POLICY,
                    rereadGuard.errorMessage());
        }
        boolean completed=turns.stream().allMatch(t->t.trajectory().completed());
        if (evaluation.failedCriteria().stream().anyMatch(s->s.startsWith("tool order"))
                && V12JavaMutationContract.assess(steps,completed).satisfied()) {
            return new Diagnosis(V12FailureCategory.EVALUATOR_FAILURE,V12FailureCategory.EVALUATOR_FAILURE,
                    V12FailureOwner.EVALUATOR,String.join("; ",evaluation.failedCriteria()));
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

    private static AgentStep javaVerificationGuardAfterMutationAndReread(List<AgentStep> steps) {
        List<String> mutations=List.of("create_file","apply_patch","insert_before","insert_after","replace_lines");
        String mutatedPath=null;
        boolean reread=false;
        for (AgentStep step:steps) {
            if ((step.actionType()==AgentActionType.TOOL_CALL || step.actionType()==AgentActionType.AUTO_REREAD)
                    && step.toolResult()!=null && step.toolResult().success()) {
                Object path=step.arguments().get("path");
                if (mutations.contains(step.toolName()) && path instanceof String p) {
                    mutatedPath=p;
                    reread=false;
                } else if ("read_file".equals(step.toolName()) && path instanceof String p && p.equals(mutatedPath)) {
                    reread=true;
                }
            }
            if (reread && step.actionType()==AgentActionType.RUNTIME_FEEDBACK && step.errorMessage()!=null
                    && step.errorMessage().startsWith("JAVA_VERIFICATION_GUARD:")) return step;
        }
        return null;
    }

    private static Diagnosis d(V12FailureCategory c,V12FailureOwner o,AgentStep s){return new Diagnosis(c,c,o,s.toolName()+": "+s.toolResult().errorCode());}
}
