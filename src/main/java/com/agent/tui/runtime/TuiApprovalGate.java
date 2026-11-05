package com.agent.tui.runtime;

import com.agent.execution.secure.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** UI-owned denial-only bridge design. Never issues APPROVED or invokes an execution backend. */
public final class TuiApprovalGate {
    public record Request(String requestId,String sessionId,String turnId,ExecutionApprovalPrompt prompt,long expiresAt) { }
    public record Audit(String requestId,String sessionId,String turnId,String digest,ApprovalDecision.Status decision,String reason) { }
    private final Thread owner=Thread.currentThread();
    private final LongSupplier time;
    private final List<Audit> audit=new ArrayList<>();
    private Request pending;
    public TuiApprovalGate(){this(System::nanoTime);}
    public TuiApprovalGate(LongSupplier time){this.time=Objects.requireNonNull(time);}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Approval requires UI owner; model/worker cannot decide");}
    public Request pending(){owned();return pending;}
    public List<Audit> audit(){owned();return List.copyOf(audit);}
    public Request request(String session,String turn,ExecutionApprovalPrompt prompt,Duration timeout){
        owned();UUID.fromString(session);UUID.fromString(turn);Objects.requireNonNull(prompt);
        if(!prompt.requestDigest().matches("[a-fA-F0-9]{64}")||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(2))>0)
            throw new IllegalArgumentException("Bounded immutable request required");
        invalidate("SUPERSEDED");
        pending=new Request(UUID.randomUUID().toString(),session,turn,prompt,time.getAsLong()+timeout.toNanos());return pending;
    }
    public ApprovalDecision resolve(String request,String session,String turn,boolean allow){
        owned();
        if(pending==null)return new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE,"","");
        String digest=pending.prompt().requestDigest();
        if(expire())return new ApprovalDecision(ApprovalDecision.Status.DENIED,digest,"");
        boolean matches=pending.requestId().equals(request)&&pending.sessionId().equals(session)&&pending.turnId().equals(turn);
        return finish(matches&&allow?ApprovalDecision.Status.UNAVAILABLE:ApprovalDecision.Status.DENIED,
                !matches?"BINDING_MISMATCH":allow?"PRODUCTION_BRIDGE_NOT_CONNECTED":"USER_DENIED");
    }
    public boolean expire(){owned();if(pending!=null&&time.getAsLong()>=pending.expiresAt()){finish(ApprovalDecision.Status.DENIED,"TIMED_OUT");return true;}return false;}
    public void invalidate(String reason){owned();if(pending!=null)finish(ApprovalDecision.Status.DENIED,reason);}
    private ApprovalDecision finish(ApprovalDecision.Status status,String reason){
        var request=pending;pending=null;var result=new ApprovalDecision(status,request.prompt().requestDigest(),"");
        if(audit.size()==128)audit.remove(0);
        audit.add(new Audit(request.requestId(),request.sessionId(),request.turnId(),request.prompt().requestDigest(),status,reason));return result;
    }
}
