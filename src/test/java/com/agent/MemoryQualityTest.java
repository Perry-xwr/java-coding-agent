package com.agent;
import com.agent.llm.*;
import com.agent.memory.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MemoryQualityTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();final DailyRuntimeTest helper=new DailyRuntimeTest();String cooldown;String previousManagedMode;
 @BeforeEach void configure(){previousManagedMode=System.getProperty("agent.memory.agentManaged");System.setProperty("agent.memory.agentManaged","false");cooldown=System.getProperty("agent.memory.semantic.cooldownMillis");System.setProperty("agent.memory.semantic.cooldownMillis","0");}
 @AfterEach void restore(){if(cooldown==null)System.clearProperty("agent.memory.semantic.cooldownMillis");else System.setProperty("agent.memory.semantic.cooldownMillis",cooldown);if(previousManagedMode==null)System.clearProperty("agent.memory.agentManaged");else System.setProperty("agent.memory.agentManaged",previousManagedMode);}
 JsonNode body(Request request)throws Exception{var b=new okio.Buffer();request.body().writeTo(b);return json.readTree(b.readUtf8());}
 boolean extraction(JsonNode body){String prompt=body.path("messages").get(0).path("content").asText();return prompt.startsWith("Classify each user-authored sentence")||prompt.startsWith("Extract explicit durable");}
 MemoryExtractor.Source source(JsonNode body)throws Exception{return json.treeToValue(json.readTree(body.path("messages").get(1).path("content").asText()).get(0),MemoryExtractor.Source.class);}
 Map<String,Object> proposal(String text,MemoryExtractor.Source source,double confidence){String evidence=source.userText().contains(text)?text:source.userText();return Map.of("proposedContent",text,"category","PROJECT_CONSTRAINT","sourceMessageRefs",List.of(source.messageRef()),"evidenceQuote",evidence,"confidence",confidence,"proposedAction","PROPOSE");}
 Response extracted(Request r,String content)throws Exception{return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("mock extraction").body(ResponseBody.create(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content),"finish_reason","stop")),"usage",Map.of("prompt_tokens",17,"completion_tokens",9,"total_tokens",26))),MediaType.get("application/json"))).build();}
 void enable(TuiSessionController c,Path settings){c.enableMemoryConsent(new MemoryConsentStore(settings));assertTrue(c.memoryCommand("smart on"));c.resolveMemoryConsent(true);assertTrue(c.memoryCommand("semantic on"));c.resolveMemoryConsent(true);c.createSession();}
 void finish(TuiSessionController c)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(c.semanticMemoryBusy()&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertFalse(c.semanticMemoryBusy());}
 void send(TuiSessionController c,DailyAgentAdapter a,String text)throws Exception{helper.send(c,a,text);finish(c);assertEquals("COMPLETED",c.loop().snapshot().current().turn().status(),()->c.loop().snapshot().notice());assertTrue(c.loop().snapshot().current().turn().tools().isEmpty());}

 @Test void singleExplicitLearningPreferenceWithExampleQualifiesForSemanticExtraction()throws Exception{
  Path w=Files.createDirectory(root.resolve("single-example")),settings=root.resolve("user/single-example-consent.json");String text="以后学习新技术，我希望先看最小可运行示例。";
  assertTrue(MemoryQualityPolicy.durable(text));assertTrue(MemoryQualityPolicy.semanticNeeded(new MemoryExtractor.Source("CODE:1",text)));
 }

 @Test void screenshotPreferencesAutoSaveWithoutSemanticCallsAndAreUsedInNewSession()throws Exception{
  Path w=Files.createDirectory(root.resolve("memory-usability")),settings=root.resolve("user/memory-usability-consent.json");
  String workflow="我希望以后讨论新功能时，你先给出两个可行方案，比较各自的优缺点，再推荐一个。";
  String performance="再记一条长期习惯：做性能优化前，先通过实际测量定位瓶颈，不要凭感觉优化。";
  String temporary="今天电脑风扇声音比较大只是临时情况，不需要记住。";
  var requests=new ArrayList<JsonNode>();var extractionRequests=new AtomicInteger();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var body=body(r);requests.add(body);if(extraction(body)){extractionRequests.incrementAndGet();fail("clear quoted preferences should be handled by the deterministic fast path");}
   return helper.answer(r,"已了解。这里只作讨论，不修改文件。");
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,settings);assertEquals("UNIFIED",c.loop().snapshot().current().profile());
   for(String text:List.of(workflow,performance,temporary))send(c,a,text);
   assertEquals(2,new SharedMemoryStore(w).read().size());
   assertTrue(new SharedMemoryStore(w).read().stream().anyMatch(m->m.content().contains("两个可行方案")&&m.content().contains("优缺点")));
   assertTrue(new SharedMemoryStore(w).read().stream().anyMatch(m->m.content().contains("实际测量定位瓶颈")));
   assertFalse(new SharedMemoryStore(w).read().stream().anyMatch(m->m.content().contains("风扇声音")));
   var snapshot=new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot();
   assertTrue(snapshot.extraction().candidates().stream().filter(x->x.candidateContent().contains("两个可行方案")||x.candidateContent().contains("实际测量定位瓶颈"))
       .allMatch(x->x.reviewStatus().equals("APPROVED")));
   var diag=new MemoryRunDiagnostics(w).latest();assertEquals("SKIPPED",diag.status());assertEquals("NO_SEMANTIC_CANDIDATE",diag.reason());assertEquals(0,diag.effective());assertEquals(0,diag.pending());
   assertTrue(c.memoryCommand("semantic status"));String status=c.loop().snapshot().current().history().get(c.loop().snapshot().current().history().size()-1).text();
   assertTrue(status.contains("AUTO_MEMORY_STATUS=COMPLETED"));assertTrue(status.contains("SEMANTIC_STATUS=SKIPPED"));assertTrue(status.contains("SEMANTIC_SKIP_REASON=NO_SEMANTIC_CANDIDATE"));assertTrue(status.contains("todayUsed=0 (no daily cap)"));
   c.createSession();send(c,a,"讨论新功能时请比较两个可行方案的优缺点，再给我推荐。不要修改文件。");
    assertTrue(requests.get(requests.size()-1).toString().contains("CROSS_SESSION_MEMORY_DATA"),requests.get(requests.size()-1).toString());assertTrue(requests.get(requests.size()-1).toString().contains("两个可行方案"));
    send(c,a,"性能优化前应该先做什么？");
   assertTrue(requests.get(requests.size()-1).toString().contains("CROSS_SESSION_MEMORY_DATA"));assertTrue(requests.get(requests.size()-1).toString().contains("实际测量定位瓶颈"));
   assertEquals(0,extractionRequests.get());assertEquals(5,client.chatAttempts());assertEquals(2,new SharedMemoryStore(w).read().size());
  }
 }

 @Test void multilineDailyPreferenceTurnDoesNotFailBeforeSemanticReservationAndIsUsedInNewSession()throws Exception{
  Path w=Files.createDirectory(root.resolve("multiline-daily")),settings=root.resolve("user/multiline-daily-consent.json");
  String text="以后讨论新功能，先提供两个方案、比较优缺点并推荐。\r\n"
      +"引入第三方依赖前，先判断 Java 17 JDK 原生能力是否足够。\r\n"
      +"性能优化前先通过实际测试定位瓶颈。\r\n"
      +"学习新技术时先给最小可运行示例，再逐步扩展。\r\n"
      +"今天 Maven 下载慢，只是临时情况。\r\n"
      +"这轮只讨论，不修改项目文件。";
  assertTrue(MemoryQualityPolicy.durable(text));
  // This was the concrete pre-fix failure: source eligibility normalized CR/LF,
  // but the conflict detector received the original control characters.
  assertThrows(IllegalArgumentException.class,()->MemoryQualityPolicy.relation(text,List.of()));
  assertEquals(com.agent.memory.ConflictDetector.Relation.NEW,MemoryQualityPolicy.relation(text.replace('\r',' ').replace('\n',' '),List.of()));
  var requests=new AtomicInteger();var extractionRequests=new AtomicInteger();var injectedRequest=new java.util.concurrent.atomic.AtomicReference<String>();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var b=body(r);requests.incrementAndGet();
   if(extraction(b)){
    extractionRequests.incrementAndGet();var src=source(b);
    return extracted(r,json.writeValueAsString(List.of(
      proposal("以后讨论新功能，先提供两个方案、比较优缺点并推荐",src,.99),
      proposal("引入第三方依赖前，先判断 Java 17 JDK 原生能力是否足够",src,.99),
      proposal("性能优化前先通过实际测试定位瓶颈",src,.99))));
   }
   if(b.toString().contains("CROSS_SESSION_MEMORY_DATA"))injectedRequest.set(b.toString());
   return helper.answer(r,"已按当前请求处理。请勿修改项目文件。");
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,settings);assertTrue(c.memoryCommand("remember 项目使用 Java 17"));assertTrue(c.dailyProfile(CliMode.READ));
   send(c,a,text);
   var diag=new MemoryRunDiagnostics(w).latest();assertNotNull(diag);assertEquals("SKIPPED",diag.status(),()->diag.reason()+" candidates="+diag.semanticCandidates()+" error="+diag.errorCategory());
   assertEquals("RULES_COVERED_OR_NOT_NEEDED",diag.reason());assertEquals(4,diag.ruleCandidates());assertEquals(0,diag.semanticCandidates());assertEquals(4,diag.effective());
   var memories=new SharedMemoryStore(w).read();assertEquals(5,memories.size());assertTrue(memories.stream().anyMatch(m->m.content().contains("Java 17")));
   assertFalse(memories.stream().anyMatch(m->m.content().contains("Maven 下载慢")));
   assertTrue(c.createSession());assertTrue(c.dailyProfile(CliMode.READ));send(c,a,"以后讨论新功能，先提供两个方案、比较优缺点并推荐");assertEquals(0,extractionRequests.get());assertNotNull(injectedRequest.get());assertTrue(injectedRequest.get().contains("两个方案"),"new-session request omitted the relevant authorized memory");
    assertEquals(3,memories.stream().filter(m->!m.content().contains("Java 17")).count());
  }
  assertEquals(2,requests.get());assertEquals(0,extractionRequests.get());
 }

 @Test void fourExplicitLowRiskPreferencesAutoSaveAndAreUsedAfterSessionSwitch()throws Exception{
  Path w=Files.createDirectory(root.resolve("diagnostic")),settings=root.resolve("user/diagnostic-consent.json");
  String text="以后讨论新功能，先提供两个方案、比较优缺点并推荐。引入第三方依赖前，先判断 Java 17 JDK 原生能力是否足够。性能优化前先通过实际测试定位瓶颈。学习新技术时先给最小可运行示例，再逐步扩展。今天 Maven 下载慢，只是临时情况，不需要长期记住。这轮只讨论，不修改文件。";
  var requests=new AtomicInteger();var extractionRequests=new AtomicInteger();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{var b=body(r);requests.incrementAndGet();assertFalse(extraction(b),"all durable clauses should be handled deterministically without another model request");String req=b.toString();assertTrue(req.contains("CROSS_SESSION_MEMORY_DATA"));assertTrue(req.contains("Java 17"));return helper.answer(r,"已收到。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   assertTrue(c.createSession());String session=c.loop().snapshot().currentSessionId();c.enableMemoryConsent(new MemoryConsentStore(settings));
   assertTrue(c.memoryCommand("smart on"));c.resolveMemoryConsent(true);assertTrue(c.memoryCommand("semantic on"));c.resolveMemoryConsent(true);
   assertTrue(c.loop().snapshot().current().memory());
   // Existing Java 17 remains authoritative; this test never mutates a real workspace.
   assertTrue(c.memoryCommand("remember 项目使用 Java 17"));
   send(c,a,text);
    var diag=new MemoryRunDiagnostics(w).latest();assertNotNull(diag);assertEquals("SKIPPED",diag.status(),()->diag.reason()+" error="+diag.errorCategory());assertEquals("RULES_COVERED_OR_NOT_NEEDED",diag.reason());assertEquals(4,diag.ruleCandidates());assertEquals(0,diag.semanticCandidates());assertEquals(4,diag.effective());assertEquals(0,diag.pending());assertEquals(0,extractionRequests.get());
   var entries=new SharedMemoryStore(w).read();assertEquals(5,entries.size());assertTrue(entries.stream().anyMatch(m->m.content().contains("Java 17")));for(String preference:List.of("两个方案","第三方依赖前","实际测试定位瓶颈","最小可运行示例"))assertTrue(entries.stream().anyMatch(m->m.content().contains(preference)),preference);
    var extracted=new CliConversationManager(w).load(session).snapshot().extraction().candidates();assertEquals(4,extracted.size());assertTrue(extracted.stream().allMatch(candidate->candidate.reviewStatus().equals("APPROVED")));assertFalse(extracted.stream().anyMatch(candidate->candidate.candidateContent().contains("Maven 下载慢")||candidate.candidateContent().contains("不需要长期记住")));
   assertFalse(entries.stream().anyMatch(m->m.content().contains("Maven 下载慢")));
    assertTrue(c.memoryCommand("semantic status"));var h1=c.loop().snapshot().current().history();String status=h1.get(h1.size()-1).text();assertTrue(status.contains("LAST_TURN_STATUS=COMPLETED"));assertTrue(status.contains("SEMANTIC_STATUS=SKIPPED"));assertTrue(status.contains("SEMANTIC_SKIP_REASON=RULES_COVERED_OR_NOT_NEEDED"));assertTrue(status.contains("RULE_CANDIDATES=4"));assertTrue(status.contains("EFFECTIVE_SAVES=4"));assertTrue(status.contains("PENDING=0"));
   c.createSession();
  }
   try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{var b=body(r);String req=b.toString();assertTrue(req.contains("CROSS_SESSION_MEMORY_DATA"));assertFalse(req.contains("Maven 下载慢"));String lastUser="";for(JsonNode m:b.path("messages"))if(m.path("role").asText().equals("user"))lastUser=m.path("content").asText();String expected=lastUser.contains("方案")?"两个方案":lastUser.contains("第三方依赖")?"第三方依赖前":lastUser.contains("性能优化")?"实际测试定位瓶颈":"最小可运行示例";assertTrue(req.contains(expected),"task-relevant memory was not injected: "+expected);return helper.answer(r,"已按已授权的偏好处理。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
      c.enableMemoryConsent(new MemoryConsentStore(settings));var sessions=new CliConversationManager(w).list();assertTrue(c.select(sessions.get(sessions.size()-1).id()));assertTrue(c.memoryCommand("semantic status"));var h2=c.loop().snapshot().current().history();assertTrue(h2.get(h2.size()-1).text().contains("EFFECTIVE_SAVES=4"));for(String query:List.of("讨论新功能如何比较方案？","引入第三方依赖前应如何选择？","性能优化之前先做什么？","学习新技术时先看什么？"))send(c,a,query);assertEquals(0,extractionRequests.get());
  }
  assertEquals(1,requests.get());assertEquals(0,extractionRequests.get());
 }

 @Test void compoundFallbackThroughDailyHttpSavesTwoGroundedFactsAndNewSessionAndRestartUseThem()throws Exception{
  Path w=Files.createDirectory(root.resolve("project")),settings=root.resolve("user/consent.json");String text="以后增加功能前先分析风险，不要随便创建 Markdown 文档。";var requests=new ArrayList<JsonNode>();
  assertTrue(new DeterministicMemoryExtractor().extract(List.of(new MemoryExtractor.Source("CODE:1",text))).isEmpty());
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var b=body(r);requests.add(b);
   if(extraction(b)){assertEquals("deepseek-flash",b.path("model").asText());assertEquals(512,b.path("max_tokens").asInt());assertFalse(b.has("tools"));assertEquals(2,b.path("messages").size());var src=source(b);assertEquals(text,src.userText());return extracted(r,json.writeValueAsString(List.of(proposal("以后增加功能前先分析风险",src,.99),proposal("不要随便创建 Markdown 文档",src,.99))));}
   var memories=new ArrayList<String>();for(var m:b.path("messages")){assertFalse(m.path("role").asText().equals("tool"));String s=m.path("content").asText();if(s.startsWith("CROSS_SESSION_MEMORY_DATA"))for(var entry:json.readTree(s.substring(s.indexOf('\n')+1)))memories.add(entry.path("content").asText());}
   return helper.answer(r,memories.isEmpty()?"已了解这轮约定。":"按实际收到的记忆："+String.join("；",memories));
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,settings);send(c,a,text);assertEquals("UNIFIED",c.loop().snapshot().current().profile());var entries=new SharedMemoryStore(w).read();assertEquals(2,entries.size());
   for(var m:entries){assertEquals("SEMANTIC_EXTRACTED_USER_QUOTE",m.sourceType());assertTrue(text.contains(m.content()));var ref=m.sourceMessageReference().split(":");assertTrue(SessionSummary.sourceMatches(new CliConversationManager(w).load(m.sourceSessionId()).snapshot().histories().get(CliMode.CODE),Integer.parseInt(ref[1]),ref[2]));}
   assertEquals(1,client.extractionAttempts());assertEquals(1,client.chatAttempts());assertTrue(a.validationStatus().contains("task HTTP attempts=1"));var runDiag=new MemoryRunDiagnostics(w).latest();assertNotNull(runDiag);assertEquals("COMPLETED",runDiag.status());assertEquals("SEMANTIC_RESULT_VALIDATED",runDiag.reason());assertEquals(2,runDiag.semanticCandidates());assertEquals(2,runDiag.effective());
   var ledger=json.readTree(Files.readString(w.resolve(".agent-cli/memory/semantic-extraction.json")));assertEquals("SUCCEEDED",ledger.path("attempts").get(0).path("status").asText());assertEquals(26,ledger.path("attempts").get(0).path("usage").path("totalTokens").asLong());
   c.createSession();send(c,a,"解释分析风险与 Markdown 文档的约定。");var h=new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().histories().get(CliMode.CODE);assertTrue(ConversationCopy.select(h,false).contains("先分析风险"));assertTrue(ConversationCopy.select(h,false).contains("不要随便创建 Markdown"));assertEquals(1,client.extractionAttempts());
   send(c,a,text);assertEquals(2,new SharedMemoryStore(w).read().size());assertEquals(1,client.extractionAttempts());
   send(c,a,"以后新增功能前先评估风险。");assertEquals(2,new SharedMemoryStore(w).read().size());assertEquals(1,client.extractionAttempts(),"Deterministically equivalent facts do not need a new model request");
   c.memoryCommand("global off");c.createSession();send(c,a,"以后增加功能前先分析风险，不要自动创建 Markdown 文档。");assertEquals(1,client.extractionAttempts());assertEquals(2,new SharedMemoryStore(w).read().size());assertFalse(requests.get(requests.size()-1).toString().contains("CROSS_SESSION_MEMORY_DATA"));
   c.memoryCommand("smart on");c.resolveMemoryConsent(true);c.memoryCommand("semantic on");c.resolveMemoryConsent(true);
  }
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{var b=body(r);assertFalse(extraction(b));assertTrue(b.toString().contains("CROSS_SESSION_MEMORY_DATA"));return helper.answer(r,"按已授权的风险和文档约定处理。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(settings));assertTrue(new MemoryConsentStore(settings).read().semanticEnabled());c.createSession();send(c,a,"解释分析风险与 Markdown 文档的约定。");assertEquals(0,client.extractionAttempts());
  }
 }

 @ParameterizedTest @ValueSource(strings={"invalidJson","fabricatedSource","wrongType","unsupported","lowConfidence","negativeContext","apiFailure"})
 void untrustedOrUncertainOutputsCannotAutoSaveAndFailureDoesNotFailChat(String mode)throws Exception{
  Path w=Files.createDirectory(root.resolve("bad"));String text=mode.equals("negativeContext")?"我偏好以后不要增加功能前先分析风险。":"以后增加功能前先分析风险，长期约定由管理员决定。";
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
   var b=body(r);if(!extraction(b))return helper.answer(r,"已收到用户说明。");if(mode.equals("apiFailure"))throw new java.io.IOException("controlled mock failure");var src=source(b);String answer;
   if(mode.equals("invalidJson"))answer="broken";
   else if(mode.equals("wrongType"))answer=json.writeValueAsString(List.of(Map.of("proposedContent",42,"category","PROJECT_CONSTRAINT","sourceMessageRefs",List.of(src.messageRef()),"evidenceQuote",src.userText(),"confidence",.99,"proposedAction","PROPOSE")));
   else if(mode.equals("fabricatedSource"))answer=json.writeValueAsString(List.of(proposal("用户没有说过的约定",src,.99)));
   else answer=json.writeValueAsString(List.of(proposal(mode.equals("unsupported")?"长期约定由管理员决定":mode.equals("negativeContext")?"增加功能前先分析风险":"以后增加功能前先分析风险",src,mode.equals("lowConfidence")?.5:.99)));
   return extracted(r,answer);
  }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,root.resolve("user/consent.json"));send(c,a,text);assertTrue(new SharedMemoryStore(w).read().isEmpty());assertEquals(1,client.extractionAttempts());
   send(c,a,text);assertEquals(1,client.extractionAttempts(),"Failed inputs are not automatically retried or recharged");
   send(c,a,"解释一下 Transformer。");assertEquals(1,client.extractionAttempts());assertTrue(new SharedMemoryStore(w).read().isEmpty());
  }
 }

 @Test void explicitWeeklyRoutineDecisionIsAnIndependentSafeNewTopic() {
  String schedule="周一到周五准备大模型实习，周六周日休息";
  String quote="我们约定"+schedule;
  assertTrue(MemoryQualityPolicy.lowRisk(schedule));
  assertTrue(MemoryQualityPolicy.groundedDurableCandidate(schedule,quote,quote+"。"));
  assertFalse(MemoryQualityPolicy.groundedDurableCandidate(schedule,schedule,schedule+"。"),
          "a weekly-looking sentence without an explicit durable commitment is not auto-saved");
  assertFalse(MemoryQualityPolicy.groundedDurableCandidate(schedule,quote,"下面只是一个虚构示例："+quote+"，不代表真实安排。"));
  assertFalse(MemoryQualityPolicy.groundedDurableCandidate(schedule,quote,quote+"。API_KEY=FAKE_SECRET_92831"));
  var now=java.time.Instant.now().toString();var session=UUID.randomUUID().toString();var scope="isolated-workspace";
  var javaMemory=new LongTermMemory(UUID.randomUUID().toString(),"以后这个项目统一使用 Java 17",session,now,now,
          "USER_MEMORY_COMMAND","EXPLICIT_USER",scope,null);
  assertEquals(ConflictDetector.Relation.RELATED,MemoryQualityPolicy.relation(schedule,List.of(javaMemory)));
  var otherSchedule=new LongTermMemory(UUID.randomUUID().toString(),"周一到周五学习英语，周六周日休息",session,now,now,
          "USER_MEMORY_COMMAND","EXPLICIT_USER",scope,null);
  assertEquals(ConflictDetector.Relation.POSSIBLE_CONFLICT,MemoryQualityPolicy.relation(schedule,List.of(otherSchedule)));
  assertEquals(ConflictDetector.Relation.DUPLICATE,MemoryQualityPolicy.relation(schedule,List.of(
          new LongTermMemory(UUID.randomUUID().toString(),schedule,session,now,now,"USER_MEMORY_COMMAND","EXPLICIT_USER",scope,null))));
  String weekend="周六周日，上午去跑步，下午睡觉";String weekendQuote="我们约定，"+weekend;
  assertTrue(MemoryQualityPolicy.lowRisk(weekend));
  assertFalse(MemoryQualityPolicy.deterministicLowRisk(weekend),"new schedule shapes use the semantic path, not a guessed rule extraction");
  assertTrue(MemoryQualityPolicy.groundedDurableCandidate(weekend,weekendQuote,weekendQuote));
  assertFalse(MemoryQualityPolicy.groundedDurableCandidate(weekend,weekend,weekend),"no explicit durable assertion stays ineligible");
 }

 @Test void explicitExactRevisionKeepsIdAndOldVersionButUnclearConflictRemainsPending()throws Exception{
  Path w=Files.createDirectory(root.resolve("revision"));
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{var b=body(r);if(extraction(b))return extracted(r,json.writeValueAsString(List.of(proposal("项目使用 Java 21",source(b),.99))));return helper.answer(r,"已收到约定说明。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,root.resolve("user/consent.json"));send(c,a,"项目使用 Java 17。");var old=new SharedMemoryStore(w).read().get(0);
    send(c,a,"之前规定项目使用 Java 17，现在改成项目使用 Java 21。");var updated=new SharedMemoryStore(w).read().get(0);var diag=new MemoryRunDiagnostics(w).latest();var candidateState=new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().extraction().candidates();assertEquals(old.memoryId(),updated.memoryId());assertEquals("项目使用 Java 21",updated.content(),"diag="+diag+" candidates="+candidateState);
    assertEquals(1,updated.revisions().size());assertEquals(old.content(),updated.revisions().get(0).oldContent());assertTrue(updated.revisions().get(0).reason().contains("Explicit user revision"));assertNotNull(updated.revisions().get(0).candidateId());
   send(c,a,"以后这个项目统一使用 Java 22。");assertEquals("项目使用 Java 21",new SharedMemoryStore(w).read().get(0).content());assertTrue(new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().extraction().candidates().stream().anyMatch(p->p.candidateContent().contains("Java 22")&&p.reviewStatus().equals("PENDING")));
  }
 }

 @Test void consentIsSeparateAndQuestionsAndSimpleRulesUseZeroExtraCalls()throws Exception{
  Path w=Files.createDirectory(root.resolve("consent")),settings=root.resolve("user/consent.json");
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{assertFalse(extraction(body(r)));return helper.answer(r,"正常回答，不修改文件。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(settings));c.memoryCommand("smart on");c.resolveMemoryConsent(true);assertFalse(new MemoryConsentStore(settings).read().semanticEnabled());c.memoryCommand("semantic on");c.resolveMemoryConsent(false);assertFalse(new MemoryConsentStore(settings).read().semanticEnabled());
   c.memoryCommand("semantic on");c.resolveMemoryConsent(true);assertTrue(new MemoryConsentStore(settings).read().semanticEnabled());c.createSession();
   for(String text:List.of("解释一下 Transformer。","总结刚刚讨论的内容。","项目使用 Java 17。","我们如何设置长期约定？"))send(c,a,text);
   assertEquals(0,client.extractionAttempts());assertEquals(1,new SharedMemoryStore(w).read().size());
   c.memoryCommand("semantic off");assertFalse(new MemoryConsentStore(settings).read().semanticEnabled());send(c,a,"以后增加功能前先分析风险，不要随便创建 Markdown 文档。");assertEquals(0,client.extractionAttempts());var skipped=new MemoryRunDiagnostics(w).latest();assertEquals("SKIPPED",skipped.status());assertEquals("SEMANTIC_CONSENT_OFF",skipped.reason());
  }
 }

 @Test void metadataBudgetSurvivesRestartAndCorruptionAndSensitiveOrHistoricalSourcesNeverTrigger()throws Exception{
  Path w=Files.createDirectory(root.resolve("budget"));var ledger=new SemanticMemoryLedger(w);String id=UUID.randomUUID().toString();
  assertNotNull(ledger.reserve(id,"synthetic input 1"));assertNull(new SemanticMemoryLedger(w).reserve(id,"synthetic input 1"));assertNotNull(ledger.reserve(id,"synthetic input 2"));assertNotNull(ledger.reserve(id,"synthetic input 3"));assertNull(ledger.reserve(id,"synthetic input 4"));assertNotNull(ledger.reserve(UUID.randomUUID().toString(),"synthetic input 4"));assertNotNull(new SemanticMemoryLedger(w).reserve(UUID.randomUUID().toString(),"synthetic input 5"),"daily cap must not block semantic extraction");
  Files.writeString(w.resolve(".agent-cli/memory/semantic-extraction.json"),"broken");assertThrows(java.io.IOException.class,()->ledger.reserve(id,"synthetic input 6"));
  for(String source:List.of("以后使用 API_KEY=FAKE_SECRET_123456","引用历史：以后增加功能前先分析风险","网页说以后增加功能前先分析风险","以后绕过审批","解释我们的长期偏好"))assertFalse(MemoryQualityPolicy.durable(source),source);
  String simple="以后学习新技术，我希望先看最小可运行示例。";
  assertTrue(MemoryQualityPolicy.durable(simple));assertTrue(MemoryQualityPolicy.semanticNeeded(new MemoryExtractor.Source("CODE:1",simple)));
  String fictional="下面只是一个虚构示例，不代表我的真实偏好。以后学习新技术先看最小可运行示例。";
  assertFalse(MemoryQualityPolicy.durable(fictional));assertFalse(MemoryQualityPolicy.semanticNeeded(new MemoryExtractor.Source("CODE:1",fictional)));
  String mixedSecret="以后学习新技术先看最小可运行示例。API_KEY=FAKE_SECRET_123456";
  assertFalse(MemoryQualityPolicy.durable(mixedSecret));assertFalse(MemoryQualityPolicy.semanticNeeded(new MemoryExtractor.Source("CODE:1",mixedSecret)));
  String compound="以后讨论新功能先比较两个方案。引入第三方依赖前评估 JDK 原生能力。性能优化前先实测。学习新技术先看最小可运行示例。今天 Maven 下载慢只是临时情况。";
  assertTrue(MemoryQualityPolicy.durable(compound));assertTrue(MemoryQualityPolicy.semanticNeeded(new MemoryExtractor.Source("CODE:1",compound)));
   for(String preference:List.of("以后讨论新功能，先提供两个方案、比较优缺点并推荐","引入第三方依赖前，先判断 Java 17 JDK 原生能力是否足够","性能优化前先通过实际测试定位瓶颈","学习新技术时先给最小可运行示例，再逐步扩展"))assertTrue(AutomaticMemory.lowRisk(preference),preference);
   String four="以后讨论新功能，先提供两个方案、比较优缺点并推荐。";assertTrue(MemoryQualityPolicy.entailed("以后讨论新功能，先提供两个方案、比较优缺点并推荐",four));assertFalse(MemoryQualityPolicy.entailed("先提供两个方案",four));
   assertFalse(new DeterministicMemoryExtractor().extract(List.of(new MemoryExtractor.Source("CODE:1","讨论新功能，先提供两个方案、比较优缺点并推荐。"))).isEmpty());
   assertFalse(AutomaticMemory.lowRisk("今天 Maven 下载慢，只是临时情况"));assertFalse(AutomaticMemory.lowRisk("不要记住今天 Maven 下载慢"));
  assertTrue(AutomaticMemory.lowRisk("以后讲大模型算法先讲原理，再讲代码，最后再做面试题"));assertFalse(MemoryQualityPolicy.entailed("增加功能前先分析风险","我偏好以后不要增加功能前先分析风险"));
 }

 @Test void semanticReservationAndStatusExposeSpecificSkipReasons()throws Exception{
  String oldDaily=System.getProperty("agent.memory.semantic.maxDailyRequests"),oldCooldown=System.getProperty("agent.memory.semantic.cooldownMillis");
  try{
   System.setProperty("agent.memory.semantic.maxDailyRequests","1");System.setProperty("agent.memory.semantic.cooldownMillis","0");
   Path daily=Files.createDirectory(root.resolve("daily-reason"));var ledger=new SemanticMemoryLedger(daily);String session=UUID.randomUUID().toString();
   assertTrue(ledger.reserveDetailed(session,"durable source one").granted());
   assertEquals("DUPLICATE_SOURCE",ledger.reserveDetailed(session,"durable source one").skipReason());
   for(int i=2;i<=6;i++)assertTrue(ledger.reserveDetailed(UUID.randomUUID().toString(),"durable source "+i).granted(),"daily requests are no longer capped");
   String status=ledger.status(session);assertTrue(status.contains("todayUsed=6 (no daily cap)"));assertTrue(status.contains("currentSessionUsed=1/3"));assertTrue(status.contains("cooldownRemainingMs=0"));

   Path sessionCap=Files.createDirectory(root.resolve("session-reason"));var capped=new SemanticMemoryLedger(sessionCap);String cappedSession=UUID.randomUUID().toString();
   assertTrue(capped.reserveDetailed(cappedSession,"one").granted());assertTrue(capped.reserveDetailed(cappedSession,"two").granted());assertTrue(capped.reserveDetailed(cappedSession,"three").granted());
   assertEquals("SESSION_BUDGET_EXHAUSTED",capped.reserveDetailed(cappedSession,"four").skipReason());

   System.setProperty("agent.memory.semantic.cooldownMillis","120000");
   Path cooldown=Files.createDirectory(root.resolve("cooldown-reason"));var cooling=new SemanticMemoryLedger(cooldown);String coolingSession=UUID.randomUUID().toString();
   assertTrue(cooling.reserveDetailed(coolingSession,"first").granted());assertEquals("COOLDOWN_ACTIVE",cooling.reserveDetailed(coolingSession,"second").skipReason());
   assertTrue(cooling.status(coolingSession).matches("(?s).*cooldownRemainingMs=[1-9][0-9]*.*"));
  }finally{if(oldDaily==null)System.clearProperty("agent.memory.semantic.maxDailyRequests");else System.setProperty("agent.memory.semantic.maxDailyRequests",oldDaily);if(oldCooldown==null)System.clearProperty("agent.memory.semantic.cooldownMillis");else System.setProperty("agent.memory.semantic.cooldownMillis",oldCooldown);}
 }
 @Test void semanticWaitDoesNotBlockUiAndRevokedConsentDiscardsCompletedProposal()throws Exception{
  Path w=Files.createDirectory(root.resolve("async")),settings=root.resolve("user/consent.json");var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{var b=body(r);if(!extraction(b))return helper.answer(r,"正常回答已完成。");entered.countDown();release.await();return extracted(r,json.writeValueAsString(List.of(proposal("以后增加功能前先分析风险",source(b),.99))));}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   enable(c,settings);helper.send(c,a,"以后增加功能前先分析风险，不要随便创建 Markdown 文档。");assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(c.semanticMemoryBusy());
   c.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.TEXT,"中文草稿");c.loop().resize(95,28);c.pump();assertEquals("中文草稿",c.loop().snapshot().current().input().text());
   c.memoryCommand("semantic off");assertFalse(new MemoryConsentStore(settings).read().semanticEnabled());release.countDown();finish(c);assertTrue(new SharedMemoryStore(w).read().isEmpty());
   var ledger=json.readTree(Files.readString(w.resolve(".agent-cli/memory/semantic-extraction.json")));assertEquals("DISCARDED",ledger.path("attempts").get(0).path("status").asText());
  }finally{release.countDown();}
 }
 @Test void legacyConsentIsOffForPaidCallsAndStaleRevisionCannotOverwriteChangedFact()throws Exception{
  Path settings=root.resolve("legacy.json");Files.writeString(settings,"{\"version\":1,\"revision\":2,\"enabled\":true,\"autoConsent\":true}");assertFalse(new MemoryConsentStore(settings).read().semanticEnabled());
  Path w=Files.createDirectory(root.resolve("stale"));String now=java.time.Instant.now().toString(),session=UUID.randomUUID().toString();var store=new SharedMemoryStore(w);
  var old=new LongTermMemory(UUID.randomUUID().toString(),"项目使用 Java 17",session,now,now,"USER_MEMORY_COMMAND","EXPLICIT_USER",w.toString(),null);store.append(old);store.update(old.memoryId(),w.toString(),"项目使用 Java 18");
  String source="之前规定项目使用 Java 17，现在改成项目使用 Java 21";
  var replacement=new LongTermMemory(UUID.randomUUID().toString(),"项目使用 Java 21",session,now,now,"SEMANTIC_EXTRACTED_USER_QUOTE","EXPLICIT_WORKSPACE_AUTO_POLICY",w.toString(),"CODE:1:"+SessionSummary.digest(source)+":policy=1");
  assertThrows(java.io.IOException.class,()->store.applySemantic(replacement,source,UUID.randomUUID().toString()));assertEquals("项目使用 Java 18",store.read().get(0).content());
 }
}
