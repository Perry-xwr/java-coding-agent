package com.agent;
import com.agent.llm.*;
import com.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class D1MemoryDemoTest {
 @TempDir Path temp;
 @Test void fullOfflineDemoThroughRealCliCommands()throws Exception {
  Path workspace=System.getProperty("agent.demo.workspace")==null?temp:Path.of(System.getProperty("agent.demo.workspace"));
  Files.createDirectories(workspace);try(var files=Files.list(workspace)){assertEquals(0,files.count(),"D1 requires an empty new workspace");}
  List<List<Message>> requests=new ArrayList<>();LLMClient fake=messages->{requests.add(List.copyOf(messages));return new LLMResponse("[FAKE] offline response",List.of());};
  var output=new ByteArrayOutputStream();var ui=new ConsoleUi(new PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8),"FAKE (OFFLINE ONLY)","d1-scripted");
  var cli=new InteractiveCli(CliAgentFactory.createProfiles(fake,workspace,ui),ui,workspace);
  cli.embeddingProviderForTest(new FakeEmbeddingProvider(Map.of(),1));
  run(cli,"/help\n/new\n/memory global on\n/memory auto on\n/chat\n这个项目使用 Java 17 和 Maven，后续新增功能尽量补充 JUnit 测试。\n/memory pending\n/exit\n");
  var manager=new CliConversationManager(workspace);var a=manager.list().get(0);assertFalse(a.snapshot().extraction().candidates().isEmpty());
  for(var candidate:a.snapshot().extraction().candidates())run(cli,"/memory approve "+candidate.candidateId()+"\n/exit\n");
  var memory=new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->true,workspace);
  var target=memory.listAuthorized().stream().filter(m->m.content().contains("Java 17")).findFirst().orElseThrow();
  run(cli,"/memory list\n/new\n/memory global status\n/chat\n请问项目使用什么 Java 版本？\n/exit\n");absent(requests,"CROSS_SESSION_MEMORY_DATA");
  String b=manager.list().stream().filter(c->!c.id().equals(a.id())).findFirst().orElseThrow().id();
  run(cli,"/memory global on\n请问项目使用什么 Java 版本？\n/exit\n");present(requests,"Java 17");
  run(cli,"/switch "+a.id()+"\n/chat\n项目已经升级到 Java 21，以后统一使用 Java 21。\n/exit\n");
  var candidate=manager.load(a.id()).snapshot().extraction().candidates().stream().filter(c->c.reviewStatus().equals("PENDING")&&c.candidateContent().contains("Java 21")).findFirst().orElseThrow();
  run(cli,"/memory review "+candidate.candidateId()+"\n/memory replace "+candidate.candidateId()+" "+target.memoryId()+"\n/memory versions "+target.memoryId()+"\n/switch "+b+"\n/chat\n请问项目使用什么 Java 版本？\n/exit\n");
  present(requests,"Java 21");absent(requests,"Java 17");
  run(cli,"/memory forget "+target.memoryId()+"\n/memory search Java\n/exit\n");
  assertTrue(memory.listAuthorized().stream().noneMatch(m->m.memoryId().equals(target.memoryId())));
  var fresh=new InteractiveCli(CliAgentFactory.createProfiles(fake,workspace,ui),ui,workspace);
  fresh.embeddingProviderForTest(new FakeEmbeddingProvider(Map.of(),1));
  run(fresh,"/switch "+b+"\n/chat\n请问项目使用什么 Java 版本？\n/exit\n");absent(requests,"Java 21");absent(requests,"Java 17");
  run(fresh,"/memory global off\n请问项目使用什么 Java 版本？\n/exit\n");absent(requests,"CROSS_SESSION_MEMORY_DATA");
  String transcript=output.toString(java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(transcript.contains("Provider: FAKE"));assertTrue(transcript.contains("Mode: UNIFIED"));assertTrue(transcript.contains("OLD_VERSION"));assertTrue(transcript.contains("/memory review"));
  Path evidence=System.getProperty("agent.demo.workspace")==null?workspace.resolve("demo-evidence"):workspace.resolveSibling("evidence");Files.createDirectories(evidence);
  Files.writeString(evidence.resolve("fake-cli-transcript.txt"),transcript);
  Files.writeString(evidence.resolve("result.txt"),"D1_FAKE_E2E=PASS\nREAL_API_REQUESTS=0\nALL_STAGES=PASS\nWORKSPACE="+workspace+"\n");
 }
 void run(InteractiveCli c,String s)throws Exception{c.run(new BufferedReader(new StringReader(s)));}
 void present(List<List<Message>> requests,String text){assertTrue(shared(requests).contains(text),text);}
 void absent(List<List<Message>> requests,String text){assertFalse(shared(requests).contains(text),text);}
 String shared(List<List<Message>> requests){return requests.get(requests.size()-1).stream().filter(m->m.content().startsWith("CROSS_SESSION_MEMORY_DATA")).map(Message::content).collect(java.util.stream.Collectors.joining());}
}
