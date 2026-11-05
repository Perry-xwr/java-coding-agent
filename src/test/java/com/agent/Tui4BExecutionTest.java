package com.agent;

import com.agent.llm.*;
import com.agent.tool.*;
import com.agent.tool.execution.*;
import com.agent.execution.secure.*;
import com.agent.tui.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class Tui4BExecutionTest {
    @TempDir Path temp;
    Path workspace()throws Exception{Path w=TuiSessionWorkspace.create(temp.resolve(UUID.randomUUID().toString()));Files.writeString(w.resolve("note.txt"),"old\n");return w;}
    void submit(TuiSessionController c,String text){c.loop().edit(UiEventLoop.Edit.TEXT,text);assertNotNull(c.submit());}
    void finish(TuiSessionController c,RealAgentAdapter adapter)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;while((c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertTrue(adapter.available());assertFalse(c.loop().snapshot().current().turn().running());
    }
    void approval(TuiSessionController c)throws Exception{long end=System.nanoTime()+2_000_000_000L;while(c.loop().snapshot().localApproval()==null&&System.nanoTime()<end){c.pump();Thread.sleep(3);}assertNotNull(c.loop().snapshot().localApproval());}
    LLMResponse patch(){return new LLMResponse("",List.of(new ToolCall("patch","apply_context_patch","{\"path\":\"note.txt\",\"expectedContent\":\"old\\n\",\"edits\":[{\"search\":\"old\",\"replace\":\"new\"}]}")));}
    @Test void realAgentActualFileEditUsesOriginalSnapshotAndDiffWithoutNewApprovalPolicy()throws Exception{
        Path w=workspace();var step=new AtomicInteger();
        LLMClient mock=m->switch(step.incrementAndGet()){
            case 1->new LLMResponse("",List.of(new ToolCall("read","read_file","{\"path\":\"note.txt\"}")));
            case 2->patch();default->new LLMResponse("已修改 note.txt；功能测试未运行。",List.of());};
        try(var adapter=RealAgentAdapter.forFileEditing(w,mock);var c=new TuiSessionController(w,true,adapter)){
            submit(c,"请把 note.txt 中的 old 修改为 new。");finish(c,adapter);
            assertEquals("new\n",Files.readString(w.resolve("note.txt")));assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertNull(c.loop().snapshot().localApproval());
            assertTrue(c.diffCommand());assertTrue(c.loop().snapshot().current().history().stream().anyMatch(m->m.text().contains("+new")));
            var saved=new CliConversationManager(w).list().get(0).snapshot().histories().get(CliMode.CODE);
            assertEquals(2,saved.stream().filter(m->m.role().equals("tool")).count());
            assertTrue(saved.stream().anyMatch(m->m.role().equals("system")&&m.content().contains("RUNTIME_AUTO_REREAD")&&m.content().contains("new")));
        }
        var registry=ToolRegistry.withCliCodingTools(w,(a,b,d,e)->{throw new AssertionError("No process");},w.resolve(".m2/repository"));
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,registry.execute("create_file","{\"path\":\"../outside.txt\",\"content\":\"bad\"}").errorCode());
        var stale=registry.execute("apply_context_patch","{\"path\":\"note.txt\",\"expectedContent\":\"old\\n\",\"edits\":[{\"search\":\"old\",\"replace\":\"bad\"}]}");
        assertEquals(ToolErrorCode.STALE_EDIT_CONTEXT,stale.errorCode());assertEquals("new\n",Files.readString(w.resolve("note.txt")));
    }
    @Test void realCoreMavenExactConfirmationStartsOnceAndResultIsPersisted()throws Exception{
        Path w=workspace();Files.writeString(w.resolve("pom.xml"),"<project/>");var calls=new AtomicInteger();var model=new AtomicInteger();
        LLMClient provider=m->model.incrementAndGet()==1?new LLMResponse("",List.of(new ToolCall("maven","run_maven_test","{}"))):new LLMResponse("已运行一个测试并通过。",List.of());
        try(var channel=TestingTuiChannels.create();var adapter=new RealAgentAdapter(w,provider,true,channel,(a,b,c,d)->{calls.incrementAndGet();return new ProcessExecutionResult(0,false,"Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",false,1);},w.resolve(".m2/repository"));var c=new TuiSessionController(w,true,adapter)){
            submit(c,"检查测试情况，不要修改文件。");approval(c);var view=channel.pending();assertTrue(c.loop().snapshot().localApproval().nativeRequest());assertEquals(0,calls.get());
            c.loop().resize(60,18);assertNotNull(com.agent.tui.prototype.TuiSessionsMain.frame(c.loop().snapshot(),new com.agent.tui.prototype.ConversationViewport(),true));
            assertTrue(c.approvalCommand("confirm "+view.requestId()+" APPROVE "+view.prompt().challenge()));finish(c,adapter);assertEquals(1,calls.get());
            assertFalse(c.approvalCommand("confirm "+view.requestId()+" APPROVE "+view.prompt().challenge()));assertEquals(1,calls.get());
            assertTrue(new CliConversationManager(w).list().get(0).snapshot().histories().get(CliMode.CODE).stream().anyMatch(m->m.role().equals("tool")&&m.content().contains("TEST_PASSED")&&m.content().contains("approvalStatus=APPROVED")&&m.content().contains("executed=true")));
        }
    }
    @Test void denialBlocksR2RetryAndSessionSwitchRevokesOnlyPendingRequest()throws Exception{
        Path w=workspace();Files.writeString(w.resolve("pom.xml"),"<project/>");var calls=new AtomicInteger();var model=new AtomicInteger();
        LLMClient provider=m->model.incrementAndGet()<=2?new LLMResponse("",List.of(new ToolCall("maven-"+model.get(),"run_maven_test","{}"))):new LLMResponse("审批未获准，没有测试通过证据。",List.of());
        try(var channel=TestingTuiChannels.create();var adapter=new RealAgentAdapter(w,provider,true,channel,(a,b,c,d)->{calls.incrementAndGet();throw new AssertionError("Denied process launched");},w.resolve(".m2/repository"));var c=new TuiSessionController(w,true,adapter)){
            submit(c,"检查测试状态，不要修改文件。");String a=c.loop().snapshot().currentSessionId();approval(c);assertTrue(c.createSession());String b=c.loop().snapshot().currentSessionId();
            long end=System.nanoTime()+4_000_000_000L;while(!adapter.available()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();
            assertEquals(0,calls.get());assertTrue(c.loop().snapshot().current().history().isEmpty());assertNotEquals(a,b);
            var history=new CliConversationManager(w).load(a).snapshot().histories().get(CliMode.CODE);
            assertTrue(history.stream().anyMatch(m->m.role().equals("tool")&&m.content().contains("APPROVAL_DENIED")));
            assertTrue(history.stream().anyMatch(m->m.role().equals("tool")&&m.content().contains("MAVEN_RETRY_BLOCKED")));
            assertEquals(1,channel.audit().stream().filter(v->v.status().equals("REQUESTED")).count());
        }
    }
    @Test void cancellationAfterActualMutationRetainsPairedHistoryAndActualDiff()throws Exception{
        Path w=workspace();var step=new AtomicInteger();var waiting=new CountDownLatch(1);
        LLMClient provider=m->{int n=step.incrementAndGet();if(n==1)return new LLMResponse("",List.of(new ToolCall("read","read_file","{\"path\":\"note.txt\"}")));if(n==2)return patch();
            waiting.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stopped){throw new IOException("stopped");}throw new AssertionError();};
        try(var adapter=RealAgentAdapter.forFileEditing(w,provider);var c=new TuiSessionController(w,true,adapter)){
            submit(c,"把 note.txt 的 old 修改为 new");assertTrue(waiting.await(3,TimeUnit.SECONDS));c.cancel();
            long end=System.nanoTime()+3_000_000_000L;while(!adapter.available()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();
            assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());assertEquals("new\n",Files.readString(w.resolve("note.txt")));assertTrue(c.diffCommand());
            var saved=new CliConversationManager(w).list().get(0).snapshot();
            CliAgentFactory.createProfiles(m->{throw new IOException();},w,com.agent.agent.AgentEventListener.NO_OP).validateHistories(saved.histories());
            assertTrue(saved.histories().get(CliMode.CODE).stream().anyMatch(m->m.role().equals("tool")&&m.content().contains("new")));
        }
    }
}
