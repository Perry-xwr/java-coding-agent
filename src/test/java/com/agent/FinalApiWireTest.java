package com.agent;

import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import com.agent.tui.runtime.*;
import com.agent.tui.prototype.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import okhttp3.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiWireTest {
    @TempDir Path root;
    Path workspace()throws Exception{return FinalApiWorkspace.create(root.resolve(UUID.randomUUID().toString()),false);}
    FinalApiBudget budget(Path w)throws Exception{return new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true);}
    void send(TuiSessionController c,FinalApiTuiAdapter a,String message)throws Exception{
        c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,message);assertNotNull(c.submit());waitFor(c,a);
    }
    void waitFor(TuiSessionController c,FinalApiTuiAdapter a)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;while((c.loop().snapshot().current().turn().running()||!a.available())&&System.nanoTime()<end){c.pump();Thread.sleep(2);}c.pump();assertTrue(a.available());assertFalse(c.loop().snapshot().current().turn().running());
    }
    Response reply(Request r,String s){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(s,MediaType.get("text/event-stream"))).build();}
    String text(String s){return "data: {\"choices\":[{\"delta\":{\"content\":\""+s+"\"}}]}\n\n";}
    String end(){return "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}\n\ndata: [DONE]\n\n";}
    @Test void newWireThreeScenariosStreamingToolsLabelsAndRestart()throws Exception{
        Path w=workspace();var budget=budget(w);var dispatch=new AtomicInteger();String id;
        try(var client=new FinalApiValidationClient(budget,r->{
            int n=dispatch.incrementAndGet();assertEquals(n,budget.state().attempts());
            var body=new okio.Buffer();r.body().writeTo(body);String request=body.readUtf8();assertTrue(request.contains("\"max_tokens\":256"));assertFalse(request.contains("\"name\":\"create_file\""));
            return reply(r,n==3?"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"pom-call\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"pom.xml\\\"}\"}}]}}]}\n\ndata: [DONE]\n\n":text(n==4?"Java 17。":"中文")+(n==4?"":text("增量回复。"))+end());
        });var adapter=new FinalApiTuiAdapter(w,client,budget,false);var c=new TuiSessionController(w,true,adapter)){
            assertEquals("DeepSeek",c.loop().snapshot().current().provider());assertTrue(c.loop().snapshot().current().model().contains("MOCK HTTP"));
            send(c,adapter,"请用一句话介绍 Java。");id=c.loop().snapshot().currentSessionId();
            c.loop().edit(UiEventLoop.Edit.TEXT,"请用一句话介绍 Java。");assertNull(c.submit());assertTrue(c.loop().snapshot().notice().contains("ALREADY_SUBMITTED"));assertEquals(1,dispatch.get());
            assertEquals("READ",c.loop().snapshot().current().profile());assertEquals("中文增量回复。",c.loop().snapshot().current().turn().streamingText());
            assertFalse(c.memoryCommand("global on"));assertFalse(c.memoryCommand("auto on"));assertFalse(c.approvalCommand("demo"));
            send(c,adapter,"请简要解释 Agent 的工作流程。");send(c,adapter,"读取当前测试工作区的 pom.xml，告诉我 Java 版本。");
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals("Java 17。",c.loop().snapshot().current().turn().streamingText());
            assertEquals("OK",c.loop().snapshot().current().turn().tools().get(0).status());assertFalse(c.loop().snapshot().current().memory());assertFalse(c.loop().snapshot().current().autoMemory());
            c.validationStatus();assertTrue(c.loop().snapshot().current().history().get(c.loop().snapshot().current().history().size()-1).text().contains("4/4"));
            c.loop().edit(UiEventLoop.Edit.TEXT,"第五次不应发送");assertNull(c.submit());assertEquals(4,dispatch.get());
            var saved=new CliConversationManager(w).load(id).snapshot();assertEquals(3,saved.histories().get(CliMode.READ).stream().filter(m->m.role().equals("assistant")&&!m.content().isBlank()).count());
            assertTrue(c.prepareExit());
        }
        var restart=budget(w);
        try(var client=new FinalApiValidationClient(restart,r->{throw new AssertionError("restart dispatch");});var a=new FinalApiTuiAdapter(w,client,restart,false);var c=new TuiSessionController(w,true,a)){
            assertEquals(id,c.loop().snapshot().currentSessionId());assertFalse(a.canSubmit());assertEquals(4,restart.state().attempts());
        }
        try(var files=Files.list(w.getParent().resolve("evidence"))){assertEquals(3,files.filter(p->p.getFileName().toString().startsWith("turn-")).count());}
    }
    @Test void entryFlagsWorkspaceAndUnsafeSessionFailClosed()throws Exception{
        assertFalse(FinalApiTuiMain.options(new String[]{"--create",root.resolve("mock").toString()}).live());
        assertThrows(IllegalArgumentException.class,()->FinalApiTuiMain.options(new String[]{"--create",root.toString(),"--provider","deepseek"}));
        assertThrows(IllegalArgumentException.class,()->FinalApiTuiMain.options(new String[]{"--create",root.toString(),"--provider","glm"}));
        assertTrue(FinalApiTuiMain.options(new String[]{"--create",root.toString(),"--provider","deepseek","--enable-real-api"}).live()); // parsing only, no client or network
        Path w=workspace();assertThrows(IOException.class,()->FinalApiWorkspace.check(w.getParent(),true));
        try(var client=new FinalApiValidationClient(budget(w),r->{throw new AssertionError();});var a=new FinalApiTuiAdapter(w,client,budget(w),false);var c=new TuiSessionController(w,true,a)){
            assertTrue(c.createSession());var s=new CliConversationManager(w).list().get(0).snapshot();
            assertThrows(IllegalArgumentException.class,()->a.prepare("bad",new CliSessionSnapshot(s.version(),s.savedAt(),CliMode.CODE,null,s.histories(),s.workingContext())));
            assertThrows(IllegalArgumentException.class,()->a.prepare("bad",new CliSessionSnapshot(s.version(),s.savedAt(),CliMode.READ,null,s.histories(),s.workingContext(),s.summary(),s.crossSessionMemory().withEnabled(true),s.extraction())));
        }
        Files.writeString(w.resolve("pom.xml"),"changed");assertThrows(IOException.class,()->FinalApiWorkspace.check(w.getParent(),false));
    }
    @Test void providerFailureAndCancellationStopTheWholeRun()throws Exception{
        Path w=workspace();var b=budget(w);var count=new AtomicInteger();
        try(var client=new FinalApiValidationClient(b,r->{count.incrementAndGet();throw new IOException("offline failure");});var a=new FinalApiTuiAdapter(w,client,b,false);var c=new TuiSessionController(w,true,a)){
            send(c,a,"请用一句话介绍 Java。");assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertFalse(a.canSubmit());assertEquals(1,count.get());
        }
        Path second=workspace();var secondBudget=budget(second);var entered=new java.util.concurrent.CountDownLatch(1);
        try(var client=new FinalApiValidationClient(secondBudget,r->{entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException("cancelled");}throw new IOException("timeout");});var a=new FinalApiTuiAdapter(second,client,secondBudget,false);var c=new TuiSessionController(second,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,FinalApiTuiAdapter.SCENARIOS.get(1));c.submit();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));c.loop().cancelCurrent();waitFor(c,a);
            assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());assertFalse(a.canSubmit());assertEquals(1,secondBudget.state().attempts());assertEquals("FAILED",secondBudget.state().status());
        }
    }
    @Test void exitingDuringRequestStopsLedgerAndTransport()throws Exception{
        Path w=workspace();var b=budget(w);var entered=new java.util.concurrent.CountDownLatch(1);var count=new AtomicInteger();
        var client=new FinalApiValidationClient(b,r->{count.incrementAndGet();entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("shutdown");}throw new IOException("timeout");});
        var adapter=new FinalApiTuiAdapter(w,client,b,false);var controller=new TuiSessionController(w,true,adapter);
        try{
            controller.loop().edit(UiEventLoop.Edit.TEXT,FinalApiTuiAdapter.SCENARIOS.get(0));assertNotNull(controller.submit());assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        }finally{controller.close();adapter.close();client.close();}
        assertEquals("FAILED",b.state().status());assertEquals(1,count.get());assertEquals(1,new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true).state().attempts());
    }
    @Test void exactDefaultEntryMockTransportSupportsAllThreeScenarios()throws Exception{
        Path w=workspace();var b=budget(w);var method=FinalApiTuiMain.class.getDeclaredMethod("mock",Request.class);method.setAccessible(true);
        try(var client=new FinalApiValidationClient(b,r->{try{return (Response)method.invoke(null,r);}catch(ReflectiveOperationException failure){throw new IOException("offline entry fixture",failure);}});
            var a=new FinalApiTuiAdapter(w,client,b,false);var c=new TuiSessionController(w,true,a)){
            for(String task:FinalApiTuiAdapter.SCENARIOS){send(c,a,task);assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());}
            assertEquals(4,b.state().attempts());assertEquals("read_file",c.loop().snapshot().current().turn().tools().get(0).name());
            assertFalse(c.loop().snapshot().current().turn().usage().providerReported());
        }
    }
}
