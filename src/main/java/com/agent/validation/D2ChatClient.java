package com.agent.validation;
import com.agent.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.IOException;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Dedicated fixed nonstreaming verification transport; not the Agent's client factory. */
public final class D2ChatClient implements LLMClient,AutoCloseable {
 private final D2Budget budget;private final String key;private final OkHttpClient http;
 private Integer httpStatus;
 private boolean closed;
 public Integer httpStatus(){return httpStatus;}
 public static final int MAX_INPUT_PAYLOAD_BYTES=4096, MAX_OUTPUT_TOKENS=128;
 public D2ChatClient(D2Budget budget,String key){this(budget,key,null);}
 D2ChatClient(D2Budget budget,String key,okhttp3.Interceptor offlineMock){
  this.budget=budget;this.key=key;
  var builder=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).callTimeout(30,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
    .addInterceptor(chain->{budget.reserve();try{return chain.proceed(chain.request());}catch(IOException|RuntimeException failure){budget.stop(false);throw new IOException("D2_REQUEST_FAILED; stopped",failure);}});
  builder.addNetworkInterceptor(chain->{budget.exchange();var response=chain.proceed(chain.request());httpStatus=response.code();if(!response.isSuccessful()){int status=response.code();response.close();throw new IOException("HTTP_"+status);}return response;});
  if(offlineMock!=null)builder.addInterceptor(chain->{budget.exchange();return offlineMock.intercept(chain);});
  http=builder.build();
 }
 public LLMResponse chat(List<Message> messages)throws IOException{
  synchronized(this){if(closed)throw new IOException("D2_CLIENT_CLOSED");}
  try{
   if(key==null||key.isBlank())throw new IOException("KEY_MISSING");
   ObjectMapper json=new ObjectMapper();
   // Byte cap is a conservative input-size proxy, NOT provider token accounting.
   if(json.writeValueAsBytes(messages).length>MAX_INPUT_PAYLOAD_BYTES||messages.stream().anyMatch(m->!List.of("system","user","assistant").contains(m.role())||!m.toolCalls().isEmpty()||m.toolCallId()!=null))throw new IOException("INPUT_SIZE_OR_ROLE_BUDGET_EXCEEDED");
   var payload=Map.of("model","deepseek-flash","stream",false,"thinking",Map.of("type","disabled"),"max_tokens",MAX_OUTPUT_TOKENS,"messages",messages);
   byte[] wirePayload=json.writeValueAsBytes(payload);
   if(wirePayload.length>MAX_INPUT_PAYLOAD_BYTES)throw new IOException("FINAL_REQUEST_BYTE_LIMIT_EXCEEDED");
   var request=new Request.Builder().url("https://api.deepseek.com/chat/completions").header("Authorization","Bearer "+key).post(RequestBody.create(wirePayload,MediaType.get("application/json"))).build();
   try(var response=http.newCall(request).execute()){
    httpStatus=response.code();
    if(!response.isSuccessful())throw new IOException("HTTP_"+response.code());
    if(response.body()==null)throw new IOException("EMPTY_RESPONSE");
    byte[] bytes=response.body().byteStream().readNBytes(65537);if(bytes.length>65536)throw new IOException("RESPONSE_TOO_LARGE");
    var root=json.readTree(bytes);var choice=root.path("choices");
    if(!choice.isArray()||choice.isEmpty()||!choice.get(0).path("message").path("content").isTextual()||!choice.get(0).path("message").path("tool_calls").isMissingNode())throw new IOException("INVALID_CHAT_RESPONSE");
    String content=choice.get(0).get("message").get("content").asText();if(content.contains(key))throw new IOException("RESPONSE_REDACTED");
    return new LLMResponse(content,List.of(),ProviderTokenUsage.fromJson(root.path("usage")));
   }
  }catch(Exception failed){budget.stop(false);throw new IOException("D2 chat failed; run stopped (no response body logged)");}
 }
 @Override public LLMResponse chat(List<Message> messages,List<ToolDefinition> tools)throws IOException{
  if(tools!=null&&!tools.isEmpty()){budget.stop(false);throw new IOException("D2_TOOLS_DISABLED");}
  return chat(messages);
 }
 @Override public synchronized void close(){
  if(closed)return;
  closed=true;
  // This client owns its builder/dispatcher/pool; never touch process-global TaskRunner/Okio.
  http.dispatcher().cancelAll();
  http.connectionPool().evictAll();
  var executor=http.dispatcher().executorService();executor.shutdown();
  try{if(!executor.awaitTermination(2,TimeUnit.SECONDS)){executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}}
  catch(InterruptedException interrupted){executor.shutdownNow();Thread.currentThread().interrupt();}
 }
}
