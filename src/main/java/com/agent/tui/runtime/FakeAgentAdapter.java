package com.agent.tui.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static com.agent.tui.runtime.UiEvent.*;

/** Explicit test adapter: no Agent, network client, filesystem, command or R1 ApprovalDecision. */
public final class FakeAgentAdapter implements AgentAdapter {
    public enum Scenario { NORMAL, STREAM, TOOL_SUCCESS, TOOL_FAILURE, ERROR, APPROVAL }
    private final Function<Request,Scenario> scenarios;
    private final long delayMs,approvalTimeoutMs;
    private final Set<Thread> workerThreads=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"tui1-fake-worker");workerThreads.add(t);return t;});
    private final Set<Control> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public FakeAgentAdapter(Function<Request,Scenario> scenarios,long delayMs,long approvalTimeoutMs){
        if(delayMs<0||approvalTimeoutMs<1)throw new IllegalArgumentException("Invalid fake timing");
        this.scenarios=scenarios;this.delayMs=delayMs;this.approvalTimeoutMs=approvalTimeoutMs;
    }
    @Override public Mode mode(){return Mode.FAKE_ONLY;}
    @Override public RunHandle start(Request request,EventSink sink) {
        if(closed)throw new IllegalStateException("Fake adapter closed");
        var control=new Control(request,sink);active.add(control);
        try {workers.submit(()->run(control));}
        catch(RuntimeException failure){active.remove(control);throw failure;}
        return control;
    }
    private final class Control implements RunHandle {
        final Request request;final EventSink sink;final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Decision> decision=new CompletableFuture<>();
        final String approvalId=UUID.randomUUID().toString();
        volatile boolean awaiting;volatile Thread thread;long sequence;
        Control(Request request,EventSink sink){this.request=request;this.sink=sink;}
        void emit(Signal signal)throws InterruptedException {
            if(cancelled.get())throw new InterruptedException("Fake task cancelled");
            sink.publish(new Task(request.sessionId(),request.turnId(),++sequence,signal));
        }
        @Override public synchronized void cancel(){cancelled.set(true);decision.complete(Decision.CANCELLED);if(thread!=null)thread.interrupt();}
        @Override public boolean resolveSimulatedApproval(String id,boolean approved){
            return !cancelled.get()&&awaiting&&approvalId.equals(id)
                    &&decision.complete(approved?Decision.SIMULATED_APPROVED:Decision.DENIED);
        }
    }
    private void run(Control c) {
        synchronized(c){c.thread=Thread.currentThread();}
        try {
            Scenario scenario=Objects.requireNonNull(scenarios.apply(c.request));
            c.emit(new Started());
            if(scenario==Scenario.APPROVAL) {
                c.awaiting=true;c.emit(new ApprovalRequired(c.approvalId,"fake_read",ApprovalChannel.FAKE_TEST));
                Decision reply;
                try {reply=c.decision.get(approvalTimeoutMs,TimeUnit.MILLISECONDS);}
                catch(TimeoutException timeout){reply=Decision.TIMED_OUT;}
                c.awaiting=false;c.emit(new ApprovalResolved(c.approvalId,reply));
                if(reply!=Decision.SIMULATED_APPROVED){c.emit(new Completed(Outcome.DENIED));return;}
            }
            if(scenario==Scenario.ERROR)throw new IllegalStateException("Scripted fake Agent error");
            boolean tool=scenario==Scenario.TOOL_SUCCESS||scenario==Scenario.TOOL_FAILURE||scenario==Scenario.APPROVAL;
            if(tool)c.emit(new ToolStarted("fake-tool-1","fake_read"));
            String[] chunks=scenario==Scenario.NORMAL?new String[]{"[FAKE] 正常回复"}
                    :new String[]{"[FAKE] ","中文流式回复。","会话事件隔离，","没有真实 API。"};
            for(String chunk:chunks){Thread.sleep(delayMs);c.emit(new TextDelta(chunk));}
            if(tool)c.emit(new ToolFinished("fake-tool-1",scenario!=Scenario.TOOL_FAILURE,
                    scenario==Scenario.TOOL_FAILURE?"SIMULATED_FAILURE":"SIMULATED_OK"));
            c.emit(new UsageUpdated(null,null,false));
            c.emit(new Completed(scenario==Scenario.TOOL_FAILURE?Outcome.FAILED:Outcome.COMPLETED));
        } catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Exception error) {
            try {c.emit(new AgentError("FAKE_AGENT_ERROR"));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        } finally {
            // A completed handle must never interrupt a worker reused by a different session.
            synchronized(c){c.awaiting=false;c.thread=null;}
            active.remove(c);
        }
    }
    public boolean terminated(){return workers.isTerminated();}
    @Override public void close(){
        closed=true;active.forEach(Control::cancel);workers.shutdownNow();
        if(workerThreads.contains(Thread.currentThread()))throw new IllegalStateException("Worker cannot await its own shutdown");
        try {if(!workers.awaitTermination(2,TimeUnit.SECONDS))throw new IllegalStateException("Fake workers did not terminate");active.clear();}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }
}
