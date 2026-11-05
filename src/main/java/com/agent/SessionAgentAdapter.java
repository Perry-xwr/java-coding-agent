package com.agent;

import com.agent.agent.AgentRunResult;
import com.agent.tui.runtime.AgentAdapter;

/** M1 snapshot handoff; only the UI owner commits returned histories. */
public interface SessionAgentAdapter extends AgentAdapter {
    void prepare(String sessionId, CliSessionSnapshot snapshot);
    Result takeResult(String turnId);
    default com.agent.memory.LlmMemoryExtractor semanticMemoryExtractor(){return null;}
    /** Daily can opt into Agent-managed writes and suppress the legacy post-turn extractor. */
    default boolean agentManagedMemory(){return false;}
    default com.agent.execution.secure.TuiMavenApprovalChannel nativeApprovals(){return null;}
    default java.util.List<Stopped> drainStopped(){return java.util.List.of();}
    record Stopped(String sessionId,String turnId,CliSessionSnapshot snapshot,com.agent.agent.TaskChangeReview changes) { }
    record Result(CliSessionSnapshot snapshot, AgentRunResult run) { }
}
