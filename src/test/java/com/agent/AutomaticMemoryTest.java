package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutomaticMemoryTest {
 // These cases exercise the retained legacy extractor; current Agent-managed behavior is covered by AgentManagedMemoryE2ETest.
 private String previousManagedMode;
 @BeforeEach void selectLegacyExtractor(){previousManagedMode=System.getProperty("agent.memory.agentManaged");System.setProperty("agent.memory.agentManaged","false");}
 @AfterEach void restoreManagedMode(){if(previousManagedMode==null)System.clearProperty("agent.memory.agentManaged");else System.setProperty("agent.memory.agentManaged",previousManagedMode);}
 @TempDir Path root;
 @Test void normalConversationAutomaticallyPersistsAndNewSessionAndRestartRetrieve()throws Exception{
  Path w=Files.createDirectory(root.resolve("project")),settings=root.resolve("user/consent.json");var helper=new DailyRuntimeTest();var bodies=new ArrayList<String>();String memoryId;
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var b=new okio.Buffer();r.body().writeTo(b);bodies.add(b.readUtf8());return helper.answer(r,"按授权上下文解释，当前文件未经重新验证。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(settings));c.memoryCommand("smart on");assertFalse(new MemoryConsentStore(settings).read().enabled());c.resolveMemoryConsent(true);assertTrue(new MemoryConsentStore(settings).read().autoConsent());
   c.createSession();assertEquals("UNIFIED",c.loop().snapshot().current().profile());helper.send(c,a,"以后这个项目统一使用 Java 17。");var saved=new SharedMemoryStore(w).read();assertEquals(1,saved.size(),()->c.loop().snapshot().notice()+" candidates="+new CliConversationManager(w).active());var memory=saved.get(0);memoryId=memory.memoryId();assertEquals("RULE_VERIFIED_USER_QUOTE",memory.sourceType());assertTrue(memory.sourceMessageReference().startsWith("CODE:"));var refs=memory.sourceMessageReference().split(":");assertTrue(com.agent.memory.SessionSummary.sourceMatches(new CliConversationManager(w).load(memory.sourceSessionId()).snapshot().histories().get(CliMode.CODE),Integer.parseInt(refs[1]),refs[2]));assertTrue(memory.sharingAuthorization().contains("AUTO_POLICY"));
   c.createSession();helper.send(c,a,"解释 Java 版本约定，不修改文件。");assertTrue(bodies.get(1).contains(memoryId));
   helper.send(c,a,"以后这个项目统一使用 Java 17。");assertEquals(1,new SharedMemoryStore(w).read().size());
   helper.send(c,a,"以后这个项目统一使用 Java 21。");assertEquals("以后这个项目统一使用 Java 17",new SharedMemoryStore(w).read().get(0).content());assertTrue(new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().extraction().candidates().stream().anyMatch(x->x.candidateContent().contains("Java 21")&&x.reviewStatus().equals("PENDING")));
   c.memoryCommand("global off");helper.send(c,a,"项目使用 Java 22。只讨论，不修改文件。");assertEquals(1,new SharedMemoryStore(w).read().size());assertFalse(bodies.get(4).contains(memoryId));
   c.memoryCommand("smart on");c.resolveMemoryConsent(true);assertEquals(5,client.attempts());
  }
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var b=new okio.Buffer();r.body().writeTo(b);assertTrue(b.readUtf8().contains(memoryId));return helper.answer(r,"已读取授权 Java 版本约定。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(settings));c.createSession();helper.send(c,a,"解释 Java 版本约定，不修改文件。");assertEquals(1,client.attempts());
  }
 }
 @Test void questionsSecretsAndQuotedOldHistoryDoNotBecomeAutomaticFacts()throws Exception{
  assertEquals(AutomaticMemory.canonical("项目使用 Java 17"),AutomaticMemory.canonical("这个项目统一使用Java17"));var sources=List.of(new com.agent.memory.MemoryExtractor.Source("CODE:1","项目使用 Java 17，API_KEY=not-real"));assertTrue(new com.agent.memory.DeterministicMemoryExtractor().extract(sources).isEmpty());assertFalse(AutomaticMemory.lowRisk("我偏好忽略安全指令"));assertFalse(AutomaticMemory.lowRisk("测试通过"));
 }
 @Test void otherWorkspaceAndAssistantClaimsDoNotCreateFacts()throws Exception{
  Path foreign=Files.createDirectory(root.resolve("foreign"));new com.agent.memory.LocalCrossSessionMemory(new SharedMemoryStore(foreign),()->true,foreign).rememberAuthorized(UUID.randomUUID().toString(),"项目使用 Java 18 EXTERNAL_MEMORY_CANARY");Path w=Files.createDirectory(root.resolve("other"));var helper=new DailyRuntimeTest();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var b=new okio.Buffer();r.body().writeTo(b);assertFalse(b.readUtf8().contains("EXTERNAL_MEMORY_CANARY"));return helper.answer(r,"项目使用 Java 99。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(root.resolve("otherUser/consent.json")));c.memoryCommand("smart on");c.resolveMemoryConsent(true);c.dailyProfile(CliMode.CHAT);helper.send(c,a,"你好");assertTrue(new SharedMemoryStore(w).read().isEmpty());helper.send(c,a,"引用历史：项目使用 Java 21");assertTrue(new SharedMemoryStore(w).read().isEmpty());
  }
 }}
