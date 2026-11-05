package com.agent;
import com.agent.memory.*;
import com.agent.llm.*;
import com.agent.agent.AgentEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class MemoryConflictTest {
 @TempDir Path workspace;
 LocalCrossSessionMemory memory(){return new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->true,workspace);}
 String id(){return UUID.randomUUID().toString();}
 @Test void deterministicRelationsAreConservative()throws Exception{
  var detector=new DeterministicConflictDetector();var m=memory().rememberAuthorized(id(),"项目使用 Java 17");
  assertEquals(ConflictDetector.Relation.NEW,detector.detect("项目使用 Java 17",List.of()).relation());
  assertEquals(ConflictDetector.Relation.DUPLICATE,detector.detect(m.content(),List.of(m)).relation());
  assertEquals(ConflictDetector.Relation.DUPLICATE,detector.detect("项目采用 JDK 17",List.of(m)).relation());
  assertEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,detector.detect("项目已经升级到 Java 21",List.of(m)).relation());
  assertEquals(ConflictDetector.Relation.RELATED,detector.detect("项目使用 Maven",List.of(m)).relation());
  assertEquals(ConflictDetector.Relation.UNCERTAIN,detector.detect("以前项目使用 Java 21",List.of(m)).relation());
  var other=memory().rememberAuthorized(id(),"项目 A 使用 Java 17");
  assertNotEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,detector.detect("项目 B 使用 Java 21",List.of(other)).relation());
  assertEquals(ConflictDetector.Relation.UNCERTAIN,detector.detect("支持多语言",List.of(m)).relation());
  assertEquals(ConflictDetector.Relation.RELATED,detector.detect("项目使用 Java 17 并要求代码审查",List.of(m)).relation());
  var named=memory().rememberAuthorized(id(),"支付项目使用 Java 17");
  assertNotEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,detector.detect("库存项目使用 Java 21",List.of(named)).relation());
 }
 @Test void replacementIsAtomicVersionedAndStaleSafe()throws Exception{
  var m=memory().rememberAuthorized(id(),"项目使用 Java 17");String expected=SessionSummary.digest(m.content()+m.updatedAt());String candidate=id();
  var store=new SharedMemoryStore(workspace);var updated=store.updateChecked(m.memoryId(),m.workspaceScope(),"项目使用 Java 21",expected,candidate);
  assertEquals(m.memoryId(),updated.memoryId());assertEquals(candidate,updated.revisions().get(0).candidateId());assertEquals(m.content(),updated.revisions().get(0).oldContent());
  assertThrows(IOException.class,()->store.updateChecked(m.memoryId(),m.workspaceScope(),"Java 23",expected,id()));
  assertEquals("项目使用 Java 21",new SharedMemoryStore(workspace).read().get(0).content());
  String version=SessionSummary.digest(updated.content()+updated.updatedAt());byte[] before=Files.readAllBytes(workspace.resolve(".agent-cli/memory/shared.json"));
  var broken=new SharedMemoryStore(workspace,(p,b)->{throw new IOException("fixture failure");});
  assertThrows(IOException.class,()->broken.updateChecked(m.memoryId(),m.workspaceScope(),"Java 23",version,id()));assertArrayEquals(before,Files.readAllBytes(workspace.resolve(".agent-cli/memory/shared.json")));
  memory().forgetAuthorized(m.memoryId());assertTrue(memory().retrieveAuthorized(id(),"Java").isEmpty());
 }
 @Test void maliciousOrInvalidLlmSuggestionsNeverAuthorizeAnything()throws Exception{
  var m=memory().rememberAuthorized(id(),"Java 17");
  for(String response:List.of("broken","{}","{\"relation\":\"POSSIBLE_CONFLICT\",\"targetMemoryId\":\"fake\",\"proposedAction\":\"REVIEW\",\"proposedContent\":\"Java 21\",\"evidence\":\"Java 17 Java 21\",\"explanation\":\"test\"}")){
   var d=new LlmConflictDetector(messages->new LLMResponse(response,List.of()));assertEquals(ConflictDetector.Relation.UNCERTAIN,d.detect("Java 21",List.of(m)).relation());
  }
  assertEquals("Java 17",memory().listAuthorized().get(0).content());
 }
 @Test void validLlmProposalIsOnlyASuggestion()throws Exception{
  var m=memory().rememberAuthorized(id(),"Java 17");
  String json="{\"relation\":\"POSSIBLE_CONFLICT\",\"targetMemoryId\":\""+m.memoryId()+"\",\"proposedAction\":\"REVIEW\",\"proposedContent\":\"Java 21\",\"evidence\":\"Java 21 | Java 17\",\"explanation\":\"version difference\"}";
  var detector=new LlmConflictDetector(messages->new LLMResponse(json,List.of()));
  assertEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,detector.detect("Java 21",List.of(m)).relation());
  assertEquals("Java 17",memory().listAuthorized().get(0).content());
  assertEquals(ConflictDetector.Relation.UNCERTAIN,new LlmConflictDetector(messages->{throw new IOException("failure");}).detect("Java 21",List.of(m)).relation());
 }
 @Test void fakeLlmConversationReviewReplaceAndRejectBranch()throws Exception{
  List<List<Message>> requests=new ArrayList<>();LLMClient llm=messages->{requests.add(List.copyOf(messages));return new LLMResponse("offline",List.of());};
  var cli=new InteractiveCli(CliAgentFactory.createProfiles(llm,workspace,AgentEventListener.NO_OP),new ConsoleUi(new PrintStream(new ByteArrayOutputStream())),workspace);
  run(cli,"/memory global on\n/memory remember 项目使用 Java 17\n/memory auto on\n/chat\n项目已经升级到 Java 21，以后统一使用 Java 21。\n/exit\n");
  var manager=new CliConversationManager(workspace);var a=manager.list().get(0);var candidates=a.snapshot().extraction().candidates();assertFalse(candidates.isEmpty());
  String target=memory().listAuthorized().get(0).memoryId();String rejected=candidates.get(0).candidateId();
  run(cli,"/memory review "+rejected+"\n/memory reject "+rejected+"\n/exit\n");assertEquals("项目使用 Java 17",memory().listAuthorized().get(0).content());
  String approved=candidates.get(1).candidateId();run(cli,"/memory review "+approved+"\n/memory replace "+approved+" "+target+"\n/exit\n");
  var current=memory().listAuthorized().get(0);assertEquals(target,current.memoryId());assertTrue(current.content().contains("Java 21"));assertEquals(approved,current.revisions().get(0).candidateId());
  run(cli,"/new\n/memory global on\n/chat\n请问项目使用什么 Java 版本？\n/exit\n");
  String block=requests.get(requests.size()-1).stream().filter(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")).map(Message::content).findFirst().orElseThrow();assertTrue(block.contains("Java 21"));assertFalse(block.contains("Java 17"));
  run(cli,"/memory global off\n请问项目使用什么 Java 版本？\n/exit\n");assertFalse(requests.get(requests.size()-1).stream().anyMatch(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")));
 }
 void run(InteractiveCli cli,String input)throws Exception{cli.run(new BufferedReader(new StringReader(input)));}
 @Test void offAndStaleCliReviewsCannotReplace()throws Exception{
  LLMClient llm=messages->new LLMResponse("offline",List.of());
  var cli=new InteractiveCli(CliAgentFactory.createProfiles(llm,workspace,AgentEventListener.NO_OP),new ConsoleUi(new PrintStream(new ByteArrayOutputStream())),workspace);
  run(cli,"/memory global on\n/memory remember 项目使用 Java 17\n/memory auto on\n/chat\n项目已经升级到 Java 21\n/exit\n");
  var candidate=new CliConversationManager(workspace).list().get(0).snapshot().extraction().candidates().get(0);
  var target=memory().listAuthorized().get(0);
  run(cli,"/memory review "+candidate.candidateId()+"\n/exit\n");
  memory().updateAuthorized(target.memoryId(),"项目使用 Java 23");
  run(cli,"/memory replace "+candidate.candidateId()+" "+target.memoryId()+"\n/exit\n");assertEquals("项目使用 Java 23",memory().listAuthorized().get(0).content());
  run(cli,"/memory global off\n/memory review "+candidate.candidateId()+"\n/memory replace "+candidate.candidateId()+" "+target.memoryId()+"\n/memory versions "+target.memoryId()+"\n/exit\n");
  assertEquals("项目使用 Java 23",memory().listAuthorized().get(0).content());
 }
}
