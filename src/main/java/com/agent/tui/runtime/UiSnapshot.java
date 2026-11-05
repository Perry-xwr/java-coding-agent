package com.agent.tui.runtime;

import java.util.List;

/** Deeply immutable read model. Renderers never receive the reducer or mutable buffers. */
public record UiSnapshot(String currentSessionId,String selectedSessionId,boolean sessionFocus,int width,int height,
                         boolean closed,List<Session> sessions,String notice,ApprovalView localApproval,boolean chatFocus) {
    public UiSnapshot { sessions=List.copyOf(sessions); }
    public Session current(){return sessions.stream().filter(s->s.sessionId().equals(currentSessionId)).findFirst().orElseThrow();}
    public record Chat(String role,String text,String state,String copyKey) {
        public Chat(String role,String text,String state){this(role,text,state,"");}
        public Chat(String role,String text){this(role,text,"");}
    }
    public record Input(String text,int cursor) { }
    public record Tool(String toolId,String name,String status,String summary,String details) {
        public Tool(String id,String name,String status,String summary){this(id,name,status,summary,"");}
    }
    public record Approval(String requestId,String operation,UiEvent.ApprovalChannel channel,String status) { }
    public record ApprovalView(String requestId,String sessionId,String turnId,String operation,String risk,boolean nativeRequest) {
        public ApprovalView(String requestId,String sessionId,String turnId,String operation,String risk){this(requestId,sessionId,turnId,operation,risk,false);}
    }
    public record Turn(String turnId,String status,String streamingText,long sequence,List<Tool> tools,Approval approval,
                       UiEvent.UsageUpdated usage,String error) {
        public Turn {tools=List.copyOf(tools);}
        public boolean running(){return status.equals("RUNNING")||status.equals("STARTING");}
    }
    public record Session(String sessionId,String title,boolean memory,String provider,String model,List<Chat> history,
                          Input input,int scroll,Turn turn,String profile,String updatedAt,boolean autoMemory,String retrievalMode,String capabilities,
                          // Transient navigation only: revision resets follow; delta accumulates between frames.
                          long scrollRevision,long scrollDelta) {
        public Session {history=List.copyOf(history);}
    }
}
