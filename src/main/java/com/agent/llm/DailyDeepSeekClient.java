package com.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.Proxy;
import javax.net.ssl.SSLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Daily transport ownership and observations only. No API quotas, ledger or FINAL-API policy. */
public final class DailyDeepSeekClient implements StreamingLlmClient,AutoCloseable {
    @FunctionalInterface public interface MockTransport {Response send(Request request)throws IOException;}
    private final OkHttpClient http;private final DeepSeekStreamingClient provider;private final String key;private final boolean offline;
    private final DeepSeekStreamingClient extractionProvider;private final ThreadLocal<Boolean> extraction=ThreadLocal.withInitial(()->false);
    private final AtomicLong extractionAttempts=new AtomicLong(),chatAttempts=new AtomicLong();
    private final AtomicLong attempts=new AtomicLong(),exchanges=new AtomicLong();private volatile boolean closed;
    private volatile String status="NOT_RUN",failureCategory="NONE";private volatile Integer httpStatus;
    public static DailyDeepSeekClient configured(String key){
        if(key==null||key.isBlank())throw new IllegalArgumentException("DEEPSEEK_API_KEY_MISSING");return new DailyDeepSeekClient(key,null);
    }
    public static DailyDeepSeekClient offline(MockTransport transport){return new DailyDeepSeekClient(null,Objects.requireNonNull(transport));}
    private DailyDeepSeekClient(String key,MockTransport transport){
        if(LiveTestControl.enabled())throw new IllegalStateException("DAILY_REFUSES_EXPERIMENT_LIMITS");
        this.key=key;offline=transport!=null;
        var builder=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).connectTimeout(30,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
                .addInterceptor(chain->{
                    if(closed||Thread.currentThread().isInterrupted())throw new IOException("DAILY_CANCELLED");
                    if(extraction.get()){
                        httpStatus=null;status="EXTRACTION_IN_FLIGHT";
                        var bytes=new okio.Buffer();chain.request().body().writeTo(bytes);var json=new ObjectMapper();var payload=json.readTree(bytes.readUtf8());
                        if(payload.has("tools"))throw new IOException("EXTRACTION_TOOLS_FORBIDDEN");
                        ((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("max_tokens",512);
                        byte[] data=json.writeValueAsBytes(payload);if(data.length>8192)throw new IOException("EXTRACTION_INPUT_LIMIT");
                        extractionAttempts.incrementAndGet();
                        attempts.incrementAndGet();Request request=chain.request().newBuilder().post(RequestBody.create(data,MediaType.get("application/json"))).tag(Permit.class,new Permit()).build();
                        Response response=transport!=null?transport.send(request):chain.proceed(request);if(transport!=null)exchanges.incrementAndGet();
                        httpStatus=response.code();if(!response.isSuccessful()){response.close();throw new IOException("EXTRACTION_HTTP_ERROR");}
                        if(response.body()==null)throw new IOException("EXTRACTION_EMPTY_RESPONSE");
                        try(response){var body=response.body().source();body.request(32769);if(body.buffer().size()>32768)throw new IOException("EXTRACTION_OUTPUT_LIMIT");return response.newBuilder().body(ResponseBody.create(body.readUtf8(),MediaType.get("application/json"))).build();}
                    }
                    chatAttempts.incrementAndGet();attempts.incrementAndGet();httpStatus=null;status="IN_FLIGHT";
                    Request request=chain.request().newBuilder().tag(Permit.class,new Permit()).build();
                    Response response;
                    if(transport!=null){exchanges.incrementAndGet();response=transport.send(request);}else response=chain.proceed(request);
                    httpStatus=response.code();if(!response.isSuccessful()){response.close();throw new IOException("DAILY_HTTP_ERROR");}return response;
                }).addNetworkInterceptor(chain->{
                    var permit=chain.request().tag(Permit.class);if(permit==null||!permit.used.compareAndSet(false,true))throw new IOException("DAILY_HTTP_FOLLOWUP_DISABLED");
                    exchanges.incrementAndGet();var response=chain.proceed(chain.request());httpStatus=response.code();
                    if(!response.isSuccessful()){response.close();throw new IOException("DAILY_HTTP_ERROR");}return response;
                });
        if(offline)builder.dns(host->{throw new java.net.UnknownHostException("OFFLINE_TRANSPORT_ONLY");});
        http=builder.build();String endpoint=offline?"https://daily-offline.invalid/chat/completions":"https://api.deepseek.com/chat/completions";provider=new DeepSeekStreamingClient(new OpenAiCompatibleClient(endpoint,"deepseek-flash",key,http,new ObjectMapper(),true));
        extractionProvider=new DeepSeekStreamingClient(new OpenAiCompatibleClient(endpoint,"deepseek-flash",key,http.newBuilder().callTimeout(20,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build(),new ObjectMapper(),true));
    }
    private static final class Permit {final java.util.concurrent.atomic.AtomicBoolean used=new java.util.concurrent.atomic.AtomicBoolean();}
    public boolean offline(){return offline;}public long attempts(){return attempts.get();}public long exchanges(){return exchanges.get();}
    public long extractionAttempts(){return extractionAttempts.get();}public long chatAttempts(){return chatAttempts.get();}
    public LLMResponse extractMemory(List<Message> messages)throws IOException{extraction.set(true);try{return invoke(()->extractionProvider.chat(messages,List.of()));}finally{extraction.remove();}}
    public String lastStatus(){return status;}public Integer httpStatus(){return httpStatus;}public String failureCategory(){return failureCategory;}
    public boolean containsCredential(String text){return key!=null&&text!=null&&text.contains(key);}
    public void cancelActive(){http.dispatcher().cancelAll();}
    public LLMResponse chat(List<Message> m)throws IOException{return chat(m,List.of());}
    public LLMResponse chat(List<Message> m,List<ToolDefinition> t)throws IOException{return invoke(()->provider.chat(m,t));}
    public LLMResponse stream(List<Message> m,List<ToolDefinition> t,LlmStreamListener listener)throws IOException{
        return invoke(()->{
            if(key==null)return provider.stream(m,t,listener);
            var pending=new StringBuilder();var result=provider.stream(m,t,delta->{
                pending.append(delta);if(pending.indexOf(key)>=0)throw new IllegalStateException("SENSITIVE_RESPONSE");
                int safe=Math.max(0,pending.length()-key.length()+1);if(safe>0){listener.onTextDelta(pending.substring(0,safe));pending.delete(0,safe);}
            });check(result);if(!pending.isEmpty())listener.onTextDelta(pending.toString());return result;
        });
    }
    private interface Action{LLMResponse run()throws IOException;}
    private LLMResponse invoke(Action action)throws IOException{
        if(closed)throw new IOException("DAILY_CLIENT_CLOSED");
        try{var result=action.run();check(result);status="SUCCEEDED";failureCategory="NONE";return result;}
        catch(IOException|RuntimeException error){
            status=Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED";
            failureCategory=classifyFailure(error);
            throw new IOException("DAILY_PROVIDER_FAILED: HTTP="+Objects.toString(httpStatus,"UNKNOWN")
                    +"; category="+failureCategory+"; no automatic retry");
        }
    }
    private String classifyFailure(Throwable failure){
        if(status.equals("CANCELLED")||closed)return "CANCELLED";
        if(httpStatus!=null)return "HTTP_STATUS";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof SocketTimeoutException)return "TRANSPORT_TIMEOUT";
            if(cause instanceof UnknownHostException)return "DNS_FAILURE";
            if(cause instanceof SSLException)return "TLS_FAILURE";
        }
        String message=Objects.toString(failure.getMessage(),"");
        if(message.contains("SENSITIVE_RESPONSE"))return "SENSITIVE_RESPONSE_REJECTED";
        if(message.contains("SSE")||message.contains("streaming response")||message.contains("choice"))return "PROTOCOL_OR_RESPONSE_ERROR";
        return "PROVIDER_IO_ERROR";
    }
    private void check(LLMResponse response)throws IOException{
        if(key!=null&&(Objects.toString(response.content(),"").contains(key)||response.toolCalls().stream().anyMatch(t->(t.id()+t.name()+t.arguments()).contains(key))))throw new IOException("SENSITIVE_RESPONSE");
    }
    public void close(){closed=true;cancelActive();http.connectionPool().evictAll();var executor=http.dispatcher().executorService();executor.shutdownNow();
        try{executor.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}}
}
