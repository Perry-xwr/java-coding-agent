package com.agent.execution.secure;

import com.agent.tool.execution.*;
import org.jline.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Native UI capability, not an Agent event. One background turn; exact R1 prompt, nonce and digest. */
public final class TuiMavenApprovalChannel implements AutoCloseable {
    public record View(String requestId,String sessionId,String turnId,String toolName,ExecutionApprovalPrompt prompt,long deadline) { }
    public record Audit(String requestId,String sessionId,String turnId,String digest,String approvalId,String status,String reason) { }
    private final Thread uiOwner=Thread.currentThread();
    private final Duration timeout;private final LongSupplier clock;
    private final ArrayBlockingQueue<View> delivery=new ArrayBlockingQueue<>(1);
    private final List<Audit> audit=new ArrayList<>();
    private Pending current;private boolean closed,auditTruncated;
    private static final class Pending {
        final View view;final CompletableFuture<ApprovalDecision> answer=new CompletableFuture<>();
        final Thread requester=Thread.currentThread();
        boolean revoked,consumed,presented;ApprovalDecision decision;
        Pending(View view){this.view=view;}
    }
    public static TuiMavenApprovalChannel openNative(Terminal terminal){
        if(System.console()==null||terminal==null||terminal.getType().contains("dumb"))throw new IllegalStateException("Native console required; no stdin approval fallback");
        return new TuiMavenApprovalChannel(Duration.ofSeconds(60),System::nanoTime);
    }
    // Same-package tests only. No environment/configuration switch can manufacture native availability.
    TuiMavenApprovalChannel(Duration timeout,LongSupplier clock){
        if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("Approval timeout");
        this.timeout=timeout;this.clock=clock;
    }
    private void ui(){if(Thread.currentThread()!=uiOwner)throw new IllegalStateException("Only native UI owner can submit confirmation");}
    public Turn bind(String session,String turn,Path workspace){UUID.fromString(session);UUID.fromString(turn);return new Turn(session,turn,workspace.toAbsolutePath().normalize());}
    private void record(Pending p,String status,String reason){
        if(audit.size()==256){audit.remove(0);auditTruncated=true;}var v=p.view;
        audit.add(new Audit(v.requestId(),v.sessionId(),v.turnId(),v.prompt().requestDigest(),p.decision==null?"":p.decision.approvalId(),status,reason));
    }
    public synchronized List<Audit> audit(){return List.copyOf(audit);}
    public synchronized boolean auditTruncated(){return auditTruncated;}
    public synchronized View poll(){ui();expire();var view=delivery.poll();if(view!=null&&current!=null&&current.view==view)current.presented=true;return view;}
    public synchronized View pending(){ui();expire();return current!=null&&!current.revoked&&!current.consumed&&current.decision==null?current.view:null;}
    private void deny(Pending p,String reason){
        if(p.revoked||p.consumed)return;p.revoked=true;delivery.remove(p.view);record(p,"DENIED",reason);
        p.answer.complete(new ApprovalDecision(ApprovalDecision.Status.DENIED,p.view.prompt().requestDigest(),""));
    }
    private void expire(){if(current!=null&&clock.getAsLong()>=current.view.deadline())deny(current,"TIMED_OUT");}
    public synchronized boolean confirm(String request,String session,String turn,String exactResponse){
        ui();expire();var p=current;
        if(p==null||p.revoked||p.consumed||p.decision!=null)return false;
        if(!p.presented){deny(p,"NOT_PRESENTED_TO_UI");return false;}
        var v=p.view;
        if(!v.requestId().equals(request)||!v.sessionId().equals(session)||!v.turnId().equals(turn)){deny(p,"BINDING_MISMATCH");return false;}
        p.decision=ExactExecutionConfirmation.evaluate(v.prompt(),exactResponse);
        record(p,p.decision.status().name(),p.decision.status()==ApprovalDecision.Status.APPROVED?"EXACT_NONCE_CONFIRMED":"USER_DENIED_OR_INVALID_MATERIAL");
        if(p.decision.status()!=ApprovalDecision.Status.APPROVED)p.revoked=true;
        p.answer.complete(p.decision);return p.decision.status()==ApprovalDecision.Status.APPROVED;
    }
    public synchronized void denyPending(String reason){ui();if(current!=null)deny(current,reason);delivery.clear();}
    @Override public synchronized void close(){closed=true;if(current!=null)deny(current,"CHANNEL_CLOSED");delivery.clear();}
    public final class Turn implements AutoCloseable {
        private final String session,turn;private final Path workspace;
        private Pending grant;private boolean ended;
        private Turn(String session,String turn,Path workspace){this.session=session;this.turn=turn;this.workspace=workspace;}
        public ApprovalDecision request(ExecutionApprovalPrompt prompt){
            if(Thread.currentThread()==uiOwner)return new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE,prompt.requestDigest(),"");
            Pending p;
            synchronized(TuiMavenApprovalChannel.this){
                if(closed||ended||current!=null&&!current.consumed&&!current.revoked)return new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE,prompt.requestDigest(),"");
                String details=details(prompt);
                if(prompt.action()!=ExecutionRequest.Action.MAVEN_TEST||!prompt.challenge().matches("[A-F0-9]{8}")||!prompt.requestDigest().matches("[a-f0-9]{64}")
                        ||!prompt.workspaceCopy().startsWith("Windows host workspace="+workspace+"; command=")
                        ||details.length()>16384||details.codePoints().anyMatch(cp->Character.isISOControl(cp)&&cp!='\n'))
                    return new ApprovalDecision(ApprovalDecision.Status.UNAVAILABLE,prompt.requestDigest(),"");
                var v=new View(UUID.randomUUID().toString(),session,turn,"run_maven_test",prompt,clock.getAsLong()+timeout.toNanos());
                p=new Pending(v);current=grant=p;record(p,"REQUESTED","R1_PROMPT");
                if(!delivery.offer(v)){deny(p,"DELIVERY_QUEUE_FULL");return new ApprovalDecision(ApprovalDecision.Status.DENIED,prompt.requestDigest(),"");}
            }
            try{
                var decision=p.answer.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
                synchronized(TuiMavenApprovalChannel.this){expire();return p.revoked||ended?new ApprovalDecision(ApprovalDecision.Status.DENIED,prompt.requestDigest(),""):decision;}
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();synchronized(TuiMavenApprovalChannel.this){deny(p,"TASK_CANCELLED");}return new ApprovalDecision(ApprovalDecision.Status.DENIED,prompt.requestDigest(),"");}
            catch(ExecutionException|TimeoutException failure){synchronized(TuiMavenApprovalChannel.this){deny(p,"TIMED_OUT_OR_CHANNEL_FAILURE");}return new ApprovalDecision(ApprovalDecision.Status.DENIED,prompt.requestDigest(),"");}
        }
        private void consume(List<String> command,Path directory,Duration timeout,int limit,Map<String,String> env,ProcessEnvironmentProfile profile)throws IOException{
            synchronized(TuiMavenApprovalChannel.this){expire();var p=grant;
                if(closed||ended||!workspace.equals(directory.toAbsolutePath().normalize())||p==null||p.requester!=Thread.currentThread()||p.revoked||p.consumed||p.decision==null||p.decision.status()!=ApprovalDecision.Status.APPROVED)
                    throw new IOException("TUI R1 approval absent, expired, revoked or already consumed; no process started");
                if(!directory.toRealPath().equals(workspace))throw new IOException("TUI workspace identity changed; no process started");
                for(Path path=directory;path!=null;path=path.getParent())if(java.nio.file.Files.isSymbolicLink(path))throw new IOException("TUI workspace link changed; no process started");
                var limits=java.util.regex.Pattern.compile("timeout=(\\d+)s; max output=(\\d+) bytes").matcher(p.view.prompt().isolationSummary());
                if(!p.view.prompt().workspaceCopy().equals("Windows host workspace="+workspace+"; command="+command)
                        ||!limits.find()||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(Long.parseLong(limits.group(1))))>0
                        ||limit!=Integer.parseInt(limits.group(2))||!env.isEmpty()||profile!=ProcessEnvironmentProfile.MAVEN){deny(p,"EXECUTION_CONFIGURATION_CHANGED");throw new IOException("TUI R1 execution configuration changed; no process started");}
                p.consumed=true;record(p,"START_PERMITTED","ONE_TIME_CONSUMPTION");
            }
        }
        public ProcessRunner guard(ProcessRunner delegate){return new ProcessRunner(){
            public ProcessExecutionResult run(List<String> command,Path directory,Duration timeout,int limit)throws IOException,InterruptedException{
                return run(command,directory,timeout,limit,Map.of(),ProcessEnvironmentProfile.MAVEN);
            }
            public ProcessExecutionResult run(List<String> command,Path directory,Duration timeout,int limit,Map<String,String> env,ProcessEnvironmentProfile profile)throws IOException,InterruptedException{
                consume(command,directory,timeout,limit,env,profile);return delegate.run(command,directory,timeout,limit,env,profile);
            }
        };}
        @Override public void close(){synchronized(TuiMavenApprovalChannel.this){ended=true;if(grant!=null)deny(grant,"TURN_ENDED");}}
    }
    public static String details(ExecutionApprovalPrompt p){return "Action: "+p.action()+"\nTarget and command: "+p.workspaceCopy()+"\nTests: "+p.selectedTest()+"\nRuntime: "+p.imageReference()+"\nLimits: "+p.isolationSummary()+"\nRisk: "+p.riskNotice()+"\nDigest prefix: "+p.requestDigest().substring(0,Math.min(12,p.requestDigest().length()))+"...\nExact confirmation: APPROVE "+p.challenge();}
}
