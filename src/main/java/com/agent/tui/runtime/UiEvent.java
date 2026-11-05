package com.agent.tui.runtime;

import java.util.Objects;

/** Local immutable presentation protocol. No event is a production execution permission. */
public final class UiEvent {
    private UiEvent() { }
    public enum Outcome { COMPLETED, FAILED, DENIED, CANCELLED }
    public enum ApprovalChannel { FAKE_TEST, PRODUCTION_NATIVE }
    public enum Decision { SIMULATED_APPROVED, DENIED, TIMED_OUT, UNAVAILABLE, CANCELLED }
    public sealed interface Signal permits Started, TextDelta, Completed, ToolStarted, ToolFinished,
            ApprovalRequired, ApprovalResolved, UsageUpdated, AgentError, Cancelled, CoreCompleted { }
    /** Only accepted from the core adapter, after AgentTrajectory is finalized. */
    public record CoreCompleted(boolean completed,String reason,String diagnostic) implements Signal {
        public CoreCompleted(boolean completed,String reason){this(completed,reason,"");}
    }
    public record Started() implements Signal { }
    public record TextDelta(String text) implements Signal { public TextDelta { Objects.requireNonNull(text); } }
    public record Completed(Outcome outcome) implements Signal { public Completed { Objects.requireNonNull(outcome); } }
    public record ToolStarted(String toolId,String name) implements Signal { }
    public record ToolFinished(String toolId,boolean success,String summary,String details) implements Signal {
        public ToolFinished(String toolId,boolean success,String summary){this(toolId,success,summary,"");}
    }
    public record ApprovalRequired(String requestId,String operation,ApprovalChannel channel) implements Signal { }
    public record ApprovalResolved(String requestId,Decision decision) implements Signal { }
    public record UsageUpdated(Integer inputTokens,Integer outputTokens,boolean providerReported) implements Signal {
        public UsageUpdated {
            if((inputTokens!=null && inputTokens<0)||(outputTokens!=null && outputTokens<0)
                    ||(!providerReported && (inputTokens!=null||outputTokens!=null)))
                throw new IllegalArgumentException("Unknown usage is not a token estimate");
        }
    }
    public record AgentError(String summary) implements Signal { }
    public record Cancelled() implements Signal { }
    public record Task(String sessionId,String turnId,long sequence,Signal signal) {
        public Task {
            if(sessionId==null||sessionId.isBlank()||turnId==null||turnId.isBlank()||sequence<1)
                throw new IllegalArgumentException("Task events require session, turn and positive sequence");
            Objects.requireNonNull(signal);
        }
    }
}
