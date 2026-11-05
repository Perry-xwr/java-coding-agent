package com.agent;

import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import com.agent.tui.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiP0Test {
    @TempDir Path root;
    static Response response(Request request,int code,String body){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OFFLINE").body(ResponseBody.create(body,MediaType.get("text/event-stream"))).build();}
    static String text(String value)throws Exception{return "data: "+new ObjectMapper().writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",value)))))+"\n\n";}
    static String end(){return "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}\n\ndata: [DONE]\n\n";}
    static void submit(TuiSessionController c,RealAgentAdapter a,String input)throws Exception{
        c.loop().edit(UiEventLoop.Edit.TEXT,input);assertNotNull(c.submit());long deadline=System.nanoTime()+5_000_000_000L;
        do{c.pump();Thread.sleep(2);}while((!a.available()||c.loop().snapshot().current().turn().running())&&System.nanoTime()<deadline);
        c.pump();assertTrue(a.available());assertFalse(c.loop().snapshot().current().turn().running());
    }
    @Test void threeScenariosUseFourDurableHttpAttemptsAndRestoreHistory()throws Exception{
        Files.createDirectories(root.resolve("experiment"));
        Path w=TuiSessionWorkspace.create(root.resolve("experiment/workspace"));Files.writeString(w.resolve("pom.xml"),"<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        // Explicit read-only validation profile: do not weaken the Unified CODE guard.
        try(var adapter=new RealAgentAdapter(w,m->{throw new AssertionError("seed must not call a Provider");});var c=new TuiSessionController(w,true,adapter)){assertTrue(c.createSession());}
        var manager=new CliConversationManager(w);var seed=manager.list().get(0);manager.activate(seed);var s=seed.snapshot();
        manager.checkpoint(new CliSessionSnapshot(s.version(),s.savedAt(),CliMode.READ,null,s.histories(),s.workingContext(),s.summary(),s.crossSessionMemory(),s.extraction()));
        Path ledger=root.resolve("experiment/evidence/ledger");var budget=new FinalApiBudget(ledger,true);var dispatched=new AtomicInteger();var requestBodies=new ArrayList<String>();String id;
        try(var client=new FinalApiValidationClient(budget,request->{
            int n=dispatched.incrementAndGet();assertEquals(n,budget.state().attempts()); // reservation is already durable
            var buffer=new okio.Buffer();request.body().writeTo(buffer);String body=buffer.readUtf8();requestBodies.add(body);
            assertFalse(body.contains("SHARED_PRIVATE_CANARY"));assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"));
            try{return response(request,200,switch(n){
                case 1 -> text("Java 是编程语言。")+end();
                case 2 -> text("Agent ")+text("读取、决策并执行工具。")+end();
                case 3 -> "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"read-pom\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"pom.xml\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
                case 4 -> text("Java 17。")+end();default -> throw new AssertionError("fifth dispatch");});}
            catch(Exception error){throw new IOException("OFFLINE_FIXTURE",error);}
        });var adapter=new RealAgentAdapter(w,client);var c=new TuiSessionController(w,true,adapter)){
            assertFalse(client.automaticRetryEnabled());assertFalse(client.redirectsEnabled());
            for(String task:List.of("请用一句话介绍 Java。","请简要解释 Agent 的工作流程。","读取当前测试工作区的 pom.xml，告诉我 Java 版本。")){
                submit(c,adapter,task);assertEquals("COMPLETED",c.loop().snapshot().current().turn().status(),task+" "+c.loop().snapshot().notice()+" "+c.loop().snapshot().current().turn().error()+" dispatched="+dispatched.get());
                if(dispatched.get()<=2)assertEquals(12,c.loop().snapshot().current().turn().usage().inputTokens());
            }
            id=c.loop().snapshot().currentSessionId();assertEquals("Java 17。",c.loop().snapshot().current().turn().streamingText());
            assertEquals(1,c.loop().snapshot().current().turn().tools().size());assertFalse(c.loop().snapshot().current().memory());
            assertEquals(4,budget.state().attempts());assertEquals(4,requestBodies.size());assertTrue(c.prepareExit());
            assertTrue(com.agent.tui.prototype.TuiFrameRenderer.frame(c.loop().snapshot(),new com.agent.tui.prototype.ConversationViewport(),true).lines().stream().anyMatch(line->line.toString().contains("Java 17")));
            assertNull(c.loop().snapshot().current().turn().usage().inputTokens()); // tool-call chunk omitted usage: task total UNKNOWN
        }
        var restarted=new FinalApiBudget(ledger,true);assertEquals(4,restarted.state().attempts());
        try(var client=new FinalApiValidationClient(restarted,r->{throw new AssertionError("fifth request sent");});var adapter=new RealAgentAdapter(w,client);var c=new TuiSessionController(w,true,adapter)){
            assertEquals(id,c.loop().snapshot().currentSessionId());assertEquals(3,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")).count());
            submit(c,adapter,"请用一句话介绍 Java。");assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertEquals(4,restarted.state().attempts());
        }
    }
    @Test void unauthorizedAndFailuresNeverRetryAndStopAcrossRestart()throws Exception{
        for(int status:List.of(0,401,402,429,503,307)){
            Path ledger=root.resolve("failure-"+status);var budget=new FinalApiBudget(ledger,true);var sent=new AtomicInteger();
            try(var client=new FinalApiValidationClient(budget,r->{sent.incrementAndGet();if(status==0)throw new IOException("FAKE_TIMEOUT");return response(r,status,"{}");})){
                assertThrows(IOException.class,()->client.stream(List.of(Message.user("你好")),List.of(),d->{}));
                assertThrows(IOException.class,()->client.stream(List.of(Message.user("你好")),List.of(),d->{}));assertEquals(1,sent.get());assertEquals(1,budget.state().attempts());
            }
            assertEquals("FAILED",new FinalApiBudget(ledger,true).state().status());
        }
        var denied=new FinalApiBudget(root.resolve("denied"),false);
        assertThrows(IOException.class,()->FinalApiValidationClient.authorized(denied,"synthetic-only",true));
        assertThrows(IOException.class,()->FinalApiValidationClient.authorized(new FinalApiBudget(root.resolve("not-confirmed"),true),"synthetic-only",false));
        try(var client=new FinalApiValidationClient(denied,r->{throw new AssertionError("unauthorized dispatch");})){
            assertThrows(IOException.class,()->client.chat(List.of(Message.user("你好"))));assertEquals(0,denied.state().attempts());
        }
    }
    @Test void forbiddenWriteToolIsDeniedByRealRuntimeAndUnknownUsageStaysUnknown()throws Exception{
        Path w=TuiSessionWorkspace.create(root.resolve("readonly"));var budget=new FinalApiBudget(root.resolve("readonly-ledger"),true);var count=new AtomicInteger();
        try(var client=new FinalApiValidationClient(budget,r->{
            if(count.incrementAndGet()==1)return response(r,200,"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"denied\",\"function\":{\"name\":\"create_file\",\"arguments\":\"{\\\"path\\\":\\\"forbidden.txt\\\",\\\"content\\\":\\\"no\\\"}\"}}]}}]}\n\ndata: [DONE]\n\n");
            try{return response(r,200,text("无法修改，仅报告实际结果。")+"data: [DONE]\n\n");}catch(Exception e){throw new IOException(e);}
        });var adapter=new RealAgentAdapter(w,client);var c=new TuiSessionController(w,true,adapter)){
            submit(c,adapter,"检查权限并解释，不要修改文件");assertFalse(Files.exists(w.resolve("forbidden.txt")));
            assertEquals("FAIL",c.loop().snapshot().current().turn().tools().get(0).status());
            assertFalse(c.loop().snapshot().current().turn().usage().providerReported());assertNull(c.loop().snapshot().current().turn().usage().inputTokens());assertEquals(2,count.get());
        }
    }
    @Test void missingDoneFailsAndCancellationCannotStartNextHttp()throws Exception{
        var budget=new FinalApiBudget(root.resolve("broken"),true);var sent=new AtomicInteger();
        try(var client=new FinalApiValidationClient(budget,r->{sent.incrementAndGet();return response(r,200,"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");})){
            var output=new StringBuilder();assertThrows(IOException.class,()->client.stream(List.of(Message.user("你好")),List.of(),output::append));assertEquals("partial",output.toString());
            assertEquals("FAILED",budget.state().status());assertEquals(1,sent.get());
        }
        var cancelled=new FinalApiBudget(root.resolve("cancelled"),true);
        try(var client=new FinalApiValidationClient(cancelled,r->{throw new AssertionError("cancelled dispatch");})){
            Thread.currentThread().interrupt();try{assertThrows(IOException.class,()->client.chat(List.of(Message.user("你好"))));}finally{Thread.interrupted();}
            assertEquals(0,cancelled.state().attempts());
        }
    }
    @Test void byteLimitAndDamagedLedgerFailClosed()throws Exception{
        Path ledger=root.resolve("size-ledger");var budget=new FinalApiBudget(ledger,true);
        try(var client=new FinalApiValidationClient(budget,r->{throw new AssertionError("oversize dispatch");})){
            assertThrows(IOException.class,()->client.chat(List.of(Message.user("中".repeat(12000)))));assertEquals(0,budget.state().attempts());
        }
        Files.writeString(ledger.resolve("attempts.json"),"{damaged");assertThrows(IOException.class,()->new FinalApiBudget(ledger,true));
        assertEquals("{damaged",Files.readString(ledger.resolve("attempts.json")));
    }
    @Test void cancellationOfRealCoreStopsBeforeNextHttpAttempt()throws Exception{
        Path w=TuiSessionWorkspace.create(root.resolve("cancel-workspace"));var budget=new FinalApiBudget(root.resolve("cancel-ledger"),true);
        var entered=new java.util.concurrent.CountDownLatch(1);var dispatched=new AtomicInteger();
        try(var client=new FinalApiValidationClient(budget,r->{
            dispatched.incrementAndGet();entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException("MOCK_INTERRUPTED");}
            throw new IOException("MOCK_TIMEOUT");
        });var adapter=new RealAgentAdapter(w,client);var c=new TuiSessionController(w,true,adapter)){
            c.loop().edit(UiEventLoop.Edit.TEXT,"解释 Java，不要修改文件");assertNotNull(c.submit());assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            c.loop().cancelCurrent();long end=System.nanoTime()+3_000_000_000L;while(!adapter.available()&&System.nanoTime()<end){c.pump();Thread.sleep(2);}c.pump();
            assertTrue(adapter.available());assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());assertEquals(1,dispatched.get());assertEquals(1,budget.state().attempts());assertEquals("FAILED",budget.state().status());
        }
    }
}
