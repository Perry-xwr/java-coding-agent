package com.agent;
import com.agent.llm.*;
import com.agent.tool.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionHistoryToolsTest {
 @TempDir Path root;
 String seed(TuiSessionController c,Path w,String title,String user,String answer)throws Exception{
  c.createSession();String id=c.loop().snapshot().currentSessionId();c.rename(title);var manager=new CliConversationManager(w);manager.activate(manager.load(id));var s=manager.active().snapshot();var histories=new EnumMap<CliMode,List<Message>>(CliMode.class);histories.putAll(s.histories());var h=new ArrayList<>(histories.get(CliMode.CODE));h.add(Message.user(user));h.add(Message.assistant(answer));histories.put(CliMode.CODE,h);var summary=new com.agent.memory.ExtractiveSessionSummarizer().summarize(id,histories,s.summary());manager.checkpoint(new CliSessionSnapshot(s.version(),java.time.Instant.now().toString(),CliMode.CODE,null,histories,s.workingContext(),summary,s.crossSessionMemory(),s.extraction()));return id;
 }
 @Test void actualDailyToolCallingReadsStoredHistoryAndFeedsNextMockHttpRequest()throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));var helper=new DailyRuntimeTest();var json=new ObjectMapper();var calls=new AtomicInteger();var chosen=new AtomicReference<String>();var bodies=new ArrayList<String>();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var buffer=new okio.Buffer();r.body().writeTo(buffer);String body=buffer.readUtf8();bodies.add(body);int n=calls.incrementAndGet();
   if(n==1){assertFalse(body.contains("首次授权一次"));assertTrue(body.contains("search_sessions"));return helper.reply(r,helper.call("search_sessions","{\"query\":\"自动 Memory\"}"));}
   var messages=json.readTree(body).path("messages");String tool="";for(var m:messages)if(m.path("role").asText().equals("tool"))tool=m.path("content").asText();
   if(n==2){var result=json.readTree(tool);String id=result.path("matches").get(0).path("sessionId").asText();chosen.set(id);return helper.reply(r,helper.call("read_session_context",json.writeValueAsString(Map.of("session_id",id))));}
   assertTrue(tool.contains("首次授权一次"));assertTrue(tool.contains("冲突与不确定信息不能直接自动保存"));assertTrue(tool.contains("用户级授权持久化"));assertTrue(tool.contains(chosen.get()));return helper.answer(r,"根据历史会话 "+chosen.get()+" 的引用继续讨论；当前代码和测试状态尚未重新验证。");
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   String id=seed(c,w,"自动 Memory 方案","自动 Memory：已确定规则 + DeepSeek 混合提取；首次授权一次，后续持续自动运行。","冲突与不确定信息不能直接自动保存。待办：实现用户级授权持久化。");seed(c,w,"菜谱","怎样做面条？","先烧水。");byte[] before=Files.readAllBytes(w.resolve(".agent-cli/conversations/"+id+".json"));c.createSession();helper.send(c,a,"继续上次关于自动 Memory 的讨论，不修改文件。");assertEquals(id,chosen.get());assertEquals(3,calls.get());assertEquals(2,c.loop().snapshot().current().turn().tools().size());assertArrayEquals(before,Files.readAllBytes(w.resolve(".agent-cli/conversations/"+id+".json")));assertFalse(c.loop().snapshot().current().memory());
  }
 }
 @Test void boundedReadIsolationDeletedDamagedAndSourceSummary()throws Exception{
  Path w=Files.createDirectory(root.resolve("scope"));var json=new ObjectMapper();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("No network");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   String id=seed(c,w,"中文主题 Memory", "已决定：Memory 必须保留来源。"+"中文长内容".repeat(500),"历史完成状态不代表现在测试通过");c.createSession();String current=c.loop().snapshot().currentSessionId();var registry=new ToolRegistry();SessionHistoryTools.register(registry,w,current);
   var result=registry.execute("search_sessions","{\"query\":\"Memory 来源\"}");assertTrue(result.success());assertTrue(result.output().contains(id));assertFalse(result.output().contains(current));
   result=registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",id,"max_chars",128)));assertTrue(result.success());var read=json.readTree(result.output());assertTrue(read.path("truncated").asBoolean());assertEquals(128,read.path("nextCharacterOffset").asInt());assertTrue(read.path("validatedSummaryFacts").isArray());assertTrue(read.path("messages").get(0).has("sourceSha256"));
   assertTrue(registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",id,"max_chars",128,"character_offset",128))).success());
   assertFalse(registry.execute("read_session_context","{\"session_id\":\"../trash\"}").success());assertFalse(registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",UUID.randomUUID().toString()))).success());assertFalse(registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",id,"workspace","outside"))).success());
   byte[] before=Files.readAllBytes(w.resolve(".agent-cli/conversations/"+id+".json"));new SessionTrash(w).delete(id,new SessionTrash(w).fingerprint(id));
   assertFalse(registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",id))).success());
   Files.writeString(w.resolve(".agent-cli/conversations/"+UUID.randomUUID()+".json"),"broken");var empty=registry.execute("search_sessions","{\"query\":\"Memory 来源\"}");assertTrue(empty.success());assertEquals(0,json.readTree(empty.output()).path("matches").size());assertArrayEquals(before,Files.readAllBytes(w.resolve(".agent-cli/trash/conversations/"+id+".json")));assertEquals(0,client.attempts());
  }
 } @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"{\"query\":\"Java Coding Agent\"}","{\"query\":\"Java Coding Agent\",\"limit\":2}","{\"query\":\"自动 Memory\"}","{\"query\":\"Java \\\"Coding\\\" Agent\",\"limit\":3.0}","{\"query\":\"Java Coding Agent\",\"limit\":10}"})
 void schemaCompatibleArgumentsThroughActualDailySse(String arguments)throws Exception{
  Path w=Files.createDirectory(root.resolve("variants"));var helper=new DailyRuntimeTest();var count=new AtomicInteger();var json=new ObjectMapper();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var b=new okio.Buffer();r.body().writeTo(b);var request=json.readTree(b.readUtf8());int n=count.incrementAndGet();
   if(n==1){var tools=request.path("tools");assertTrue(tools.toString().contains("search_sessions"));return helper.reply(r,helper.call("search_sessions",arguments));}
   String tool="";for(var m:request.path("messages"))if(m.path("role").asText().equals("tool"))tool=m.path("content").asText();
   if(n==2){var hits=json.readTree(tool).path("matches");assertEquals(1,hits.size());return helper.reply(r,helper.call("read_session_context",json.writeValueAsString(Map.of("session_id",hits.get(0).path("sessionId").asText()))));}
   assertTrue(tool.contains("Java 17"));assertTrue(tool.contains("自动 Memory 尚未实施"));return helper.answer(r,"已按来源历史整理；现在项目状态未重新验证。");
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   seed(c,w,"Java Coding Agent","我们决定 Java Coding Agent 使用 Java 17，下一步需要实现自动 Memory。","已确认 Java 17，自动 Memory 尚未实施。");seed(c,w,"其他主题","今天如何煮面条？","先烧水。");c.createSession();helper.send(c,a,"搜索之前关于 Java Coding Agent 的讨论，不修改文件。");assertEquals(3,count.get());assertEquals(2,c.loop().snapshot().current().turn().tools().size());
  }
 }
 @Test void invalidArgumentsStorageAndEmptyResultsAreDifferent()throws Exception{
  Path w=Files.createDirectory(root.resolve("errors"));var registry=new ToolRegistry();SessionHistoryTools.register(registry,w,UUID.randomUUID().toString());
  for(String invalid:List.of("broken","{\"query\":4}","{\"query\":\"\"}","{\"query\":\"Java\",\"limit\":\"3\"}","{\"query\":\"Java\",\"limit\":1.5}","{\"query\":\"Java\",\"limit\":0}","\"{\\\"query\\\":\\\"Java\\\"}\""))assertEquals(ToolErrorCode.INVALID_ARGUMENTS,registry.execute("search_sessions",invalid).errorCode());
  var empty=registry.execute("search_sessions","{\"query\":\"Java Coding Agent\"}");assertTrue(empty.success());assertEquals(0,new ObjectMapper().readTree(empty.output()).path("matches").size());
  Files.createDirectories(w.resolve(".agent-cli"));Files.writeString(w.resolve(".agent-cli/conversations"),"not a directory");assertEquals(ToolErrorCode.TOOL_EXECUTION_ERROR,registry.execute("search_sessions","{\"query\":\"Java\"}").errorCode());
 } @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"{\"query\":3}","{\"query\":\"Java\",\"limit\":0}","{\"query\":\"NO_MATCH_8472\"}"})
 void errorOrEmptyResultReturnsThroughActualToolProtocol(String arguments)throws Exception{
  Path w=Files.createDirectory(root.resolve("protocolErrors"));var helper=new DailyRuntimeTest();var calls=new AtomicInteger();var json=new ObjectMapper();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   if(calls.incrementAndGet()==1)return helper.reply(r,helper.call("search_sessions",arguments));
   var b=new okio.Buffer();r.body().writeTo(b);var messages=json.readTree(b.readUtf8()).path("messages");String last="";for(var m:messages)if(m.path("role").asText().equals("tool"))last=m.path("content").asText();
   if(arguments.contains("NO_MATCH"))assertEquals(0,json.readTree(last).path("matches").size());else assertTrue(last.contains("INVALID_ARGUMENTS"));
   return helper.answer(r,"没有足够历史证据，未完成历史总结。");
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.createSession();helper.send(c,a,"搜索旧讨论，不修改文件。");assertEquals(2,calls.get());assertEquals(1,c.loop().snapshot().current().turn().tools().size());
  }
 }}
