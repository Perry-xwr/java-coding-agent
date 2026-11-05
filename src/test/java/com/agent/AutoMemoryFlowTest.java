package com.agent;
import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import com.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutoMemoryFlowTest {
 @TempDir Path workspace;
 List<List<Message>> requests=new ArrayList<>();
 InteractiveCli cli(){return new InteractiveCli(CliAgentFactory.createProfiles((LLMClient)m->{requests.add(List.copyOf(m));return new LLMResponse("offline",List.of());},workspace,AgentEventListener.NO_OP),new ConsoleUi(new PrintStream(new ByteArrayOutputStream())),workspace);}
 void run(InteractiveCli c,String s)throws Exception{c.run(new BufferedReader(new StringReader(s)));}
 @Test void reviewAndCrossSessionRequestsAndRestart()throws Exception{
  var c=cli();run(c,"/memory global on\n/memory auto on\n/chat\n这个项目使用 Java 17 和 Maven，后续新增功能尽量补充 JUnit 测试。\n/exit\n");
  var manager=new CliConversationManager(workspace);var a=manager.list().get(0);
  assertTrue(a.snapshot().extraction().autoEnabled());assertEquals(2,a.snapshot().extraction().candidates().size());
  assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
  var resumed=cli();run(resumed,"/switch "+a.id()+"\n/memory harvest\n/exit\n");
  assertEquals(2,manager.load(a.id()).snapshot().extraction().candidates().size());
  for(var candidate:a.snapshot().extraction().candidates())run(resumed,"/memory approve "+candidate.candidateId()+"\n/exit\n");
  assertEquals(2,new SharedMemoryStore(workspace).read().size());
  run(resumed,"/new\n请问项目使用什么 Java 版本？\n/exit\n");
  assertFalse(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")));
  run(resumed,"/memory global on\n请问项目使用什么 Java 版本？\n/exit\n");
  assertTrue(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains("这个项目使用 Java 17 和 Maven")));
 }
 @Test void defaultsOffAndRejectDoesNotRegenerate()throws Exception{
  var c=cli();run(c,"这个项目使用 Java 17\n/exit\n");var manager=new CliConversationManager(workspace);
  var a=manager.list().get(0);assertFalse(a.snapshot().extraction().autoEnabled());assertTrue(a.snapshot().extraction().candidates().isEmpty());
  run(c,"/memory global on\n/memory harvest\n/exit\n");a=manager.load(a.id());var candidate=a.snapshot().extraction().candidates().get(0);
  run(c,"/memory reject "+candidate.candidateId()+"\n/memory harvest\n/exit\n");
  assertEquals("REJECTED",manager.load(a.id()).snapshot().extraction().candidates().get(0).reviewStatus());assertTrue(new SharedMemoryStore(workspace).read().isEmpty());
 }
 @Test void llmProtocolAndFabricationAndInvalidJson()throws Exception{
  var sources=List.of(new MemoryExtractor.Source("CODE:1","项目使用 Java 17"));
  String valid="[{\"proposedContent\":\"项目使用 Java 17\",\"category\":\"EXPLICIT_FACT\",\"sourceMessageRefs\":[\"CODE:1\"],\"evidenceQuote\":\"项目使用 Java 17\",\"confidence\":0.8,\"proposedAction\":\"PROPOSE\"}]";
  assertEquals(1,new LlmMemoryExtractor(m->new LLMResponse(valid,List.of())).extract(sources).size());
  assertThrows(IOException.class,()->new LlmMemoryExtractor(m->new LLMResponse(valid.replace("CODE:1","CODE:99"),List.of())).extract(sources));
  assertThrows(IOException.class,()->new LlmMemoryExtractor(m->new LLMResponse("broken",List.of())).extract(sources));
  assertThrows(IOException.class,()->new LlmMemoryExtractor(m->{throw new IOException("controlled");}).extract(sources));
 }
 @Test void paidFailureIsReservedOnceAndUsageSeparatedWithoutInterruptingChat()throws Exception {
  String old=System.getProperty("agent.memory.extraction.maxRequests");System.setProperty("agent.memory.extraction.maxRequests","1");
  try {
   int[] extractCalls={0},chatCalls={0};
   LLMClient fake=m->{if(m.get(0).content().startsWith("Extract explicit durable")||m.get(0).content().startsWith("Classify each user-authored sentence")){extractCalls[0]++;return new LLMResponse("invalid json",List.of(),new ProviderTokenUsage(10,2,12,null,null,true));}chatCalls[0]++;return new LLMResponse("answer",List.of());};
   var c=new InteractiveCli(CliAgentFactory.createProfiles(fake,workspace,AgentEventListener.NO_OP),new ConsoleUi(new PrintStream(new ByteArrayOutputStream())),workspace);
   run(c,"/memory global on\n/memory auto on\n/memory extractor llm\n/chat\n项目使用 Java 17\n/memory harvest\n项目使用 Maven\n/exit\n");
   var state=new CliConversationManager(workspace).list().get(0).snapshot().extraction();
   assertEquals(1,extractCalls[0]);assertEquals(2,chatCalls[0]);assertEquals(1,state.attempts());assertEquals(12,state.usage().get(0).totalTokens());assertTrue(state.candidates().isEmpty());
  } finally {if(old==null)System.clearProperty("agent.memory.extraction.maxRequests");else System.setProperty("agent.memory.extraction.maxRequests",old);}
 }
}
