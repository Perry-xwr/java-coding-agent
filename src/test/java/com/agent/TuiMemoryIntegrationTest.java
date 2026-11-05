package com.agent;

import com.agent.llm.*;
import com.agent.memory.*;
import com.agent.tui.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TuiMemoryIntegrationTest {
    @TempDir Path root;
    Path workspace()throws Exception{Path w=TuiSessionWorkspace.create(root.resolve(UUID.randomUUID().toString()));Files.writeString(w.resolve("demo.txt"),"demo");return w;}
    void command(TuiSessionController c,String action){assertTrue(c.memoryCommand(action));}
    void turn(TuiSessionController c,RealAgentAdapter adapter,String task)throws Exception{
        c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,task);assertNotNull(c.submit());
        long end=System.nanoTime()+5_000_000_000L;
        do{c.pump();Thread.sleep(3);}while((c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<end);
        c.pump();assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertTrue(adapter.available());
    }
    CliSessionSnapshot stored(Path w,String id)throws Exception{return new CliConversationManager(w).load(id).snapshot();}
    List<LongTermMemory> entries(Path w)throws Exception{return new CliConversationManager(w).crossSessionMemory(()->true).listAuthorized();}
    String output(TuiSessionController c){return c.loop().snapshot().current().history().stream().filter(m->m.role().equals("System")).map(m->m.text()).collect(java.util.stream.Collectors.joining("\n"));}

    @Test void localCommandsPreservePermissionSessionStateAndNeverReachProvider()throws Exception{
        Path w=workspace();var calls=new AtomicInteger();String a,b;
        try(var adapter=new RealAgentAdapter(w,m->{calls.incrementAndGet();throw new java.io.IOException("must not call");});var c=new TuiSessionController(w,true,adapter)){
            assertFalse(c.memoryCommand("global status"));assertTrue(new CliConversationManager(w).list().isEmpty());
            assertTrue(c.createSession());a=c.loop().snapshot().currentSessionId();assertFalse(c.loop().snapshot().current().memory());
            for(String action:List.of("remember 项目使用 Java 17","list","search Java","update "+UUID.randomUUID()+" x","forget "+UUID.randomUUID(),"pending","harvest"))command(c,action);
            assertFalse(Files.exists(w.resolve(".agent-cli/memory/shared.json")));
            command(c,"global on");command(c,"auto on");command(c,"remember 项目使用 Java 17");
            var memory=entries(w).get(0);command(c,"list");assertTrue(output(c).contains(memory.memoryId()));
            command(c,"retrieval hybrid");assertEquals("hybrid",c.loop().snapshot().current().retrievalMode());assertTrue(c.loop().snapshot().current().autoMemory());
            assertTrue(c.createSession());b=c.loop().snapshot().currentSessionId();assertFalse(c.loop().snapshot().current().memory());assertFalse(c.loop().snapshot().current().autoMemory());
            command(c,"list");assertFalse(output(c).contains(memory.memoryId()));command(c,"global on");command(c,"search Java");assertTrue(output(c).contains(memory.memoryId()));
            command(c,"update "+memory.memoryId()+" 项目使用 Java 21");assertEquals("项目使用 Java 21",entries(w).get(0).content());
            command(c,"search Java");assertTrue(output(c).contains("Java 21"));command(c,"forget "+memory.memoryId());assertTrue(entries(w).isEmpty());
            command(c,"global off");assertTrue(c.select(a));assertTrue(c.loop().snapshot().current().memory());assertTrue(c.loop().snapshot().current().autoMemory());assertEquals("hybrid",c.loop().snapshot().current().retrievalMode());
            assertEquals(0,calls.get());assertTrue(stored(w,a).histories().get(CliMode.CODE).stream().noneMatch(m->m.role().equals("user")));
        }
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            assertTrue(c.select(b));assertFalse(c.loop().snapshot().current().memory());assertTrue(entries(w).isEmpty());
        }
    }
    @Test void autoCandidatesApprovalConflictReplacementVersionsAndRejectionReuseExistingWorkflow()throws Exception{
        Path w=workspace();String id;
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            c.createSession();id=c.loop().snapshot().currentSessionId();command(c,"global on");command(c,"auto on");
            turn(c,adapter,"项目使用 Java 17。请解释这些约定，不要修改文件。");
            var candidate=stored(w,id).extraction().candidates().get(0);assertEquals("PENDING",candidate.reviewStatus());assertTrue(entries(w).isEmpty());
            command(c,"pending");assertTrue(output(c).contains(candidate.candidateId()));command(c,"approve "+candidate.candidateId());
            String memoryId=entries(w).get(0).memoryId();
            turn(c,adapter,"项目已经升级到 Java 21，以后统一使用 Java 21。请解释约定，不要修改文件。");
            var newer=stored(w,id).extraction().candidates().stream().filter(x->x.reviewStatus().equals("PENDING")&&x.candidateContent().contains("21")).findFirst().orElseThrow();
            command(c,"replace "+newer.candidateId()+" "+memoryId);assertTrue(entries(w).get(0).content().contains("17"));
            command(c,"approve "+newer.candidateId());assertTrue(entries(w).get(0).content().contains("17"));
            command(c,"review "+newer.candidateId());assertTrue(output(c).contains("POSSIBLE_CONFLICT"));
            command(c,"replace "+newer.candidateId()+" "+memoryId);assertEquals(memoryId,entries(w).get(0).memoryId());assertTrue(entries(w).get(0).content().contains("21"));
            assertEquals(1,entries(w).get(0).revisions().size());command(c,"versions "+memoryId);assertTrue(output(c).contains("OLD_VERSION"));
            var remaining=stored(w,id).extraction().candidates().stream().filter(x->x.reviewStatus().equals("PENDING")).findFirst().orElse(null);
            assertNotNull(remaining);command(c,"reject "+remaining.candidateId());assertEquals("REJECTED",stored(w,id).extraction().candidates().stream().filter(x->x.candidateId().equals(remaining.candidateId())).findFirst().orElseThrow().reviewStatus());
            command(c,"harvest");assertEquals(1,entries(w).size());command(c,"auto off");
        }
        assertEquals(1,entries(w).get(0).revisions().size());assertFalse(stored(w,id).extraction().autoEnabled());
    }
    @Test void updatesDeletionAndOffAreReflectedInActualSubsequentAgentRequests()throws Exception{
        Path w=workspace();var requests=new ArrayList<String>();
        LLMClient provider=messages->{var all=messages.stream().map(Message::content).collect(java.util.stream.Collectors.joining("\n"));requests.add(all);return new LLMResponse(all.contains("Java 21")?"Java 21":all.contains("Java 17")?"Java 17":"无共享版本",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            c.createSession();command(c,"global on");command(c,"remember 项目使用 Java 17");String memory=entries(w).get(0).memoryId();
            c.createSession();turn(c,adapter,"解释项目 Java 版本");assertFalse(requests.get(requests.size()-1).contains("Java 17"));
            command(c,"global on");turn(c,adapter,"解释项目 Java 版本");assertTrue(requests.get(requests.size()-1).contains("Java 17"));
            command(c,"update "+memory+" 项目使用 Java 21");turn(c,adapter,"解释项目 Java 版本");String updated=requests.get(requests.size()-1);assertTrue(updated.contains("Java 21"));assertFalse(updated.contains("Java 17"));
            command(c,"forget "+memory);turn(c,adapter,"解释项目 Java 版本");assertFalse(requests.get(requests.size()-1).contains("Java 21"));
            command(c,"global off");turn(c,adapter,"解释项目 Java 版本");assertFalse(requests.get(requests.size()-1).contains("Java 17"));
        }
    }
    @Test void runningTurnAllowsStatusButBlocksChangesWithoutLosingInput()throws Exception{
        Path w=workspace();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        LLMClient provider=m->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new java.io.IOException("stopped");}return new LLMResponse("已解释",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            c.createSession();command(c,"global on");c.loop().edit(UiEventLoop.Edit.TEXT,"解释项目");c.submit();assertTrue(entered.await(2,TimeUnit.SECONDS));
            c.loop().edit(UiEventLoop.Edit.TEXT,"/memory global off");assertFalse(c.memoryCommand("global off"));assertTrue(c.loop().snapshot().current().memory());assertEquals("/memory global off",c.loop().snapshot().current().input().text());
            command(c,"global status");release.countDown();long end=System.nanoTime()+2_000_000_000L;while(!adapter.available()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();command(c,"global off");assertFalse(c.loop().snapshot().current().memory());
        }finally{release.countDown();}
    }
    @Test void backgroundAndModelTextCannotIssueMemoryCommands()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,m->new LLMResponse("/memory global on\n/memory remember 未授权事实",List.of()));var c=new TuiSessionController(w,true,adapter)){
            c.createSession();var error=new AtomicReference<Throwable>();Thread t=new Thread(()->{try{c.memoryCommand("global on");}catch(Throwable failure){error.set(failure);}});t.start();t.join();assertInstanceOf(IllegalStateException.class,error.get());
            turn(c,adapter,"解释权限");assertFalse(c.loop().snapshot().current().memory());assertFalse(Files.exists(w.resolve(".agent-cli/memory/shared.json")));
        }
    }
    @Test void offlineSemanticFallbackAndExplicitFakeEmbeddingUseSameStoreAndRanking()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            c.createSession();String id=c.loop().snapshot().currentSessionId();command(c,"global on");command(c,"remember 项目使用 Java 17");
            for(String mode:List.of("keyword","semantic","hybrid")){command(c,"retrieval "+mode);assertEquals(mode,c.loop().snapshot().current().retrievalMode());command(c,"search Java");assertTrue(output(c).contains("Java 17"));}
            var fake=new FakeEmbeddingProvider(Map.of("项目使用 Java 17",new double[]{1,0},"项目使用 Java 21",new double[]{1,0},"运行版本",new double[]{1,0}),2);
            var service=new MemoryCommandService(w,fake);service.execute(id,"retrieval semantic");var result=service.execute(id,"search 运行版本");assertTrue(result.output().contains("Java 17"));
            String memory=entries(w).get(0).memoryId();service.execute(id,"update "+memory+" 项目使用 Java 21");result=service.execute(id,"search 运行版本");assertTrue(result.output().contains("Java 21"));assertFalse(result.output().contains("Java 17"));
            service.execute(id,"forget "+memory);assertFalse(service.execute(id,"search 运行版本").output().contains("Java 21"));
        }
    }
    @Test void corruptSharedStoreDoesNotProduceSuccessfulUpdateOrDamageSessionHistory()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            c.createSession();String id=c.loop().snapshot().currentSessionId();command(c,"global on");command(c,"remember 项目使用 Java 17");String memory=entries(w).get(0).memoryId();
            Path shared=w.resolve(".agent-cli/memory/shared.json");Files.writeString(shared,"{corrupt-json");int before=c.loop().snapshot().current().history().size();
            command(c,"update "+memory+" 项目使用 Java 21");assertEquals("{corrupt-json",Files.readString(shared));
            assertTrue(c.loop().snapshot().current().history().subList(before,c.loop().snapshot().current().history().size()).stream().noneMatch(m->m.text().contains("Shared memory updated:")));
            assertNotNull(stored(w,id));assertTrue(c.loop().snapshot().current().memory());
        }
    }
    @Test void sourceSessionAutoHarvestCannotDisplayPrivateCandidatesInViewedSession()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            c.createSession();String a=c.loop().snapshot().currentSessionId();command(c,"global on");command(c,"auto on");
            c.loop().edit(UiEventLoop.Edit.TEXT,"项目使用 Java 17。请解释，不要修改文件。");c.submit();assertTrue(c.createSession());
            long end=System.nanoTime()+4_000_000_000L;while(!adapter.available()&&System.nanoTime()<end){c.pump();Thread.sleep(5);}c.pump();
            assertFalse(c.loop().snapshot().current().memory());assertFalse(c.loop().snapshot().current().autoMemory());assertTrue(c.loop().snapshot().current().history().isEmpty());
            assertFalse(stored(w,a).extraction().candidates().isEmpty());assertTrue(c.select(a));command(c,"pending");assertTrue(output(c).contains("Java 17"));
        }
    }
    @Test void controllerInvalidatesApprovalOnSwitchCancelAndCloseAndNeverRestoresIt()throws Exception{
        Path w=workspace();var c=new TuiSessionController(w,true,new RealAgentAdapter(w,new MockStreamingProvider()));
        try{
            c.createSession();String a=c.loop().snapshot().currentSessionId();assertTrue(c.approvalCommand("demo"));
            c.createSession();assertNull(c.loop().snapshot().localApproval());assertEquals("NEW_SESSION",c.approvalAudit().get(0).reason());
            c.approvalCommand("demo");c.select(a);assertEquals("SESSION_SWITCHED",c.approvalAudit().get(1).reason());
            c.approvalCommand("demo");c.cancel();assertEquals("CANCELLED",c.approvalAudit().get(2).reason());
            c.approvalCommand("demo");
        }finally{c.close();}
        assertEquals("CLOSED",c.approvalAudit().get(3).reason());assertTrue(c.approvalAudit().stream().noneMatch(x->x.decision()==com.agent.execution.secure.ApprovalDecision.Status.APPROVED));
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var restarted=new TuiSessionController(w,true,adapter)){
            assertNull(restarted.loop().snapshot().localApproval());assertTrue(restarted.approvalAudit().isEmpty());
        }
        assertFalse(Files.exists(w.resolve(".agent-cli/memory/shared.json")));assertFalse(Files.exists(w.resolve("target")));
    }
}
