package com.agent;

import com.agent.llm.*;
import com.agent.tui.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class RealAgentAdapterTest {
    @Test void actualLauncherProviderHandlesHelloAndNumericFollowups()throws Exception{
        Path w=workspace();String id;
        try(var adapter=RealAgentAdapter.forFileEditing(w,new MockApprovalProvider());var c=new TuiSessionController(w,true,adapter)){
            for(String input:List.of("你好","1","2")){
                text(c,input);assertNotNull(c.submit());finish(c,adapter);
                var turn=c.loop().snapshot().current().turn();
                assertEquals("COMPLETED",turn.status(),input+": "+turn.error());
                assertEquals(1,turn.streamingText().split("中文增量回复",-1).length-1);
                System.out.println("TUI5_THREE_TURN input="+input+" status="+turn.status()+" sequence="+turn.sequence()+" tools="+turn.tools().size());
            }
            id=c.loop().snapshot().currentSessionId();
            assertEquals(3,history(w,id).stream().filter(m->m.role().equals("user")).count());
            assertTrue(c.prepareExit());
            assertTrue(c.createSession());text(c,"3");c.submit();finish(c,adapter);
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
            assertTrue(c.select(id));text(c,"4");c.submit();finish(c,adapter);
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
        }
        try(var adapter=RealAgentAdapter.forFileEditing(w,new MockApprovalProvider());var c=new TuiSessionController(w,true,adapter)){
            assertEquals(id,c.loop().snapshot().currentSessionId());
            assertEquals(4,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")).count());
        }
    }
    @Test void invalidMockProtocolKeepsGuardFailureAndNextTurnCanRecover()throws Exception{
        Path w=workspace();var broken=new AtomicBoolean(true);var good=new MockApprovalProvider();
        StreamingLlmClient provider=new StreamingLlmClient(){
            public LLMResponse chat(List<Message> m)throws IOException{return stream(m,List.of(),d->{});}
            public LLMResponse stream(List<Message> m,List<ToolDefinition> t,LlmStreamListener l)throws IOException{
                if(!broken.get())return good.stream(m,t,l);
                l.onTextDelta("[BROKEN] attempted answer");return new LLMResponse("[BROKEN] attempted answer",List.of());
            }
        };
        String id;
        try(var adapter=RealAgentAdapter.forFileEditing(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"1");c.submit();finish(c,adapter);id=c.loop().snapshot().currentSessionId();
            assertEquals("FAILED",c.loop().snapshot().current().turn().status());
            assertEquals("FINAL_ANSWER",c.loop().snapshot().current().turn().error());
            assertTrue(c.loop().snapshot().notice().contains("MUTATION_FAILURE_FINAL"));
            assertTrue(c.loop().snapshot().current().history().stream().anyMatch(m->m.state().equals("FAILED")));
            assertTrue(history(w,id).stream().anyMatch(m->m.role().equals("system")&&m.content().startsWith("PREMATURE_FINAL_GUARD:")));
            broken.set(false);text(c,"2");c.submit();finish(c,adapter);
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertFalse(c.loop().snapshot().current().memory());
        }
        try(var adapter=RealAgentAdapter.forFileEditing(w,new MockApprovalProvider());var c=new TuiSessionController(w,true,adapter)){
            assertEquals(id,c.loop().snapshot().currentSessionId());
            assertEquals(2,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")&&m.state().equals("FAILED")).count());
            assertEquals(1,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")&&m.state().isBlank()).count());
        }
    }
    @TempDir Path root;
    Path workspace()throws Exception{Path w=TuiSessionWorkspace.create(root.resolve(UUID.randomUUID().toString()));Files.writeString(w.resolve("demo.txt"),"真实只读工具的中文内容");return w;}
    void text(TuiSessionController c,String text){c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,text);}
    void until(TuiSessionController c,BooleanSupplier test)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;
        while(!test.getAsBoolean()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertTrue(test.getAsBoolean());
    }
    void finish(TuiSessionController c,RealAgentAdapter adapter)throws Exception{until(c,()->!c.loop().snapshot().current().turn().running()&&adapter.available());}
    List<Message> history(Path w,String id)throws Exception{return new CliConversationManager(w).load(id).snapshot().histories().get(CliMode.CODE);}

    @Test void realCoreStreamsReadsAndRestoresPairedHistoryWithoutDuplicates()throws Exception{
        Path w=workspace();String id;
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            text(c,"请读取 demo.txt 并解释，不要修改文件。");assertNotNull(c.submit());id=c.loop().snapshot().currentSessionId();
            until(c,()->!c.loop().snapshot().current().turn().streamingText().isEmpty());finish(c,adapter);
            var turn=c.loop().snapshot().current().turn();assertEquals("COMPLETED",turn.status());
            assertEquals("read_file",turn.tools().get(0).name());assertEquals("OK",turn.tools().get(0).status());
            var history=history(w,id);assertEquals(1,history.stream().filter(m->m.role().equals("user")).count());
            assertEquals(1,history.stream().filter(m->m.role().equals("tool")).count());
            assertEquals(1,history.stream().filter(m->m.role().equals("assistant")&&!m.content().isEmpty()).count());
            assertEquals(1,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")&&m.text().startsWith("[MOCK]")).count());
            assertFalse(c.loop().snapshot().current().memory());
            for(int i=0;i<2;i++){text(c,"纯聊天解释继续"+i);c.submit();finish(c,adapter);}
            assertEquals(3,history(w,id).stream().filter(m->m.role().equals("user")).count());assertTrue(c.prepareExit());
        }
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            assertEquals(id,c.loop().snapshot().currentSessionId());assertFalse(c.loop().snapshot().current().memory());
            assertEquals(3,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("Agent")&&m.text().startsWith("[MOCK]")).count());
        }
    }
    @Test void unsafeToolCallsCannotWriteOrReachMavenAndRecoveredFailuresKeepCoreOutcome()throws Exception{
        Path w=workspace();var calls=new AtomicInteger();
        LLMClient provider=messages->calls.incrementAndGet()==1?new LLMResponse("",List.of(
                new ToolCall("deny-write","create_file","{\"path\":\"forbidden.txt\",\"content\":\"bad\"}"),
                new ToolCall("deny-test","run_maven_test","{}"),new ToolCall("missing","read_file","{\"path\":\"missing.txt\"}")))
                :new LLMResponse("工具不可用，未执行写入或测试。",List.of());
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"检查工具情况并说明，不要修改文件");c.submit();finish(c,adapter);
            assertFalse(Files.exists(w.resolve("forbidden.txt")));assertFalse(Files.exists(w.resolve("target")));
            var turn=c.loop().snapshot().current().turn();assertEquals(3,turn.tools().size());
            assertTrue(turn.tools().stream().allMatch(t->t.status().equals("FAIL")));
            assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().filter(m->m.role().equals("tool")).allMatch(m->m.content().contains("APPROVAL_UNAVAILABLE")||m.content().contains("FILE_NOT_FOUND")));
            assertEquals("COMPLETED",turn.status()); // Core can correctly explain a failed tool; UI must not override it.
        }
    }
    @Test void brokenStreamIsFailedAndPartialAssistantIsNotPersisted()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            text(c,"/break");c.submit();finish(c,adapter);assertEquals("FAILED",c.loop().snapshot().current().turn().status());
            assertTrue(c.loop().snapshot().current().turn().streamingText().contains("未完成"));
            assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().noneMatch(m->m.role().equals("assistant")&&m.content().contains("未完成")));
            text(c,"纯聊天解释恢复");c.submit();finish(c,adapter);assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
        }
    }
    @Test void nonStreamingProviderProducesOnlyOneReply()throws Exception{
        Path w=workspace();var requests=new AtomicInteger();LLMClient provider=m->{requests.incrementAndGet();return new LLMResponse("单次非流式回复",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"解释一个问题");c.submit();finish(c,adapter);assertEquals(1,requests.get());
            assertEquals("单次非流式回复",c.loop().snapshot().current().turn().streamingText());
            assertEquals(1,history(w,c.loop().snapshot().currentSessionId()).stream().filter(m->m.role().equals("assistant")).count());
        }
    }
    @Test void switchingWhileStreamingSavesOnlyOriginAndGlobalSingleTaskCannotReenter()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            text(c,"纯聊天解释会话A");c.submit();String a=c.loop().snapshot().currentSessionId();
            assertTrue(c.createSession());String b=c.loop().snapshot().currentSessionId();text(c,"B等待");assertNull(c.submit());
            until(c,adapter::available);assertTrue(c.loop().snapshot().current().history().isEmpty());
            assertEquals(1,history(w,a).stream().filter(m->m.role().equals("assistant")).count());
            assertEquals(0,history(w,b).stream().filter(m->m.role().equals("assistant")).count());
            assertFalse(c.loop().snapshot().current().memory());
        }
    }
    @Test void cancellationInterruptsMockTransportAndUncooperativeProviderRemainsBusy()throws Exception{
        Path w=workspace();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new AtomicBoolean();
        LLMClient provider=m->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException e){interrupted.set(true);}}return new LLMResponse("不得保存的迟到回复",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"纯聊天取消");c.submit();assertTrue(entered.await(2,TimeUnit.SECONDS));c.loop().cancelCurrent();
            text(c,"下一任务");assertNull(c.submit());assertFalse(adapter.available());
            release.countDown();until(c,adapter::available);assertTrue(interrupted.get());assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());
            assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().noneMatch(m->m.role().equals("assistant")));
        }finally{release.countDown();}
    }
    @Test void realCoreCompletionUsesExistingSaveFailureProtection()throws Exception{
        Path w=workspace();var fail=new AtomicBoolean(true);
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter,(m,s)->{
            if(fail.get()&&s.histories().get(CliMode.CODE).stream().anyMatch(msg->msg.role().equals("assistant")&&!msg.content().isEmpty()))throw new IOException("controlled");m.checkpoint(s);
        })){
            text(c,"纯聊天解释保存故障");c.submit();finish(c,adapter);assertEquals(1,c.unsavedCount());assertFalse(c.prepareExit());
            assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().noneMatch(m->m.role().equals("assistant")));
            fail.set(false);assertTrue(c.savePending());assertTrue(c.savePending());
            assertEquals(1,history(w,c.loop().snapshot().currentSessionId()).stream().filter(m->m.role().equals("assistant")).count());
        }
    }
    @Test void manyDeltasBackpressureWithoutLosingCompletion()throws Exception{
        Path w=workspace();StreamingLlmClient provider=new StreamingLlmClient(){
            public LLMResponse chat(List<Message> m){throw new AssertionError();}
            public LLMResponse stream(List<Message> m,List<ToolDefinition> t,LlmStreamListener listener){for(int i=0;i<2000;i++)listener.onTextDelta("中");return new LLMResponse("中".repeat(2000),List.of());}
        };
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"纯聊天解释长回复");c.submit();Thread.sleep(90);finish(c,adapter);
            assertEquals("中".repeat(2000),c.loop().snapshot().current().turn().streamingText());assertTrue(c.loop().backpressureCount()>0);
            assertEquals(1,history(w,c.loop().snapshot().currentSessionId()).stream().filter(m->m.role().equals("assistant")).count());
        }
    }
    @Test void globalOffUsesExistingMemoryContextAndWithholdsPriorSharedEchoAfterRestart()throws Exception{
        Path w=workspace();var manager=new CliConversationManager(w);
        var profiles=CliAgentFactory.createProfiles(m->{throw new IOException("unused");},w,com.agent.agent.AgentEventListener.NO_OP);
        var seed=new CliSessionSnapshot(1,java.time.Instant.now().toString(),CliMode.CODE,null,profiles.histories(),new CliWorkingContext().snapshot(),
                com.agent.memory.SessionSummary.empty(),com.agent.memory.CrossSessionState.off().withEnabled(true));
        var conversation=manager.create(seed);
        manager.crossSessionMemory(()->true).rememberAuthorized(conversation.id(),"项目统一使用 Java 17。");
        var inputs=new ArrayList<String>();
        LLMClient provider=messages->{String all=messages.stream().map(Message::content).collect(java.util.stream.Collectors.joining("\n"));inputs.add(all);
            return new LLMResponse(all.contains("Java 17")?"共享记忆说明 Java 17":"没有共享记忆",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            text(c,"解释项目 Java 版本");c.submit();finish(c,adapter);assertTrue(inputs.get(0).contains("Java 17"));
        }
        var saved=manager.load(conversation.id());assertFalse(saved.snapshot().crossSessionMemory().exposures().isEmpty());
        var old=saved.snapshot();manager.activate(saved);manager.checkpoint(new CliSessionSnapshot(old.version(),old.savedAt(),old.activeMode(),old.lastAutoRoutedMode(),old.histories(),old.workingContext(),old.summary(),old.crossSessionMemory().withEnabled(false),old.extraction()));
        try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            assertFalse(c.loop().snapshot().current().memory());text(c,"解释项目 Java 版本");c.submit();finish(c,adapter);
            assertFalse(inputs.get(inputs.size()-1).contains("Java 17"));
        }
    }
    @Test void legacyChatAndReadPermissionsDoNotGainToolsOrHostVerifiers()throws Exception{
        for(CliMode mode:List.of(CliMode.CHAT,CliMode.READ)){
            Path w=workspace();var profiles=CliAgentFactory.createProfiles(m->{throw new IOException("unused");},w,com.agent.agent.AgentEventListener.NO_OP);
            new CliConversationManager(w).create(new CliSessionSnapshot(1,java.time.Instant.now().toString(),mode,null,profiles.histories(),new CliWorkingContext().snapshot()));
            var calls=new AtomicInteger();var names=new ArrayList<String>();
            LLMClient provider=new LLMClient(){
                public LLMResponse chat(List<Message> m){throw new AssertionError();}
                public LLMResponse chat(List<Message> m,List<ToolDefinition> tools){
                    names.addAll(tools.stream().map(ToolDefinition::name).toList());
                    return calls.incrementAndGet()==1?new LLMResponse("",List.of(new ToolCall("deny","create_file","{\"path\":\"bad.java\",\"content\":\"x\"}")))
                            :new LLMResponse("没有修改文件",List.of());
                }
            };
            try(var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
                text(c,"解释工具权限");c.submit();finish(c,adapter);assertFalse(Files.exists(w.resolve("bad.java")));
                assertEquals(mode==CliMode.CHAT?"CHAT":"READ",c.loop().snapshot().current().profile());
                if(mode==CliMode.CHAT)assertTrue(names.isEmpty());else assertTrue(names.contains("read_file"));
                assertFalse(names.contains("run_maven_test"));assertFalse(names.contains("create_file"));
            }
        }
    }
    @Test void cancelBeforeQueuedCompletionCannotPersistItAsSuccessfulReply()throws Exception{
        Path w=workspace();try(var adapter=new RealAgentAdapter(w,m->new LLMResponse("迟到完成",List.of()));var c=new TuiSessionController(w,true,adapter)){
            text(c,"解释取消竞争");c.submit();long end=System.nanoTime()+2_000_000_000L;
            while(!adapter.available()&&System.nanoTime()<end)Thread.sleep(3);assertTrue(adapter.available());
            // Completion is queued but not accepted by the UI. Explicit cancellation wins.
            c.loop().cancelCurrent();c.pump();assertEquals("CANCELLED",c.loop().snapshot().current().turn().status());
            assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().noneMatch(m->m.role().equals("assistant")));
        }
    }
    @Test void exitReportsUnconfirmedProviderStopInsteadOfPretendingItStopped()throws Exception{
        Path w=workspace();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        LLMClient provider=m->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* deliberate non-cooperative mock */}}return new LLMResponse("late",List.of());};
        try(var adapter=new RealAgentAdapter(w,provider)){
            var c=new TuiSessionController(w,true,adapter);
            try{
                text(c,"解释退出状态");c.submit();assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertThrows(RealAgentAdapter.StopPendingException.class,c::close);
                assertTrue(c.loop().snapshot().closed());
                assertTrue(history(w,c.loop().snapshot().currentSessionId()).stream().noneMatch(m->m.role().equals("assistant")));
            }finally{release.countDown();c.close();}
        }
    }
}
