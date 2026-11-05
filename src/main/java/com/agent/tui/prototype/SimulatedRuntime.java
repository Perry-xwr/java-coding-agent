package com.agent.tui.prototype;

import java.util.Map;
import java.util.concurrent.*;
import static com.agent.tui.prototype.PrototypeState.Kind.*;

/** Background workers can only emit bounded, tagged fake events. No terminal or Agent reference. */
public final class SimulatedRuntime implements AutoCloseable {
    private final BlockingQueue<PrototypeState.Event> events;
    private final ExecutorService workers=Executors.newFixedThreadPool(2, r -> new Thread(r,"tui0-fake-worker"));
    private final Map<String,CompletableFuture<Boolean>> approvals=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final long delayMs;
    public SimulatedRuntime(BlockingQueue<PrototypeState.Event> events) { this(events,70); }
    SimulatedRuntime(BlockingQueue<PrototypeState.Event> events,long delayMs) { this.events=events; this.delayMs=delayMs; }
    private String key(String session,String turn) {return session+":"+turn;}
    public void start(String session,String turn,boolean memory,boolean fail) {
        if(closed) throw new IllegalStateException("Simulator closed");
        var decision=new CompletableFuture<Boolean>(); approvals.put(key(session,turn),decision);
        workers.submit(()->{
            try {
                emit(session,turn,STARTED,""); emit(session,turn,APPROVAL_REQUIRED,"fake_read: no files or commands");
                boolean approved=decision.get(30,TimeUnit.SECONDS);
                if(!approved) {
                    emit(session,turn,TOOL_FAILED,"DENIED; zero real execution");
                    emit(session,turn,DELTA,"[模拟] 用户拒绝，本次没有运行工具。");
                    emit(session,turn,COMPLETED,"DENIED (FAKE)"); return;
                }
                emit(session,turn,TOOL_STARTED,"fake_read (in-memory only)");
                String[] chunks={"[模拟逐段回复] ","已收到当前会话的任务。\n", "中文文本按事件增量显示，", "后台线程不向终端输出。\n",
                        "模拟 Memory 状态为 "+(memory?"ON":"OFF")+"。", "这里没有模型、文件读取或真实执行。"};
                for(String chunk:chunks) {Thread.sleep(delayMs); emit(session,turn,DELTA,chunk);}
                emit(session,turn,fail?TOOL_FAILED:TOOL_FINISHED,fail?"SIMULATED_FAILURE; no real command":"fake_read finished");
                emit(session,turn,COMPLETED,fail?"FAILED (FAKE)":"COMPLETED (FAKE)");
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            catch(TimeoutException timeout) { safeError(session,turn,"Approval timed out (FAKE)"); }
            catch(ExecutionException failure) {safeError(session,turn,"Simulator failure");}
            finally {approvals.remove(key(session,turn));}
        });
    }
    private void emit(String session,String turn,PrototypeState.Kind kind,String text)throws InterruptedException {
        if(!closed) events.put(new PrototypeState.Event(session,turn,kind,text));
    }
    private void safeError(String session,String turn,String text) {
        try {emit(session,turn,ERROR,text);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    public boolean resolve(String session,String turn,boolean approved) {
        var decision=approvals.get(key(session,turn)); return decision!=null && decision.complete(approved);
    }
    boolean terminated(){return workers.isTerminated();}
    @Override public void close() {
        closed=true; approvals.values().forEach(f->f.cancel(true)); workers.shutdownNow();
        try {if(!workers.awaitTermination(2,TimeUnit.SECONDS)) throw new IllegalStateException("Fake workers did not stop");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
