package com.agent;
import com.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SemanticMemoryTest {
 @TempDir Path workspace;
 String id(){return UUID.randomUUID().toString();}
 LocalCrossSessionMemory service(String mode,EmbeddingProvider provider,SemanticMemoryIndex index,boolean on){return new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->on,workspace).retrieval(mode,provider,index);}
 @Test void fakeRankingEvaluationAndStableDedup()throws Exception{
  var index=new SemanticMemoryIndex();
  var vectors=Map.of("项目使用 Java 17",new double[]{1,0,0},"新增功能补充测试",new double[]{0,1,0},"日志使用中文",new double[]{0,0,1},"Java 17",new double[]{1,0,0},"验证新增行为",new double[]{0,1,0},"诊断信息本地化",new double[]{0,0,1});
  var provider=new FakeEmbeddingProvider(vectors,3);var s=service("keyword",provider,index,true);
  var a=s.rememberAuthorized(id(),"项目使用 Java 17");var b=s.rememberAuthorized(id(),"新增功能补充测试");var c=s.rememberAuthorized(id(),"日志使用中文");
  var expected=Map.of("Java 17",a.memoryId(),"验证新增行为",b.memoryId(),"诊断信息本地化",c.memoryId());
  for(String mode:List.of("keyword","semantic","hybrid")){
   int hits=0;double reciprocal=0;
   for(var q:expected.entrySet()){
    var ranked=service(mode,provider,index,true).retrieveAuthorized(id(),q.getKey());
    assertEquals(ranked.size(),ranked.stream().map(h->h.memory().memoryId()).distinct().count());
    for(int i=0;i<ranked.size();i++)if(ranked.get(i).memory().memoryId().equals(q.getValue())){hits++;reciprocal+=1.0/(i+1);}
   }
   System.out.println("M6B_FAKE_ONLY mode="+mode+" Hit@3="+hits+"/3 MRR="+reciprocal/3);
   if(!mode.equals("keyword"))assertEquals(3,hits);
  }
  assertTrue(index.metrics().hits()>0);
 }
 @Test void updateAndForgetCannotResurrectCachedMemory()throws Exception{
  var index=new SemanticMemoryIndex();var provider=new FakeEmbeddingProvider(Map.of("Java 17",new double[]{1,0},"Java 21",new double[]{0,1},"语言配置",new double[]{1,0}),2);
  var s=service("semantic",provider,index,true);var m=s.rememberAuthorized(id(),"Java 17");
  assertEquals(m.memoryId(),s.retrieveAuthorized(id(),"语言配置").get(0).memory().memoryId());
  s.updateAuthorized(m.memoryId(),"Java 21");assertTrue(s.retrieveAuthorized(id(),"语言配置").isEmpty());
  s.forgetAuthorized(m.memoryId());assertTrue(s.retrieveAuthorized(id(),"语言配置").isEmpty());
 }
 @Test void offAndProviderFailureAndDimensionsFailSafely()throws Exception{
  var index=new SemanticMemoryIndex();EmbeddingProvider failure=new EmbeddingProvider(){public String identity(){return "failure";}public int dimensions(){return 2;}public double[] embed(String s){throw new AssertionError("OFF must not call");}};
  assertTrue(service("hybrid",failure,index,false).retrieveAuthorized(id(),"Java").isEmpty());assertEquals(0,index.metrics().calls());
  var seed=service("keyword",null,index,true);seed.rememberAuthorized(id(),"Java 17");
  var bad=new FakeEmbeddingProvider(Map.of("Java",new double[]{Double.NaN,0}),2);
  assertEquals(1,service("semantic",bad,index,true).retrieveAuthorized(id(),"Java").size());assertEquals(1,index.metrics().failures());
  assertThrows(IllegalArgumentException.class,()->EmbeddingProvider.normalized(new double[]{0,0},2));
  assertThrows(IllegalArgumentException.class,()->EmbeddingProvider.normalized(new double[]{1},2));
  assertThrows(IllegalArgumentException.class,()->EmbeddingProvider.normalized(new double[]{Double.POSITIVE_INFINITY},1));
 }
 @Test void changedModelOrDimensionsNeverReusesOldVectors()throws Exception{
  var index=new SemanticMemoryIndex();var old=new FakeEmbeddingProvider(Map.of("Java",new double[]{1,0},"Java 17",new double[]{1,0}),2);
  var s=service("semantic",old,index,true);s.rememberAuthorized(id(),"Java 17");s.retrieveAuthorized(id(),"Java");int before=index.metrics().calls();
  var changed=new FakeEmbeddingProvider(Map.of("Java",new double[]{0,1,0},"Java 17",new double[]{0,1,0}),3);
  assertEquals(1,service("semantic",changed,index,true).retrieveAuthorized(id(),"Java").size());assertEquals(before+2,index.metrics().calls());
 }
 @Test void retrievalModePersistsAndOldStateDefaultsToKeyword()throws Exception{
  assertEquals("keyword",CrossSessionState.off().retrievalMode());
  var state=new CrossSessionState(true,List.of(),"hybrid");var json=new com.fasterxml.jackson.databind.ObjectMapper();
  assertEquals("hybrid",json.readValue(json.writeValueAsBytes(state),CrossSessionState.class).retrievalMode());
  assertEquals("hybrid",state.withEnabled(false).retrievalMode());
 }
 @Test void actualCliRequestsRespectSemanticModeAndGlobalOff()throws Exception{
  List<List<com.agent.llm.Message>> requests=new ArrayList<>();
  com.agent.llm.LLMClient llm=messages->{requests.add(List.copyOf(messages));return new com.agent.llm.LLMResponse("offline",List.of());};
  var cli=new InteractiveCli(CliAgentFactory.createProfiles(llm,workspace,com.agent.agent.AgentEventListener.NO_OP),new ConsoleUi(new java.io.PrintStream(new java.io.ByteArrayOutputStream())),workspace);
  String memory="项目使用 Java 17",query="语言运行环境";
  cli.embeddingProviderForTest(new FakeEmbeddingProvider(Map.of(memory,new double[]{1,0},query,new double[]{1,0}),2));
  cli.run(new java.io.BufferedReader(new java.io.StringReader("/memory global on\n/memory remember "+memory+"\n/new\n/chat\n"+query+"\n/exit\n")));
  assertFalse(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")));
  cli.run(new java.io.BufferedReader(new java.io.StringReader("/memory global on\n/memory retrieval semantic\n"+query+"\n/exit\n")));
  assertTrue(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains(memory)));
  cli.run(new java.io.BufferedReader(new java.io.StringReader("/memory global off\n"+query+"\n/exit\n")));
  assertFalse(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")));
 }
 @Test void corruptedDerivedCacheFallsBackWithoutChangingSharedLibrary()throws Exception{
  var index=new SemanticMemoryIndex();var provider=new FakeEmbeddingProvider(Map.of("Java",new double[]{1,0},"Java 17",new double[]{1,0}),2);
  var s=service("semantic",provider,index,true);var m=s.rememberAuthorized(id(),"Java 17");s.retrieveAuthorized(id(),"Java");
  byte[] original=java.nio.file.Files.readAllBytes(workspace.resolve(".agent-cli/memory/shared.json"));
  var field=SemanticMemoryIndex.class.getDeclaredField("cache");field.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,SemanticMemoryIndex.Entry> cache=(Map<String,SemanticMemoryIndex.Entry>)field.get(index);
  var old=cache.get(m.memoryId());old.vector()[0]=Double.NaN;
  assertEquals(m.memoryId(),s.retrieveAuthorized(id(),"Java").get(0).memory().memoryId());
  assertTrue(index.metrics().failures()>0);
  assertArrayEquals(original,java.nio.file.Files.readAllBytes(workspace.resolve(".agent-cli/memory/shared.json")));
 }
 @Test void similarMemoriesHaveStableTiesAndModelIdentityInvalidatesCache()throws Exception{
  var index=new SemanticMemoryIndex();var vectors=Map.of("Java 17",new double[]{1,0},"项目 JDK 配置",new double[]{1,0},"运行时版本",new double[]{1,0});
  var fake=new FakeEmbeddingProvider(vectors,2);var s=service("hybrid",fake,index,true);
  var a=s.rememberAuthorized(id(),"Java 17");var b=s.rememberAuthorized(id(),"项目 JDK 配置");
  var first=s.retrieveAuthorized(id(),"运行时版本");assertEquals(2,first.size());
  assertEquals(first,s.retrieveAuthorized(id(),"运行时版本"));
  int before=index.metrics().calls();
  EmbeddingProvider nextModel=new EmbeddingProvider(){public String identity(){return "different:model-v2";}public int dimensions(){return 2;}public double[] embed(String text){return vectors.get(text).clone();}};
  var semantic=service("semantic",nextModel,index,true).retrieveAuthorized(id(),"运行时版本");
  assertEquals(before+3,index.metrics().calls());assertEquals(2,semantic.size());
  assertEquals(List.of(a.memoryId(),b.memoryId()).stream().sorted().toList(),semantic.stream().map(h->h.memory().memoryId()).toList());
 }
}
