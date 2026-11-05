package com.agent;

import com.agent.llm.*;
import com.agent.memory.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retained synthetic evidence only; no real Provider, user settings or user workspace. */
class DailyMemoryAcceptanceTest {
    // This synthetic suite preserves legacy deterministic/semantic extraction assertions. The default Agent-managed path is tested separately.
    private String previousManagedMode;
    @org.junit.jupiter.api.BeforeEach void selectLegacyExtractor(){previousManagedMode=System.getProperty("agent.memory.agentManaged");System.setProperty("agent.memory.agentManaged","false");}
    @org.junit.jupiter.api.AfterEach void restoreManagedMode(){if(previousManagedMode==null)System.clearProperty("agent.memory.agentManaged");else System.setProperty("agent.memory.agentManaged",previousManagedMode);}
    final ObjectMapper json=new ObjectMapper();
    final DailyRuntimeTest helper=new DailyRuntimeTest();
    final List<JsonNode> requests=new ArrayList<>();
    Path evidence;

    DailyDeepSeekClient mock(){return DailyDeepSeekClient.offline(request->{
        var bytes=new okio.Buffer();request.body().writeTo(bytes);var body=json.readTree(bytes.readUtf8());requests.add(body);
        Files.writeString(evidence.resolve("request-"+requests.size()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        // This response has no preselected Java version: it can only echo the actual authorized block.
        var content=new ArrayList<String>();
        for(var message:body.path("messages")){
            assertFalse(message.path("role").asText().equals("tool"),"No history/file tool result may explain this answer");
            assertFalse(message.has("tool_calls"));
            if(message.path("content").asText().startsWith("CROSS_SESSION_MEMORY_DATA")){
                String block=message.path("content").asText();for(var entry:json.readTree(block.substring(block.indexOf('\n')+1)))content.add(entry.path("content").asText());
            }
        }
        return helper.answer(request,content.isEmpty()?"[MOCK] 请求中没有授权的长期记忆。":"[MOCK] 请求中实际收到的授权记忆："+String.join("；",content));
    });}
    void send(TuiSessionController controller,DailyAgentAdapter adapter,String text)throws Exception{
        int count=requests.size();helper.send(controller,adapter,text);
        assertEquals("UNIFIED",controller.loop().snapshot().current().profile());
        assertEquals("COMPLETED",controller.loop().snapshot().current().turn().status());
        assertTrue(controller.loop().snapshot().current().turn().tools().isEmpty());
        assertEquals(count+1,requests.size(),"One chat call, zero extra extraction calls");
    }
    JsonNode memoryInLastRequest()throws Exception{
        var result=json.createArrayNode();for(var m:requests.get(requests.size()-1).path("messages")){
            String text=m.path("content").asText();if(text.startsWith("CROSS_SESSION_MEMORY_DATA"))for(var entry:json.readTree(text.substring(text.indexOf('\n')+1)))result.add(entry);
        }return result;
    }
    void assertInjected(String id,String content)throws Exception{
        var block=memoryInLastRequest();assertEquals(1,block.size());assertEquals(id,block.get(0).path("memoryId").asText());assertEquals(content,block.get(0).path("content").asText());
    }
    CliConversation current(TuiSessionController c,Path w)throws Exception{return new CliConversationManager(w).load(c.loop().snapshot().currentSessionId());}

    @Test void legacyCodeLifecycleWithContextDerivedMockAnswer()throws Exception{
        evidence=Files.createTempDirectory(Path.of("target"),"daily-memory-acceptance-").toAbsolutePath();
        Path w=Files.createDirectory(evidence.resolve("workspace")),settings=evidence.resolve("isolated-user/consent.json");
        String id,sourceSession,content;
        try(var lease=DailyWorkspace.acquire(w);var client=mock();var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
            var store=new MemoryConsentStore(settings);c.enableMemoryConsent(store);assertFalse(store.read().enabled());
            c.memoryCommand("smart on");c.resolveMemoryConsent(false);assertFalse(store.read().enabled());assertEquals(0,client.attempts());
            c.memoryCommand("smart on");c.resolveMemoryConsent(true);assertTrue(store.read().enabled());assertTrue(store.read().autoConsent());
            c.createSession();assertTrue(c.loop().snapshot().current().memory());assertTrue(c.loop().snapshot().current().autoMemory());
            send(c,adapter,"以后这个项目统一使用 Java 17。");
            var saved=new SharedMemoryStore(w).read();assertEquals(1,saved.size());var memory=saved.get(0);id=memory.memoryId();content=memory.content();sourceSession=memory.sourceSessionId();
            assertEquals("RULE_VERIFIED_USER_QUOTE",memory.sourceType());assertEquals("EXPLICIT_WORKSPACE_AUTO_POLICY",memory.sharingAuthorization());assertEquals(w.toString(),memory.workspaceScope());
            var ref=memory.sourceMessageReference().split(":");assertTrue(SessionSummary.sourceMatches(current(c,w).snapshot().histories().get(CliMode.CODE),Integer.parseInt(ref[1]),ref[2]));
            assertTrue(Files.readString(w.resolve(".agent-cli/memory/shared.json")).contains(id));
            assertEquals("APPROVED",current(c,w).snapshot().extraction().candidates().get(0).reviewStatus());
            assertFalse(current(c,w).snapshot().extraction().autoEnabled(),"Daily consent is separate from the legacy candidate switch");
            assertTrue(c.loop().snapshot().current().autoMemory());
            c.createSession();assertNotEquals(sourceSession,c.loop().snapshot().currentSessionId());
            send(c,adapter,"解释项目 Java 版本约定。");assertInjected(id,content);
            assertEquals("[MOCK] 请求中实际收到的授权记忆："+content,ConversationCopy.select(current(c,w).snapshot().histories().get(CliMode.CODE),false));
            // A brand-new session has no old dialogue, file evidence, or old private summary as fallback.
            for(var message:requests.get(1).path("messages"))if(!message.path("content").asText().startsWith("CROSS_SESSION_MEMORY_DATA"))assertFalse(message.path("content").asText().contains("Java 17"));
            assertEquals(1,current(c,w).snapshot().crossSessionMemory().exposures().size());
            send(c,adapter,"这个项目统一使用Java17。");assertEquals(1,new SharedMemoryStore(w).read().size());
            send(c,adapter,"以后这个项目统一使用 Java 21。");assertEquals(content,new SharedMemoryStore(w).read().get(0).content());
            assertTrue(current(c,w).snapshot().extraction().candidates().stream().anyMatch(candidate->candidate.candidateContent().contains("Java 21")&&candidate.reviewStatus().equals("PENDING")));
            c.createSession();send(c,adapter,"解释项目 Java 版本约定。");assertInjected(id,content);assertFalse(memoryInLastRequest().toString().contains("Java 21"));
            assertEquals(requests.size(),client.attempts());
        }
        try(var lease=DailyWorkspace.acquire(w);var client=mock();var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
            c.enableMemoryConsent(new MemoryConsentStore(settings));c.createSession();assertTrue(c.loop().snapshot().current().autoMemory());
            send(c,adapter,"解释项目 Java 版本约定。");assertInjected(id,content);
            c.memoryCommand("auto off");assertFalse(new MemoryConsentStore(settings).read().autoConsent());assertTrue(new MemoryConsentStore(settings).read().enabled());
            send(c,adapter,"项目使用 Maven。");assertEquals(1,new SharedMemoryStore(w).read().size());assertFalse(current(c,w).snapshot().extraction().candidates().stream().anyMatch(x->x.candidateContent().contains("Maven")));
            c.createSession();send(c,adapter,"解释项目 Java 版本约定。");assertInjected(id,content);
            c.memoryCommand("global off");c.createSession();assertFalse(c.loop().snapshot().current().memory());
            send(c,adapter,"解释项目 Java 版本约定。");assertEquals(0,memoryInLastRequest().size());assertFalse(requests.get(requests.size()-1).toString().contains(id));
            send(c,adapter,"项目统一使用 Java 22。");assertEquals(1,new SharedMemoryStore(w).read().size());assertTrue(current(c,w).snapshot().extraction().candidates().isEmpty());
        }
        try(var lease=DailyWorkspace.acquire(w);var client=mock();var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
            c.enableMemoryConsent(new MemoryConsentStore(settings));c.createSession();assertFalse(c.loop().snapshot().current().memory());
            send(c,adapter,"解释项目 Java 版本约定。");assertEquals(0,memoryInLastRequest().size());assertFalse(requests.get(requests.size()-1).toString().contains(id));
        }
        try(var files=Files.list(w)){assertEquals(List.of(".agent-cli"),files.map(path->path.getFileName().toString()).sorted().toList(),"No project convention file or tool fixture exists");}
        Files.writeString(evidence.resolve("result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "status","PASS","mode","DEFAULT_CODE_UNIFIED","transport","MOCK_HTTP_ONLY","realApiRequests",0,
                "modelRequests",requests.size(),"extraExtractionRequests",0,"executedTools",0,"effectiveMemoryId",id,
                "assertions",List.of("effective shared.json","source verified","new-session injection","response derived from injected block","deduplication","conflict pending","restart","auto off","global off","no history/file tools"))));
    }

    @Test void compoundStackCanBeVerifiedButFreeFormConventionStillRequiresReview(){
        String source="以后这个项目统一使用 Java 17 和 Maven，还有一个长期约定：修改代码前必须先阅读现有实现。";
        var proposals=new DeterministicMemoryExtractor().extract(List.of(new MemoryExtractor.Source("CODE:1",source)));assertEquals(2,proposals.size());
        for(var proposal:proposals)assertDoesNotThrow(()->MemoryExtractor.validate(proposal,List.of(new MemoryExtractor.Source("CODE:1",source))));
        assertTrue(AutomaticMemory.lowRisk(proposals.get(0).proposedContent()));assertFalse(AutomaticMemory.lowRisk(proposals.get(1).proposedContent()));
    }
}
