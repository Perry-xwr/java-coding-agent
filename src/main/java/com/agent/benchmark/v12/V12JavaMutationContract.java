package com.agent.benchmark.v12;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;

import java.util.List;

/** Ordering-independent evidence for completing a Java source mutation. */
final class V12JavaMutationContract {
    private static final List<String> MUTATIONS = List.of(
            "create_file", "apply_patch", "insert_before", "insert_after", "replace_lines"
    );

    record Assessment(
            boolean applicable,
            boolean sourceReadBeforeMutation,
            boolean rereadAfterMutation,
            boolean mavenPassAfterMutation,
            boolean finalAfterRequirements
    ) {
        boolean satisfied() {
            return !applicable || rereadAfterMutation && mavenPassAfterMutation && finalAfterRequirements;
        }
    }

    static Assessment assess(List<AgentStep> steps, boolean completed) {
        int mutationIndex = -1;
        String mutationTool = null;
        String path = null;
        for (int index = 0; index < steps.size(); index++) {
            AgentStep step = steps.get(index);
            Object candidatePath = step.arguments().get("path");
            if (successfulTool(step) && MUTATIONS.contains(step.toolName())
                    && candidatePath instanceof String value && isJava(value)) {
                mutationIndex = index;
                mutationTool = step.toolName();
                path = value;
            }
        }
        if (mutationIndex < 0) return new Assessment(false,true,true,true,true);

        boolean sourceRead = "create_file".equals(mutationTool);
        int rereadIndex = -1;
        int mavenIndex = -1;
        int finalIndex = -1;
        for (int index = 0; index < steps.size(); index++) {
            AgentStep step = steps.get(index);
            if (index < mutationIndex && successfulObservation(step) && "read_file".equals(step.toolName())
                    && path.equals(step.arguments().get("path"))) sourceRead = true;
            if (index > mutationIndex && successfulObservation(step) && "read_file".equals(step.toolName())
                    && path.equals(step.arguments().get("path"))) rereadIndex = index;
            if (index > mutationIndex && successfulTool(step) && "run_maven_test".equals(step.toolName()))
                mavenIndex = index;
            if (index > mutationIndex && step.actionType() == AgentActionType.FINAL_ANSWER) finalIndex = index;
        }
        if (finalIndex < 0 && completed) finalIndex = steps.size();
        boolean finalAfter = rereadIndex >= 0 && mavenIndex >= 0
                && finalIndex > rereadIndex && finalIndex > mavenIndex;
        return new Assessment(true,sourceRead,rereadIndex >= 0,mavenIndex >= 0,finalAfter);
    }

    private static boolean successfulTool(AgentStep step) {
        return step.actionType() == AgentActionType.TOOL_CALL
                && step.toolResult() != null && step.toolResult().success();
    }

    private static boolean successfulObservation(AgentStep step) {
        return (step.actionType() == AgentActionType.TOOL_CALL
                || step.actionType() == AgentActionType.AUTO_REREAD)
                && step.toolResult() != null && step.toolResult().success();
    }

    private static boolean isJava(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }
}
