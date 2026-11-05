package com.agent;
import com.agent.validation.*;
import com.agent.*;
import com.agent.llm.*;
import com.agent.memory.*;
import com.agent.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Fixed three-condition validation only. Not an alternative Agent CLI or tool executor. */
public final class D2MemoryMain {
 public static void main(String[] args)throws Exception{
  if(args.length<1)throw new IllegalArgumentException("Explicit D2 experiment directory required");
  Path root=Path.of(args[0]).toAbsolutePath().normalize();
  Path allowed=Path.of("D:/Development/agent-cli/benchmark-runs").toAbsolutePath().normalize();
  if(!root.startsWith(allowed)||!root.getFileName().toString().startsWith("memory-d2-"))throw new IOException("D2 isolation path rejected");
  boolean authorized=List.of(args).contains("--authorize-real")&&List.of(args).contains("--balance-confirmed");
  var budget=new D2Budget(root,authorized);
  System.out.println("D2_WORKSPACE="+root.resolve("workspace"));System.out.println("D2_BUDGET="+budget.state());
  System.out.println("MODEL=deepseek-flash; DIRECT=true; EMBEDDING=false; LLM_EXTRACTION=false; LLM_CONFLICT=false");
  System.out.println("INPUT_MODE=BYTE_LIMITED_INPUT (explicit opt-in required); MAX_REQUEST_UTF8_BYTES=4096; MAX_OUTPUT_TOKENS=128; NOT_AN_EXACT_TOKEN_LIMIT");
  if(!authorized){System.out.println("PREFLIGHT_ONLY; REAL_HTTP_REQUESTS=0; explicit authorization and user balance confirmation required");return;}
  budget.assertFresh();
  if(!byteLimitedInputSelected(args)){System.out.println("BYTE_LIMITED_INPUT_NOT_SELECTED; REAL_HTTP_REQUESTS=0");return;}
  String key=System.getenv("DEEPSEEK_API_KEY");
  if(key==null||key.isBlank()){budget.stop(false);System.out.println("KEY_MISSING; no requests");return;}
  Path workspace=root.resolve("workspace");
  if(Files.exists(workspace)){budget.stop(false);throw new IOException("Existing D2 workspace: no replay/overwrite");}
  Files.createDirectory(workspace);
  var client=new D2ChatClient(budget,key);
  try{runScenario(workspace,client,root);budget.stop(true);System.out.println("D2_COMPLETED; "+budget.state());}
  catch(Exception failure){budget.stop(false);System.out.println("D2_FAILED_STOPPED; "+budget.state()+"; HTTP_STATUS="+(client.httpStatus()==null?"UNKNOWN":client.httpStatus())+"; no retry or raw error body");}
  finally{client.close();}
 }
 public static boolean byteLimitedInputSelected(String[] args){return List.of(args).contains("--input-mode=BYTE_LIMITED_INPUT");}
 public static void runScenario(Path workspace,LLMClient boundedClient,Path evidence)throws Exception{
  Files.createDirectories(workspace);Files.createDirectories(evidence);
  // Local CLI management cannot obtain the network client, tools, embeddings or paid extractors.
  LLMClient denied=messages->{throw new IOException("Local-only D2 management: model calls denied");};
  var sessions=CliAgentFactory.createProfiles(denied,workspace,AgentEventListener.NO_OP);
  var cli=new InteractiveCli(sessions,new ConsoleUi(new PrintStream(OutputStream.nullOutputStream()),"LOCAL_ONLY","none"),workspace);
  local(cli,"/new\n/memory global on\n/memory remember 项目统一使用 Java 17。\n/new\n/exit\n");
  var manager=new CliConversationManager(workspace);
  String a=manager.list().stream().filter(c->c.snapshot().crossSessionMemory().enabled()).findFirst().orElseThrow().id();
  String b=manager.list().stream().filter(c->!c.id().equals(a)).findFirst().orElseThrow().id();
  String memoryId=new SharedMemoryStore(workspace).read().get(0).memoryId();
  for(int stage=0;stage<3;stage++){
   if(stage==1)local(cli,"/memory global on\n/exit\n");
   if(stage==2)local(cli,"/memory update "+memoryId+" 项目统一使用 Java 21。\n/exit\n");
   var snapshot=manager.load(b).snapshot();
   CrossSessionState state=snapshot.crossSessionMemory();
   var shared=new LocalCrossSessionMemory(new SharedMemoryStore(workspace),state::enabled,workspace);
   String question="请问项目使用什么 Java 版本？";
   var raw=List.of(Message.system("Answer the current question using authorized quoted project context when present. Do not infer facts from missing context."),Message.user(question));
   var selected=RequestContextSelector.selectSession(raw,1,2,List.of(),new SessionRequestContext(b,CliMode.CHAT,SessionSummary.empty(),new SessionContextConfig(true,8192,3),state,shared.retrieveAuthorized(b,question)));
   String block=selected.messages().stream().filter(m->m.content().startsWith("CROSS_SESSION_MEMORY_DATA")).map(Message::content).collect(java.util.stream.Collectors.joining());
   boolean correct=stage==0?block.isEmpty():stage==1?block.contains("Java 17"):block.contains("Java 21")&&!block.contains("Java 17");
   if(!correct)throw new IOException("Context gate failed BEFORE request");
   var response=boundedClient.chat(selected.messages(),List.of());
   var record=new LinkedHashMap<String,Object>();
   record.put("condition",List.of("GLOBAL_OFF","GLOBAL_ON_JAVA17","GLOBAL_ON_JAVA21").get(stage));
   record.put("provider","DEEPSEEK");record.put("model","deepseek-flash");record.put("endpoint","https://api.deepseek.com/chat/completions");
   record.put("applicationSequence",stage+1);record.put("maxOutputTokens",D2ChatClient.MAX_OUTPUT_TOKENS);
   record.put("messageCount",selected.messages().size());record.put("inputPayloadBytes",new ObjectMapper().writeValueAsBytes(selected.messages()).length);
   record.put("inputTokenEstimate",RequestContextSelector.estimateTokens(selected.messages(),List.of()));
   record.put("sessionA",a);record.put("sessionB",b);record.put("sharedInjected",!block.isEmpty());
   record.put("sharedContainsJava17",block.contains("Java 17"));record.put("sharedContainsJava21",block.contains("Java 21"));
   record.put("selectedMemoryIds",selected.metrics().sharedMemories().stream().map(SharedMemoryHit.Trace::memoryId).toList());
   record.put("contextSha256",SessionSummary.digest(new ObjectMapper().writeValueAsString(selected.messages())));
   record.put("answer",response.content());record.put("usage",response.providerUsage());record.put("currencyCost","UNKNOWN");
   Files.write(evidence.resolve("condition-"+stage+".json"),new ObjectMapper().writeValueAsBytes(record),StandardOpenOption.CREATE_NEW);
   System.out.println("CONDITION="+record.get("condition")+"; contextGate=PASS; usage="+response.providerUsage()+"; currencyCost=UNKNOWN");
  }
 }
 private static void local(InteractiveCli cli,String commands)throws IOException{cli.run(new BufferedReader(new StringReader(commands)));}
}
