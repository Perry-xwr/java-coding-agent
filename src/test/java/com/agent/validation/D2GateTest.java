package com.agent.validation;
import com.agent.D2MemoryMain;
import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import okhttp3.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class D2GateTest {
 @TempDir Path root;
 Response response(Interceptor.Chain chain,int status,String body){return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("offline mock").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
 @Test void unauthorizedNeverReachesMockHttp()throws Exception{
  int[] sent={0};var gate=new D2Budget(root,false);var client=new D2ChatClient(gate,"fictional-key",chain->{sent[0]++;return response(chain,200,"{}");});
  try{assertThrows(IOException.class,()->client.chat(List.of(Message.user("test"))));assertEquals(0,sent[0]);assertEquals(0,gate.state().attempts());}finally{client.close();}
 }
 @Test void threeCallsThenRestartAndNewClientCannotBypass()throws Exception{
  var gate=new D2Budget(root,true);int[] sent={0};
  var client=new D2ChatClient(gate,"fictional-key",chain->{sent[0]++;return response(chain,200,"{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");});
  try{for(int i=0;i<3;i++)assertFalse(client.chat(List.of(Message.user("test"))).providerUsage().usageAvailable());
   var restarted=new D2Budget(root,true);assertThrows(IOException.class,restarted::assertFresh);
   assertThrows(IOException.class,()->client.chat(List.of(Message.user("fourth"))));assertEquals(3,sent[0]);assertEquals(3,restarted.state().attempts());
  }finally{client.close();}
 }
 @Test void apiErrorsTimeoutAndRedirectionStopWithoutRetry()throws Exception{
  for(int status:List.of(401,402,429,408,503,307)){
   var gate=new D2Budget(root.resolve("s"+status),true);int[] sent={0};var client=new D2ChatClient(gate,"fictional-secret",chain->{sent[0]++;return response(chain,status,"sensitive body must not appear");});
   try{var error=assertThrows(IOException.class,()->client.chat(List.of(Message.user("test"))));assertFalse(error.toString().contains("fictional-secret"));assertFalse(error.toString().contains("sensitive body"));assertThrows(IOException.class,()->client.chat(List.of(Message.user("again"))));assertEquals(1,sent[0]);}finally{client.close();}
  }
  var gate=new D2Budget(root.resolve("timeout"),true);var client=new D2ChatClient(gate,"fictional",chain->{throw new java.net.SocketTimeoutException();});
  try{assertThrows(IOException.class,()->client.chat(List.of(Message.user("test"))));assertEquals("FAILED",gate.state().status());assertEquals(1,gate.state().attempts());}finally{client.close();}
 }
 @Test void fixedScenarioUsesThreeBoundedChatsAndManagementIsFree()throws Exception{
  List<List<Message>> seen=new ArrayList<>();LLMClient fake=messages->{seen.add(List.copyOf(messages));return new LLMResponse("FAKE",List.of());};
  D2MemoryMain.runScenario(root.resolve("workspace"),fake,root.resolve("evidence"));
  assertEquals(3,seen.size());assertFalse(shared(seen.get(0)).contains("Java"));assertTrue(shared(seen.get(1)).contains("Java 17"));assertTrue(shared(seen.get(2)).contains("Java 21"));assertFalse(shared(seen.get(2)).contains("Java 17"));
  String record=Files.readString(root.resolve("evidence/condition-2.json"));assertTrue(record.contains("UNKNOWN"));assertFalse(record.contains("Bearer"));
 }
 @Test void lostOrDamagedLedgerFailsClosed()throws Exception{
  new D2Budget(root,true);Files.delete(root.resolve("budget.json"));assertThrows(IOException.class,()->new D2Budget(root,true));
 }
 @Test void generationParametersAreFixedAndZeroUsageDiffersFromUnknown()throws Exception{
  var gate=new D2Budget(root,true);var client=new D2ChatClient(gate,"fictional-key",chain->{
   var buffer=new okio.Buffer();chain.request().body().writeTo(buffer);var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(buffer.readUtf8());
   assertEquals(128,payload.path("max_tokens").asInt());assertEquals("disabled",payload.path("thinking").path("type").asText());assertFalse(payload.has("tools"));assertEquals("deepseek-flash",payload.path("model").asText());
   return response(chain,200,"{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}");
  });
  try{var r=client.chat(List.of(Message.user("test")));assertTrue(r.providerUsage().usageAvailable());assertEquals(0,r.providerUsage().totalTokens());}finally{client.close();}
 }
 @Test void explicitByteModeIsRequiredSeparatelyFromRealAuthorization(){
  assertFalse(D2MemoryMain.byteLimitedInputSelected(new String[]{"--authorize-real","--balance-confirmed"}));
  assertTrue(D2MemoryMain.byteLimitedInputSelected(new String[]{"--input-mode=BYTE_LIMITED_INPUT"}));
 }
 @Test void closeWaitsForOwnedExecutorAndDoesNotCloseAnotherClient()throws Exception{
  var gate=new D2Budget(root,true);var first=new D2ChatClient(gate,"fictional",chain->response(chain,200,"{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
  var second=new D2ChatClient(gate,"fictional",chain->response(chain,200,"{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
  var field=D2ChatClient.class.getDeclaredField("http");field.setAccessible(true);var http=(OkHttpClient)field.get(first);
  http.dispatcher().executorService().submit(()->{}).get();
  first.close();first.close();assertTrue(http.dispatcher().executorService().isTerminated());assertEquals(0,http.connectionPool().connectionCount());
  assertThrows(IOException.class,()->first.chat(List.of(Message.user("after close"))));assertEquals(0,gate.state().attempts());
  try{assertEquals("OK",second.chat(List.of(Message.user("other owner"))).content());}finally{second.close();}
 }
 @Test void allRolesMemoryAndFinalEnvelopeCountBeforeAnyDispatch()throws Exception{
  for(List<Message> messages:List.of(
    List.of(Message.system("x".repeat(4100)),Message.user("q")),
    List.of(Message.system("s"),Message.assistant("旧历史".repeat(600)),Message.user("q")),
    List.of(Message.system("s"),Message.user("CROSS_SESSION_MEMORY_DATA:"+"记忆".repeat(700)),Message.user("q")),
    List.of(Message.assistant("",List.of(new ToolCall("tool-id","create_file","{}")))),
    List.of(Message.user("x".repeat(4000))) // messages alone fit; final model/parameter envelope does not.
  )){
   Path run=root.resolve(UUID.randomUUID().toString());var gate=new D2Budget(run,true);int[] dispatched={0};
   var client=new D2ChatClient(gate,"fictional",chain->{dispatched[0]++;return response(chain,200,"{}");});
   try{assertThrows(IOException.class,()->client.chat(messages));assertEquals(0,dispatched[0]);assertEquals(0,gate.state().attempts());}finally{client.close();}
  }
 }
 String shared(List<Message> messages){return messages.stream().filter(m->m.content().startsWith("CROSS_SESSION_MEMORY_DATA")).map(Message::content).collect(java.util.stream.Collectors.joining());}
}
