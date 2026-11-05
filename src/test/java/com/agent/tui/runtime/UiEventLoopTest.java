package com.agent.tui.runtime;

import com.agent.tui.prototype.TuiRuntimeMain;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static com.agent.tui.runtime.UiEvent.*;
import static org.junit.jupiter.api.Assertions.*;

class UiEventLoopTest {
    List<UiEventLoop.SessionSeed> seeds(){return List.of(new UiEventLoop.SessionSeed("A","Alpha",false,List.of()),new UiEventLoop.SessionSeed("B","Beta",true,List.of()));}
    FakeAgentAdapter fake(FakeAgentAdapter.Scenario scenario){return new FakeAgentAdapter(r->scenario,5,300);}
    String submit(UiEventLoop loop,String text){loop.edit(UiEventLoop.Edit.TEXT,text);return loop.submit();}
    void until(UiEventLoop loop,BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+3_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){loop.pump(8);Thread.sleep(2);}
        assertTrue(condition.getAsBoolean(),"event loop did not reach expected state");
    }
    void finished(UiEventLoop loop)throws Exception{until(loop,()->!loop.snapshot().current().turn().running());}
    @Test void immutableSnapshotsAndOwnerOnlyUpdates()throws Exception {
        try(var loop=new UiEventLoop(seeds(),fake(FakeAgentAdapter.Scenario.NORMAL),8)){
            assertThrows(UnsupportedOperationException.class,()->loop.snapshot().sessions().clear());
            assertThrows(UnsupportedOperationException.class,()->loop.snapshot().current().history().clear());
            var failure=new AtomicReference<Throwable>();Thread t=new Thread(()->{
                try{loop.edit(UiEventLoop.Edit.TEXT,"illegal");}catch(Throwable error){failure.set(error);}
            });t.start();t.join();assertInstanceOf(IllegalStateException.class,failure.get());
            assertEquals("",loop.snapshot().current().input().text());
        }
    }
    @Test void inputRemainsResponsiveAndStreamIsArchivedExactlyOnce()throws Exception {
        try(var loop=new UiEventLoop(seeds(),new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.STREAM,30,300),8)){
            submit(loop,"hello");until(loop,()->loop.snapshot().current().turn().sequence()>0);
            loop.edit(UiEventLoop.Edit.TEXT,"下一条中文");loop.edit(UiEventLoop.Edit.LEFT,"");
            assertTrue(loop.snapshot().current().turn().running());finished(loop);
            var current=loop.snapshot().current();assertEquals("下一条中文",current.input().text());assertEquals(4,current.input().cursor());
            String expected="[FAKE] 中文流式回复。会话事件隔离，没有真实 API。";
            assertEquals(expected,current.turn().streamingText());
            assertEquals(1,current.history().stream().filter(m->m.role().equals("Agent")).count());
            assertEquals(expected,current.history().get(1).text());
            var before=loop.snapshot();var frame=TuiRuntimeMain.frame(before);TuiRuntimeMain.frame(before);assertEquals(before,loop.snapshot());
            String rendered=frame.lines().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(1,rendered.split("\\[FAKE\\]",-1).length-1);
        }
    }
    @Test void switchKeepsOldTaskAndToolsInOriginSessionAndRestoresDraft()throws Exception {
        try(var loop=new UiEventLoop(seeds(),new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.TOOL_SUCCESS,20,300),16)){
            submit(loop,"A request");loop.edit(UiEventLoop.Edit.TEXT,"A draft");loop.select("B");
            assertTrue(loop.snapshot().current().history().isEmpty());assertTrue(loop.snapshot().current().memory());
            until(loop,()->!loop.snapshot().sessions().get(0).turn().running());
            assertNull(loop.snapshot().current().turn());assertTrue(loop.snapshot().current().history().isEmpty());
            loop.select("A");assertEquals("A draft",loop.snapshot().current().input().text());assertFalse(loop.snapshot().current().memory());
            assertEquals("OK",loop.snapshot().current().turn().tools().get(0).status());
        }
    }
    @Test void approvalBackpressureDoesNotApproveAndExactUserReplyIsRequired()throws Exception {
        try(var loop=new UiEventLoop(seeds(),new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.APPROVAL,0,2000),1)){
            String turn=submit(loop,"approval");long deadline=System.nanoTime()+1_000_000_000L;
            while(loop.backpressureCount()==0&&System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue(loop.backpressureCount()>0);
            until(loop,()->loop.snapshot().current().turn().approval()!=null);
            var approval=loop.snapshot().current().turn().approval();assertEquals("PENDING",approval.status());
            assertTrue(loop.snapshot().current().turn().tools().isEmpty());
            assertFalse(loop.resolveSimulatedApproval("A",turn,"wrong",true));
            loop.select("B");assertFalse(loop.resolveSimulatedApproval("A",turn,approval.requestId(),true));
            loop.select("A");assertTrue(loop.resolveSimulatedApproval("A",turn,approval.requestId(),true));
            assertFalse(loop.resolveSimulatedApproval("A",turn,approval.requestId(),true));finished(loop);
            assertEquals("SIMULATED_APPROVED",loop.snapshot().current().turn().approval().status());
            assertEquals("OK",loop.snapshot().current().turn().tools().get(0).status());
        }
    }
    @Test void denialAndTimeoutNeverStartFakeTool()throws Exception {
        for(boolean timeout:List.of(false,true))try(var loop=new UiEventLoop(seeds(),fake(FakeAgentAdapter.Scenario.APPROVAL),8)){
            String turn=submit(loop,"denied");until(loop,()->loop.snapshot().current().turn().approval()!=null);
            if(!timeout)assertTrue(loop.resolveSimulatedApproval("A",turn,loop.snapshot().current().turn().approval().requestId(),false));
            finished(loop);assertEquals("DENIED",loop.snapshot().current().turn().status());
            assertEquals(timeout?"TIMED_OUT":"DENIED",loop.snapshot().current().turn().approval().status());
            assertTrue(loop.snapshot().current().turn().tools().isEmpty());
        }
    }
    @Test void fakeErrorIsRecoverableAndToolFailureNotSuccess()throws Exception {
        var adapter=new FakeAgentAdapter(r->r.text().equals("bad")?FakeAgentAdapter.Scenario.ERROR:FakeAgentAdapter.Scenario.TOOL_FAILURE,0,300);
        try(var loop=new UiEventLoop(seeds(),adapter,16)){
            submit(loop,"bad");finished(loop);assertEquals("FAKE_AGENT_ERROR",loop.snapshot().current().turn().error());
            loop.edit(UiEventLoop.Edit.TEXT,"draft");loop.resize(60,12);assertEquals("draft",loop.snapshot().current().input().text());
            assertEquals(12,TuiRuntimeMain.frame(loop.snapshot()).lines().size());
            loop.submit();finished(loop);assertEquals("FAILED",loop.snapshot().current().turn().status());
            assertEquals("FAIL",loop.snapshot().current().turn().tools().get(0).status());
        }
    }
    @Test void cancelPendingApprovalAndIgnoreLateOldTurn()throws Exception {
        try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,8)){
            String old=submit(loop,"old");adapter.emit(1,new Started());adapter.emit(2,new ApprovalRequired("request","fake_read",ApprovalChannel.FAKE_TEST));loop.pump(8);
            loop.cancelCurrent();assertEquals("CANCELLED",loop.snapshot().current().turn().approval().status());
            adapter.emit(3,new TextDelta("late"));loop.pump(8);assertEquals("",loop.snapshot().current().turn().streamingText());
            submit(loop,"new");adapter.emitFor("A",old,4,new Completed(Outcome.COMPLETED));loop.pump(8);
            assertEquals("STARTING",loop.snapshot().current().turn().status());assertTrue(loop.ignoredEvents()>=2);
        }
    }
    @Test void duplicateIsNotRenderedTwiceAndSequenceGapIsVisibleFailure()throws Exception {
        try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,16)){
            submit(loop,"ordered");adapter.emit(1,new Started());adapter.emit(2,new TextDelta("中文"));adapter.emit(2,new TextDelta("中文"));loop.pump(16);
            assertEquals("中文",loop.snapshot().current().turn().streamingText());
            adapter.emit(4,new TextDelta("gap"));loop.pump(8);
            assertEquals("EVENT_SEQUENCE_GAP",loop.snapshot().current().turn().error());assertTrue(adapter.cancelled.get());
        }
    }
    @Test void nativeApprovalCannotBeResolvedByFakeReplyAndNoToolCanBypassPendingDecision()throws Exception {
        for(var channel:ApprovalChannel.values())try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,8)){
            String turn=submit(loop,"gated");adapter.emit(1,new ApprovalRequired("id","fake_read",channel));loop.pump(8);
            if(channel==ApprovalChannel.PRODUCTION_NATIVE)assertFalse(loop.resolveSimulatedApproval("A",turn,"id",true));
            adapter.emit(2,new ToolStarted("call","fake_read"));loop.pump(8);
            assertEquals("TOOL_WITHOUT_APPROVAL",loop.snapshot().current().turn().error());assertTrue(loop.snapshot().current().turn().tools().isEmpty());
        }
    }
    @Test void fullQueueShutdownUnblocksWorkerAndReleasesResources()throws Exception {
        var adapter=new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.STREAM,0,300);var loop=new UiEventLoop(seeds(),adapter,1);
        submit(loop,"full");long deadline=System.nanoTime()+1_000_000_000L;while(loop.backpressureCount()==0&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(loop.backpressureCount()>0);loop.close();assertTrue(adapter.terminated());assertTrue(loop.snapshot().closed());
        assertEquals("CANCELLED",loop.snapshot().current().turn().status());assertThrows(IllegalStateException.class,()->loop.edit(UiEventLoop.Edit.TEXT,"closed"));
    }
    @Test void backendCannotForgeUserApprovalAndChannelFailureDenies()throws Exception {
        for(boolean broken:List.of(false,true))try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,8)){
            String turn=submit(loop,"approval");adapter.emit(1,new ApprovalRequired("id","fake_read",ApprovalChannel.FAKE_TEST));loop.pump(8);
            if(broken){
                adapter.approvalThrows=true;assertFalse(loop.resolveSimulatedApproval("A",turn,"id",true));
                assertEquals("APPROVAL_CHANNEL_UNAVAILABLE",loop.snapshot().current().turn().error());
            } else {
                adapter.emit(2,new ApprovalResolved("id",Decision.SIMULATED_APPROVED));loop.pump(8);
                assertEquals("INVALID_APPROVAL_RESOLUTION",loop.snapshot().current().turn().error());
            }
            assertEquals("UNAVAILABLE",loop.snapshot().current().turn().approval().status());
            assertTrue(loop.snapshot().current().turn().tools().isEmpty());assertTrue(adapter.cancelled.get());
        }
    }
    @Test void conflictingDuplicateTextFailsRatherThanSilentlyDiscardingText()throws Exception {
        try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,8)){
            submit(loop,"conflict");adapter.emit(1,new TextDelta("first"));adapter.emit(1,new TextDelta("different"));loop.pump(8);
            assertEquals("EVENT_SEQUENCE_CONFLICT",loop.snapshot().current().turn().error());
            assertEquals("first",loop.snapshot().current().turn().streamingText());
        }
    }
    @Test void ownerCannotPublishToItsOwnQueueAndRealAdapterNotEnabled()throws Exception {
        var mailbox=new UiMailbox(1,Thread.currentThread());assertThrows(IllegalStateException.class,
                ()->mailbox.publish(new Task("A","turn",1,new Started())));mailbox.close();
        AgentAdapter real=new AgentAdapter(){public Mode mode(){return Mode.REAL_NATIVE;}public RunHandle start(Request r,EventSink s){throw new AssertionError();}public void close(){}};
        assertThrows(IllegalArgumentException.class,()->new UiEventLoop(seeds(),real,8));
        assertThrows(IllegalArgumentException.class,()->new UsageUpdated(1,0,false));
    }
    @Test void cancellingCompletedHandleCannotInterruptAnotherSessionOnReusedWorker()throws Exception {
        try(var adapter=new FakeAgentAdapter(r->r.text().equals("old")?FakeAgentAdapter.Scenario.NORMAL:FakeAgentAdapter.Scenario.STREAM,10,300)){
            var queue=new LinkedBlockingQueue<Task>();
            AgentAdapter.RunHandle old=adapter.start(new AgentAdapter.Request("A","old","old",false,"FAKE","test"),queue::put);
            Task event;do{event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);}while(!(event.signal() instanceof Completed));
            for(int i=0;i<6;i++){
                String turn="B-"+i;adapter.start(new AgentAdapter.Request("B",turn,"new",false,"FAKE","test"),queue::put);
                do{event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);}while(!(event.signal() instanceof Started));
                old.cancel();
                do{event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);}while(!(event.signal() instanceof Completed));
                assertEquals(turn,event.turnId());assertEquals(Outcome.COMPLETED,((Completed)event.signal()).outcome());
            }
        }
    }
    @Test void cancellationDoesNotLeaveAnApparentlyRunningTool()throws Exception {
        try(var adapter=new ScriptedAdapter();var loop=new UiEventLoop(seeds(),adapter,8)){
            submit(loop,"cancel");adapter.emit(1,new ToolStarted("call","fake_read"));loop.pump(8);loop.cancelCurrent();
            assertEquals("CANCEL_REQUESTED",loop.snapshot().current().turn().tools().get(0).status());
            adapter.emit(2,new ToolFinished("call",true,"late"));loop.pump(8);
            assertEquals("CANCEL_REQUESTED",loop.snapshot().current().turn().tools().get(0).status());
        }
    }
    @Test void normalReplyUsageUnknownAndInputResizeRemainSessionScoped()throws Exception {
        try(var loop=new UiEventLoop(seeds(),fake(FakeAgentAdapter.Scenario.NORMAL),8)){
            submit(loop,"normal");finished(loop);assertEquals("[FAKE] 正常回复",loop.snapshot().current().history().get(1).text());
            assertFalse(loop.snapshot().current().turn().usage().providerReported());assertNull(loop.snapshot().current().turn().usage().inputTokens());
            loop.edit(UiEventLoop.Edit.TEXT,"中文😀");loop.edit(UiEventLoop.Edit.BACKSPACE,"");loop.memory(true);loop.resize(20,6);
            assertEquals(6,TuiRuntimeMain.frame(loop.snapshot()).lines().size());loop.select("B");assertEquals("",loop.snapshot().current().input().text());
            loop.select("A");assertEquals("中文",loop.snapshot().current().input().text());assertTrue(loop.snapshot().current().memory());
        }
    }
    private static final class ScriptedAdapter implements AgentAdapter {
        final ExecutorService worker=Executors.newSingleThreadExecutor();final AtomicBoolean cancelled=new AtomicBoolean();
        Request request;EventSink sink;boolean approvalThrows;
        public Mode mode(){return Mode.FAKE_ONLY;}
        public RunHandle start(Request request,EventSink sink){this.request=request;this.sink=sink;return new RunHandle(){
            public void cancel(){cancelled.set(true);}
            public boolean resolveSimulatedApproval(String id,boolean approved){if(approvalThrows)throw new IllegalStateException("fake channel failed");return false;}
        };}
        void emit(long sequence,Signal signal)throws Exception{emitFor(request.sessionId(),request.turnId(),sequence,signal);}
        void emitFor(String session,String turn,long sequence,Signal signal)throws Exception{
            worker.submit(()->{try{sink.publish(new Task(session,turn,sequence,signal));}catch(InterruptedException stop){Thread.currentThread().interrupt();}}).get(1,TimeUnit.SECONDS);
        }
        public void close(){worker.shutdownNow();try{assertTrue(worker.awaitTermination(1,TimeUnit.SECONDS));}catch(InterruptedException stop){Thread.currentThread().interrupt();}}
    }
}
