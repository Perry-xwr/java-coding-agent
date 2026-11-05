package com.agent;

import com.agent.llm.*;
import com.agent.tui.runtime.*;
import com.agent.tui.prototype.DailyTuiMain;
import com.agent.execution.secure.*;
import com.agent.tool.execution.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyRuntimeTest {
    @TempDir Path root;
    Path workspace()throws Exception{return Files.createDirectory(root.resolve(UUID.randomUUID().toString()));}
    String event(Object delta)throws Exception{return "data: "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("choices",List.of(Map.of("delta",delta))))+"\n\n";}
    String text(String text)throws Exception{return event(Map.of("content",text))+"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}\n\ndata: [DONE]\n\n";}
    String call(String name,String args)throws Exception{return event(Map.of("tool_calls",List.of(Map.of("index",0,"id",UUID.randomUUID().toString(),"function",Map.of("name",name,"arguments",args)))))+"data: [DONE]\n\n";}
    Response reply(Request r,String body){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(body,MediaType.get("text/event-stream"))).build();}
    Response answer(Request r,String body)throws IOException{try{return reply(r,text(body));}catch(Exception e){throw new IOException(e);}}
    void send(TuiSessionController c,DailyAgentAdapter a,String text)throws Exception{c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,text);assertNotNull(c.submit());finish(c,a);}
    void finish(TuiSessionController c,DailyAgentAdapter a)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;while((c.loop().snapshot().current().turn().running()||!a.available())&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertTrue(a.available());assertFalse(c.loop().snapshot().current().turn().running());
    }
    @Test void dailyRealCoreCanExceedExperimentQuotaAndRestoreSessions()throws Exception{
        Path w=workspace();for(int i=0;i<7;i++)Files.writeString(w.resolve("f"+i+".txt"),"content "+i);var count=new AtomicInteger();String id;
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{
            int n=count.incrementAndGet();try{return n<=7?reply(r,call("read_file","{\"path\":\"f"+(n-1)+".txt\"}")):answer(r,"读取或解释已完成；未修改文件。");}catch(Exception e){throw new IOException(e);}
        });var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            assertEquals(0,client.attempts());assertEquals("DeepSeek",c.loop().snapshot().current().provider());
            send(c,a,"读取这些文本并解释，不要修改文件。");assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(8,client.attempts());assertEquals(7,c.loop().snapshot().current().turn().tools().size());
            id=c.loop().snapshot().currentSessionId();assertEquals("UNIFIED",c.loop().snapshot().current().profile());
            for(int i=0;i<5;i++)send(c,a,"请解释 Java 的一个概念 "+i+"，不要修改文件。");assertEquals(13,client.attempts());
            String status=a.validationStatus();assertTrue(status.contains("PROVIDER_REPORTED"));assertFalse(status.contains("4/4"));assertFalse(status.contains("NEW_LEDGER"));assertFalse(Files.exists(w.resolve("evidence/http-ledger")));
            assertNull(a.submissionRejection("任意自然语言"));assertTrue(c.dailyProfile(CliMode.READ));assertEquals("READ",c.loop().snapshot().current().profile());
        }
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("restart should not request");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            assertEquals(id,c.loop().snapshot().currentSessionId());assertEquals("READ",c.loop().snapshot().current().profile());assertEquals(0,c.loop().snapshot().current().history().stream().filter(x->x.role().equals("You")).count()); // READ history is separate
            assertTrue(c.dailyProfile(CliMode.CODE));assertEquals(6,c.loop().snapshot().current().history().stream().filter(x->x.role().equals("You")).count());assertEquals(0,client.attempts());
        }
    }
    @Test void editingKeepsSnapshotDiffAndWorkspaceBoundaries()throws Exception{
        Path w=workspace();Files.writeString(w.resolve("note.txt"),"old\n");var n=new AtomicInteger();var processes=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{
            try{return switch(n.incrementAndGet()){
                case 1->reply(r,call("read_file","{\"path\":\"note.txt\"}"));
                case 2->reply(r,call("apply_context_patch","{\"path\":\"note.txt\",\"expectedContent\":\"old\\n\",\"edits\":[{\"search\":\"old\",\"replace\":\"new\"}]}"));
                case 4->reply(r,call("create_file","{\"path\":\"../outside.txt\",\"content\":\"bad\"}"));
                default->answer(r,"如实报告：文件状态以工具结果为准，功能测试未运行。");};}catch(Exception e){throw new IOException(e);}
        });var a=new DailyAgentAdapter(lease,client,null,(cmd,dir,t,m)->{processes.incrementAndGet();throw new AssertionError("text edit must not execute processes");});var c=new TuiSessionController(w,true,a)){
            send(c,a,"将 note.txt 的 old 修改为 new。");assertEquals("new\n",Files.readString(w.resolve("note.txt")));assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertTrue(c.diffCommand());
            assertTrue(c.loop().snapshot().current().history().stream().anyMatch(x->x.text().contains("+new")));assertNull(c.loop().snapshot().localApproval());
            send(c,a,"检查权限并解释，不要修改文件。");assertFalse(Files.exists(w.getParent().resolve("outside.txt")));assertEquals("FAIL",c.loop().snapshot().current().turn().tools().get(0).status());assertEquals(0,processes.get());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void mavenKeepsExactR1AndZeroLaunchUntilDecision(boolean approve)throws Exception{
        Path w=workspace();Files.writeString(w.resolve("pom.xml"),"<project/>");var model=new AtomicInteger();var processes=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var channel=TestingTuiChannels.create();var client=DailyDeepSeekClient.offline(r->{try{return model.incrementAndGet()==1?reply(r,call("run_maven_test","{}")):answer(r,"测试状态以真实工具结果为准。");}catch(Exception e){throw new IOException(e);}});
            var a=new DailyAgentAdapter(lease,client,channel,(cmd,dir,t,m)->{processes.incrementAndGet();return new ProcessExecutionResult(0,false,"Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",false,1);});var c=new TuiSessionController(w,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,"检查 Maven 测试情况，不要修改文件。");assertNotNull(c.submit());long end=System.nanoTime()+3_000_000_000L;
            while(c.loop().snapshot().localApproval()==null&&System.nanoTime()<end){c.pump();Thread.sleep(3);}assertNotNull(channel.pending(),()->"Turn="+c.loop().snapshot().current().turn()+" notice="+c.loop().snapshot().notice());assertEquals(0,processes.get());var view=channel.pending();
            c.approvalCommand("confirm "+view.requestId()+" "+(approve?"APPROVE "+view.prompt().challenge():"Y"));finish(c,a);assertEquals(approve?1:0,processes.get());
            assertFalse(c.approvalCommand("confirm "+view.requestId()+" APPROVE "+view.prompt().challenge()));assertEquals(approve?1:0,processes.get());
        }
    }
    @Test void memoryOffOnOffUsesActualRequestContextAndSameStore()throws Exception{
        Path w=workspace();var bodies=new ArrayList<String>();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var bytes=new okio.Buffer();r.body().writeTo(bytes);bodies.add(bytes.readUtf8());return answer(r,"按提供的上下文解释。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            assertTrue(c.createSession());String first=c.loop().snapshot().currentSessionId();assertFalse(c.loop().snapshot().current().memory());
            assertTrue(c.memoryCommand("global on"));assertTrue(c.memoryCommand("remember 项目使用 Java 17，约定标识 DAILY_MEMORY_CANARY"));
            assertTrue(c.createSession());String second=c.loop().snapshot().currentSessionId();assertFalse(c.loop().snapshot().current().memory());
            send(c,a,"解释项目 Java 版本，不要修改文件。");assertFalse(bodies.get(0).contains("DAILY_MEMORY_CANARY"));
            assertTrue(c.memoryCommand("global on"));send(c,a,"解释项目 Java 版本，不要修改文件。");assertTrue(bodies.get(1).contains("DAILY_MEMORY_CANARY"));
            assertTrue(c.memoryCommand("global off"));send(c,a,"解释项目 Java 版本，不要修改文件。");assertFalse(bodies.get(2).contains("DAILY_MEMORY_CANARY"));
            assertTrue(c.select(first));assertTrue(c.loop().snapshot().current().memory());assertTrue(c.select(second));assertFalse(c.loop().snapshot().current().memory());
        }
    }
    @Test void errorDoesNotRetryAndExplicitNextTaskStillWorks()throws Exception{
        Path w=workspace();var n=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{if(n.incrementAndGet()==1)throw new IOException("offline disconnect");return answer(r,"已解释。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            send(c,a,"解释 Java，不要修改文件。");assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertEquals(1,n.get());
            send(c,a,"请解释 Python，不要修改文件。");assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(2,n.get());
        }
    }
    @Test void workspaceLeaseAndEntryConfigurationFailClosed()throws Exception{
        Path w=workspace();assertEquals(w,DailyTuiMain.workspace(new String[]{"--workspace",w.toString()}));assertThrows(IllegalArgumentException.class,()->DailyDeepSeekClient.configured(" "));
        try(var lease=DailyWorkspace.acquire(w)){assertThrows(IOException.class,()->DailyWorkspace.acquire(w));assertEquals(w,lease.verify(w));}
        Path experimental=FinalApiWorkspace.create(root.resolve("experiment"),true);assertThrows(IOException.class,()->DailyWorkspace.acquire(experimental));
        Path mock=TuiSessionWorkspace.create(root.resolve("mock"));assertThrows(IOException.class,()->DailyWorkspace.acquire(mock));
    }
    @Test void cancellationInterruptsTransportAndDoesNotAutoRequestAgain()throws Exception{
        Path w=workspace();var entered=new java.util.concurrent.CountDownLatch(1);var count=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{int n=count.incrementAndGet();if(n>1)return answer(r,"新任务回复。");entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException("cancelled");}throw new IOException("timeout");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,"解释 Java，不要修改文件。");c.submit();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));c.loop().cancelCurrent();finish(c,a);
            assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());assertEquals(1,count.get());
            send(c,a,"解释新的问题，不要修改文件。");assertEquals(2,count.get());assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
        }
    }
    @Test void browsingAnotherSessionCannotReceiveOrCancelOriginTurn()throws Exception{
        Path w=workspace();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var bodies=new java.util.concurrent.CopyOnWriteArrayList<String>();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var bytes=new okio.Buffer();r.body().writeTo(bytes);bodies.add(bytes.readUtf8());if(bodies.size()==1){entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("unexpected cancel");}}return answer(r,"对应会话的回复。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,"解释 PRIVATE_SESSION_ALPHA，不要修改文件。");c.submit();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));String first=c.loop().snapshot().currentSessionId();
            assertTrue(c.createSession());String second=c.loop().snapshot().currentSessionId();c.loop().cancelCurrent();assertFalse(a.available());release.countDown();
            long end=System.nanoTime()+4_000_000_000L;while(!a.available()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertTrue(a.available());assertTrue(c.loop().snapshot().current().history().isEmpty());
            assertEquals(1,new CliConversationManager(w).load(first).snapshot().histories().get(CliMode.CODE).stream().filter(m->m.role().equals("assistant")&&!m.content().isBlank()).count());
            send(c,a,"解释另一个问题，不要修改文件。");assertFalse(bodies.get(1).contains("PRIVATE_SESSION_ALPHA"));assertEquals(second,c.loop().snapshot().currentSessionId());
        }finally{release.countDown();}
    }

    @Test void explicitNoChangeDiscussionCompletesOnceInCodeAndReadProfiles()throws Exception{
        String task="以后讨论新功能时先比较方案优缺点，再给出推荐。\n"
                +"引入第三方依赖前先评估 JDK 原生能力。\n"
                +"性能优化前先实际测量瓶颈。\n"
                +"这次只是交流，不需要修改任何文件。";
        for(CliMode profile:List.of(CliMode.CODE,CliMode.READ)){
            Path w=workspace();Path doc=Files.writeString(w.resolve("DEVELOPMENT_PRINCIPLES.md"),"original\n");var calls=new AtomicInteger();
            try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{
                calls.incrementAndGet();var bytes=new okio.Buffer();r.body().writeTo(bytes);var request=new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes.readUtf8());
                for(var tool:request.path("tools"))assertFalse(Set.of("apply_patch","apply_context_patch","create_file","insert_before","insert_after","run_maven_test").contains(tool.path("function").path("name").asText()));
                return answer(r,"已记录这次交流中的偏好；不会修改文件。\n");
            });var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
                assertTrue(c.dailyProfile(profile));send(c,a,task);
                assertEquals("COMPLETED",c.loop().snapshot().current().turn().status(),profile.toString());
                assertEquals(1,calls.get(),"a valid model final answer ends this ordinary conversation after one request");
                assertEquals(1,c.loop().snapshot().current().history().stream().filter(h->h.role().equals("Agent")).count());
                assertTrue(c.loop().snapshot().current().turn().tools().isEmpty());
                assertTrue(c.loop().snapshot().current().history().stream().noneMatch(h->h.text().contains("PREMATURE_FINAL_GUARD")||h.text().contains("MUTATION_FAILURE_FINAL")));
                assertEquals("original\n",Files.readString(doc));
            }
        }
    }

    @Test void requiredMutationRepeatedFinalFailsOnceWithSpecificReasonInsteadOfIterationExhaustion()throws Exception{
        Path w=workspace();Path file=Files.writeString(w.resolve("note.txt"),"old\n");var calls=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{calls.incrementAndGet();return answer(r,"已经完成。\n");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            assertTrue(c.dailyProfile(CliMode.CODE));send(c,a,"把 note.txt 修改为 new。只回复完成即可。");
            assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertEquals(2,calls.get());
            assertTrue(c.loop().snapshot().notice().contains("WORKSPACE_CHANGE_NOT_COMPLETED"),c.loop().snapshot().notice());
            assertFalse(c.loop().snapshot().notice().contains("maximum iterations"));
            assertEquals("old\n",Files.readString(file));
        }
    }

    @Test void repeatedWriteToolCallsInExplicitDiscussionStopAfterOneRecoveryRequest()throws Exception{
        Path w=workspace();var calls=new AtomicInteger();String args="{\"path\":\"unsolicited.md\",\"content\":\"bad\"}";
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{
            try{calls.incrementAndGet();return reply(r,call("create_file",args));}catch(Exception failure){throw new IOException(failure);}
        });var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            assertTrue(c.dailyProfile(CliMode.CODE));send(c,a,"这次只是交流，不需要修改任何文件。\n以后讨论新功能先比较方案。\n");
            assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertEquals(2,calls.get());
            assertTrue(c.loop().snapshot().notice().contains("UNREQUESTED_MUTATION_REPEATED"),c.loop().snapshot().notice());
            assertFalse(c.loop().snapshot().notice().contains("maximum iterations"));assertFalse(Files.exists(w.resolve("unsolicited.md")));
        }
    }
}
