package com.agent;

import com.agent.llm.*;
import com.agent.memory.*;
import com.agent.tool.execution.DefaultProcessRunner;
import com.agent.tui.runtime.UiEventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Offline integration through the Daily controller, Agent Core, ToolRegistry and persistent store. */
class AgentManagedMemoryE2ETest {
    @TempDir Path root;
    private String previousManagedProperty;
    private static final String PREFERENCE = "我希望以后在这个项目里编写 Python 代码时，函数尽量添加类型注解。这是我长期坚持的开发习惯。";

    @BeforeEach void enableAgentManagedModeForThisSuite(){previousManagedProperty=System.getProperty("agent.memory.agentManaged");System.setProperty("agent.memory.agentManaged","true");}
    @AfterEach void restoreAgentManagedMode(){if(previousManagedProperty==null)System.clearProperty("agent.memory.agentManaged");else System.setProperty("agent.memory.agentManaged",previousManagedProperty);}

    @Test void dailyAgentProposesExactUserPreferenceAndNewSessionReceivesEffectiveMemoryAfterRestart() throws Exception {
        Path workspace=TuiSessionWorkspace.create(root.resolve("workspace"));
        var consent=new MemoryConsentStore(workspace.resolve("isolated-consent.json"));
        consent.update(MemoryConsentStore.Settings.off(),true,true,false);
        var provider=new ProposalThenRecallProvider(PREFERENCE);
        String firstSession;
        try(var adapter=adapter(workspace,provider,consent);var controller=new TuiSessionController(workspace,true,adapter)){
            controller.enableMemoryConsent(consent);
            assertTrue(controller.createSession());
            String input=PREFERENCE+"只讨论，不修改文件。";
            type(controller,input);assertNotNull(controller.submit());finish(controller,adapter);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status(),controller.loop().snapshot().current().turn().error());
            firstSession=controller.loop().snapshot().currentSessionId();
            var saved=new SharedMemoryStore(workspace).read();
            assertEquals(1,saved.size());assertEquals(PREFERENCE,saved.get(0).content());
            assertEquals("AGENT_MANAGED_USER_QUOTE",saved.get(0).sourceType());
            assertEquals(1,provider.proposalCalls.get());
            assertTrue(provider.sawMemoryToolSchema);
            assertEquals(1,manager(workspace).load(firstSession).snapshot().extraction().candidates().stream()
                    .filter(candidate->candidate.reviewStatus().equals("APPROVED")).count());
        }
        // New adapter/controller process, same isolated workspace and consent file.
        try(var adapter=adapter(workspace,provider,consent);var controller=new TuiSessionController(workspace,true,adapter)){
            controller.enableMemoryConsent(consent);
            assertTrue(controller.createSession());
            type(controller,"请解释 Python 类型注解有什么作用。");assertNotNull(controller.submit());finish(controller,adapter);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status());
            assertTrue(provider.recallRequestContainsMemory,"effective Memory was not injected into the new session request");
            assertFalse(provider.recallCalledHistorySearch,"the recall answer must not come from UX-4 history search");
            assertEquals(1,new SharedMemoryStore(workspace).read().size());
        }
    }

    @Test void normalQuestionDoesNotWriteMemoryAndUnauthorizedSessionDoesNotExposeProposalTool() throws Exception {
        Path workspace=TuiSessionWorkspace.create(root.resolve("no-memory"));
        var consent=new MemoryConsentStore(workspace.resolve("isolated-consent.json"));
        var provider=new PlainProvider("缓存是一种临时的近端数据存储。");
        try(var adapter=adapter(workspace,provider,consent);var controller=new TuiSessionController(workspace,true,adapter)){
            controller.enableMemoryConsent(consent);assertTrue(controller.createSession());
            type(controller,"什么是缓存？");controller.submit();finish(controller,adapter);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status());
            assertFalse(provider.sawMemoryToolSchema);
            assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
        }
        Path authorizedWorkspace=TuiSessionWorkspace.create(root.resolve("authorized-ordinary"));
        var enabledConsent=new MemoryConsentStore(authorizedWorkspace.resolve("isolated-consent.json"));
        enabledConsent.update(MemoryConsentStore.Settings.off(),true,true,false);
        var ordinary=new PlainProvider("缓存是一种临时的近端数据存储。");
        try(var adapter=adapter(authorizedWorkspace,ordinary,enabledConsent);var controller=new TuiSessionController(authorizedWorkspace,true,adapter)){
            controller.enableMemoryConsent(enabledConsent);assertTrue(controller.createSession());
            type(controller,"什么是缓存？");controller.submit();finish(controller,adapter);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status());
            assertTrue(ordinary.sawMemoryToolSchema,"authorized Daily tasks expose the opt-in proposal capability");
            assertTrue(new SharedMemoryStore(authorizedWorkspace).read().isEmpty(),"ordinary Q&A must not write Memory by itself");
            assertEquals("AGENT_MANAGED_NO_MODEL_PROPOSAL",new MemoryRunDiagnostics(authorizedWorkspace).latest().reason());
        }
    }

    @Test void rejectedProposalDoesNotTurnAnOtherwiseNormalChatIntoAnAgentFailure() throws Exception {
        Path workspace=TuiSessionWorkspace.create(root.resolve("rejected-proposal"));
        var consent=new MemoryConsentStore(workspace.resolve("isolated-consent.json"));
        consent.update(MemoryConsentStore.Settings.off(),true,true,false);
        var provider=new RejectedProposalProvider();
        try(var adapter=adapter(workspace,provider,consent);var controller=new TuiSessionController(workspace,true,adapter)){
            controller.enableMemoryConsent(consent);assertTrue(controller.createSession());
            type(controller,"我希望以后在这个项目里编写 Python 代码时，函数尽量添加类型注解。这是我长期坚持的开发习惯。只讨论，不修改文件。");
            controller.submit();finish(controller,adapter);
            assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status());
            assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
            assertEquals("AGENT_MANAGED_JAVA_REJECTED_PROPOSAL",new MemoryRunDiagnostics(workspace).latest().reason());
            assertFalse(provider.sawWorkspaceChangeGuard,"a rejected Memory proposal must not turn this preference chat into a workspace-change task");
        }
    }

    @Test void temporaryExplicitNoMemoryAndFabricatedOrConflictingProposalsNeverBecomeEffective() throws Exception {
        Path workspace=TuiSessionWorkspace.create(root.resolve("guard-tests"));
        String source="长期偏好：以后讨论复杂问题时，请先给结论，再说明理由和步骤。只讨论，不修改文件。";
        var settingsStore=new MemoryConsentStore(workspace.resolve("isolated-consent.json"));
        settingsStore.update(MemoryConsentStore.Settings.off(),true,true,false);
        var proposalTool=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",source,1,
                ()->true,ignored->{});
        var temporaryResult=proposalTool.execute("{\"memoryContent\":\"今天电脑风扇声音比较大\",\"category\":\"PREFERENCE\"}");
        assertTrue(temporaryResult.success());assertEquals("REJECTED_POLICY",temporaryResult.metadata().get("memoryDecision"));
        var prohibited=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",
                source+" 整条消息都不要记忆。",1,()->true,ignored->{});
        assertTrue(prohibited.execute("{\"memoryContent\":\"长期偏好：以后讨论复杂问题时，请先给结论，再说明理由和步骤。\",\"category\":\"PREFERENCE\"}").output().startsWith("REJECTED_POLICY"));
        var fabricated=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",source,1,()->true,ignored->{});
        assertTrue(fabricated.execute("{\"memoryContent\":\"用户希望所有回答都使用法语\",\"category\":\"PREFERENCE\"}").output().startsWith("REJECTED_POLICY"));
        var incomplete=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",
                "长期沟通偏好：讨论复杂问题时。只讨论，不修改文件。",1,()->true,ignored->{});
        assertTrue(incomplete.execute("{\"memoryContent\":\"长期沟通偏好：讨论复杂问题时\",\"category\":\"PREFERENCE\"}")
                .output().startsWith("REJECTED_POLICY"),"an incomplete condition fragment must not be persisted");
        String unrelatedBetween="以后讨论复杂问题时，请先给结论。我今天买了咖啡。以后解释代码时，请给完整示例。";
        var stitched=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",unrelatedBetween,1,()->true,ignored->{});
        assertTrue(stitched.execute("{\"memoryContent\":\""+unrelatedBetween+"\",\"category\":\"PREFERENCE\"}")
                .output().startsWith("REJECTED_POLICY"),"a span crossing an unrelated sentence must not be treated as one preference");

        String repeatSource="长期偏好：以后讨论复杂问题时，请先给结论，再说明理由和步骤。";
        String exactArgs="{\"memoryContent\":\"长期偏好：以后讨论复杂问题时，请先给结论，再说明理由和步骤。\",\"category\":\"PREFERENCE\"}";
        var first=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:3",repeatSource,1,()->true,ignored->{});
        assertTrue(first.execute(exactArgs).success());
        var duplicate=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:3",repeatSource,1,()->true,ignored->{});
        assertTrue(duplicate.execute(exactArgs).output().startsWith("ALREADY_PRESENT"));
        assertEquals(1,new SharedMemoryStore(workspace).read().size());

        String conflict="长期偏好：以后讨论复杂问题时，请先给步骤，再给结论。";
        var old=new LongTermMemory(UUID.randomUUID().toString(),"长期偏好：以后讨论复杂问题时，请先给结论，再给步骤。",
                UUID.randomUUID().toString(),java.time.Instant.now().toString(),java.time.Instant.now().toString(),
                "USER_MEMORY_COMMAND","EXPLICIT_USER",workspace.toAbsolutePath().normalize().toString(),null);
        new SharedMemoryStore(workspace).append(old);
        var conflictTool=new com.agent.tool.MemoryProposeTool(workspace,UUID.randomUUID().toString(),"CODE:2",conflict,1,()->true,ignored->{});
        var conflictResult=conflictTool.execute("{\"memoryContent\":\"长期偏好：以后讨论复杂问题时，请先给步骤，再给结论。\",\"category\":\"PREFERENCE\"}");
        assertTrue(conflictResult.success(),conflictResult.errorMessage());
        assertTrue(conflictResult.output().startsWith("PENDING_REVIEW"));
        assertEquals(2,new SharedMemoryStore(workspace).read().size());
    }

    private static RealAgentAdapter adapter(Path w,LLMClient provider,MemoryConsentStore consent)throws IOException{
        return new RealAgentAdapter(w,provider,true,null,new DefaultProcessRunner(),w.resolve(".m2/repository"),p->p,true,consent);
    }
    private static CliConversationManager manager(Path w)throws IOException{return new CliConversationManager(w);}
    private static void type(TuiSessionController c,String text){c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,text);}
    private static void finish(TuiSessionController c,RealAgentAdapter adapter)throws Exception{
        long deadline=System.nanoTime()+5_000_000_000L;
        while((c.loop().snapshot().current().turn()==null||c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<deadline){c.pump();Thread.sleep(3);}
        c.pump();assertTrue(adapter.available(),"Mock Agent did not finish");
    }

    private static final class ProposalThenRecallProvider implements StreamingLlmClient{
        private final String preference;final AtomicInteger requests=new AtomicInteger(),proposalCalls=new AtomicInteger();volatile boolean sawMemoryToolSchema,recallRequestContainsMemory,recallCalledHistorySearch;
        ProposalThenRecallProvider(String preference){this.preference=preference;}
        public LLMResponse chat(List<Message> messages)throws IOException{return stream(messages,List.of(),ignored->{});}
        public LLMResponse stream(List<Message> messages,List<ToolDefinition> tools,LlmStreamListener listener){
            int call=requests.incrementAndGet();sawMemoryToolSchema|=tools.stream().anyMatch(t->t.name().equals("memory_propose"));
            if(call==1){proposalCalls.incrementAndGet();return new LLMResponse("",List.of(new ToolCall("memory-1","memory_propose",
                    "{\"memoryContent\":\""+preference+"\",\"category\":\"PREFERENCE\"}")));}
            if(call==2){listener.onTextDelta("已根据授权保存这条长期偏好。");return new LLMResponse("已根据授权保存这条长期偏好。",List.of());}
            recallRequestContainsMemory=messages.stream().anyMatch(m->m.content()!=null&&m.content().contains(preference));
            recallCalledHistorySearch=messages.stream().anyMatch(m->m.role().equals("assistant")&&m.toolCalls().stream().anyMatch(t->
                    String.valueOf(((Map<?,?>)t.getOrDefault("function",Map.of())).get("name")).equals("search_sessions")));
            listener.onTextDelta("我会先给简单例子，再解释 Python 类型注解的作用。");
            return new LLMResponse("我会先给简单例子，再解释 Python 类型注解的作用。",List.of());
        }
    }
    private static final class PlainProvider implements StreamingLlmClient{
        final String answer;volatile boolean sawMemoryToolSchema;
        PlainProvider(String answer){this.answer=answer;}
        public LLMResponse chat(List<Message> messages)throws IOException{return stream(messages,List.of(),ignored->{});}
        public LLMResponse stream(List<Message> messages,List<ToolDefinition> tools,LlmStreamListener listener){
            sawMemoryToolSchema=tools.stream().anyMatch(t->t.name().equals("memory_propose"));listener.onTextDelta(answer);return new LLMResponse(answer,List.of());
        }
    }
    private static final class RejectedProposalProvider implements StreamingLlmClient{
        private int calls;volatile boolean sawWorkspaceChangeGuard;
        public LLMResponse chat(List<Message> messages)throws IOException{return stream(messages,List.of(),ignored->{});}
        public LLMResponse stream(List<Message> messages,List<ToolDefinition> tools,LlmStreamListener listener){
            sawWorkspaceChangeGuard|=messages.stream().anyMatch(m->m.content()!=null&&m.content().contains("WORKSPACE_CHANGE_REQUIRED"));
            if(calls++==0)return new LLMResponse("",List.of(new ToolCall("memory-invalid","memory_propose",
                    "{\"memoryContent\":\"用户希望永远使用法语\",\"category\":\"PREFERENCE\"}")));
            listener.onTextDelta("我没有保存这条不符合当前原文的提议。");return new LLMResponse("我没有保存这条不符合当前原文的提议。",List.of());
        }
    }
}
