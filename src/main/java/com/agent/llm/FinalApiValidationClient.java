package com.agent.llm;

import com.agent.validation.FinalApiBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import java.io.IOException;
import java.net.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Dedicated validation transport. No CLI entry enables online mode in P0.
 * MockTransport is a trusted test seam, not a runtime/user-configurable provider. */
public final class FinalApiValidationClient implements StreamingLlmClient,AutoCloseable {
    @FunctionalInterface public interface MockTransport { Response send(Request request)throws IOException; }
    private final FinalApiBudget budget;private final OkHttpClient http;private final DeepSeekStreamingClient provider;private final String sensitiveKey;
    private volatile boolean closed,stopped;
    private volatile boolean liveReadOnly;
    private volatile String stopReason="NONE";
    private volatile String deniedTool="";
    public static final class PermissionDenied extends IOException {
        private final String tool;
        public PermissionDenied(String tool){super("FINAL_API_PERMISSION_DENIED: "+safeTool(tool));this.tool=safeTool(tool);}
        private static String safeTool(String tool){return tool.matches("[A-Za-z0-9_]{1,64}")?tool:"UNRECOGNIZED_TOOL";}
    }
    /** Only the dedicated LIVE adapter enables this, before starting any turn. */
    public void enforceLiveReadOnly(){liveReadOnly=true;}
    public String stopReason(){return stopReason;}
    public String deniedTool(){return deniedTool;}
    public void denyRuntimePermission(String tool)throws IOException{stopped=true;stopReason="PERMISSION_DENIED";deniedTool=new PermissionDenied(tool).tool;budget.finish(false);}
    public FinalApiValidationClient(FinalApiBudget budget,MockTransport transport){this(budget,Objects.requireNonNull(transport),null);}
    /** Explicit library boundary for future P1 wiring; never called by a P0 entry/script. */
    public static FinalApiValidationClient authorized(FinalApiBudget budget,String key,boolean userConfirmed)throws IOException{
        if(!userConfirmed||!budget.authorized()||key==null||key.isBlank())throw new IOException("FINAL_API_NOT_AUTHORIZED_OR_KEY_MISSING");
        return new FinalApiValidationClient(budget,null,key);
    }
    private FinalApiValidationClient(FinalApiBudget budget,MockTransport transport,String key){
        this.budget=Objects.requireNonNull(budget);
        this.sensitiveKey=key;
        var builder=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .callTimeout(30,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
                .addInterceptor(chain->{
                    if(stopped||closed||Thread.currentThread().isInterrupted())throw new IOException("FINAL_API_CANCELLED");
                    Request request=chain.request();Buffer body=new Buffer();request.body().writeTo(body);
                    if(body.size()>32768)throw new IOException("FINAL_API_REQUEST_BYTES_EXCEEDED");
                    var payload=new ObjectMapper().readTree(body.readByteArray());
                    if(payload.has("tools"))for(var tool:payload.path("tools"))
                        if(!List.of("read_file","find_files","search_code","list_files").contains(tool.path("function").path("name").asText()))throw new IOException("FINAL_API_TOOLS_DENIED");
                    // Bound the exact wire body, including history, Memory and tool definitions.
                    ((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("max_tokens",256);
                    byte[] wire=new ObjectMapper().writeValueAsBytes(payload);
                    if(wire.length>32768)throw new IOException("FINAL_API_REQUEST_BYTES_EXCEEDED");
                    request=request.newBuilder().post(RequestBody.create(wire,MediaType.get("application/json")))
                            .tag(ExchangePermit.class,new ExchangePermit()).build();
                    budget.reserve(); // Atomic durable reservation BEFORE dispatch, failures also count.
                    Response response=transport!=null?transport.send(request):chain.proceed(request);
                    if(!response.isSuccessful()){int status=response.code();response.close();throw new IOException("FINAL_API_HTTP_"+status);}
                    return response;
                }).addNetworkInterceptor(chain->{
                    var permit=chain.request().tag(ExchangePermit.class);
                    if(permit==null||!permit.used.compareAndSet(false,true))throw new IOException("FINAL_API_HTTP_FOLLOWUP_BLOCKED");
                    Response response=chain.proceed(chain.request());
                    // Reject retryable status responses before OkHttp's follow-up machinery sees them.
                    if(!response.isSuccessful()){int status=response.code();response.close();throw new IOException("FINAL_API_HTTP_"+status);}
                    return response;
                });
        if(transport!=null)builder.dns(host->{throw new java.net.UnknownHostException("P0_NETWORK_DISABLED");});
        http=builder.build();
        provider=new DeepSeekStreamingClient(new OpenAiCompatibleClient(transport==null?"https://api.deepseek.com/chat/completions":"https://final-api.invalid/chat/completions","deepseek-flash",key,http,new ObjectMapper(),true));
    }
    private static final class ExchangePermit {final java.util.concurrent.atomic.AtomicBoolean used=new java.util.concurrent.atomic.AtomicBoolean();}
    public LLMResponse chat(List<Message> m)throws IOException{return chat(m,List.of());}
    public LLMResponse chat(List<Message> m,List<ToolDefinition> t)throws IOException{return invoke(()->provider.chat(m,t));}
    public LLMResponse stream(List<Message> m,List<ToolDefinition> t,LlmStreamListener l)throws IOException{
        if(sensitiveKey==null)return invoke(()->provider.stream(m,t,l));
        return invoke(()->{
            StringBuilder pending=new StringBuilder();
            var result=provider.stream(m,t,delta->{pending.append(delta);
                if(pending.indexOf(sensitiveKey)>=0)throw new IllegalStateException("SENSITIVE_PROVIDER_RESPONSE");
                int safe=Math.max(0,pending.length()-sensitiveKey.length()+1);
                if(safe>0){l.onTextDelta(pending.substring(0,safe));pending.delete(0,safe);}
            });
            rejectSensitive(result);if(!pending.isEmpty())l.onTextDelta(pending.toString());return result;
        });
    }
    private interface RequestAction {LLMResponse run()throws IOException;}
    private LLMResponse invoke(RequestAction action)throws IOException{
        if(closed||stopped){if(stopReason.equals("PERMISSION_DENIED"))throw new PermissionDenied("RUNTIME_PERMISSION_DENIAL");throw new IOException("FINAL_API_CLIENT_STOPPED: "+stopReason);}
        try{
            var result=action.run();rejectSensitive(result);
            if(liveReadOnly)for(var call:result.toolCalls())if(!List.of("read_file","find_files","search_code","list_files").contains(call.name()))throw new PermissionDenied(call.name());
            return result;
        }catch(IOException|RuntimeException failure){
            stopped=true;
            if(failure instanceof PermissionDenied denied)deniedTool=denied.tool;
            stopReason=failure instanceof PermissionDenied?"PERMISSION_DENIED":Thread.currentThread().isInterrupted()?"CANCELLED":protocolFailure(failure)?"PROTOCOL_ERROR":"REQUEST_FAILED";
            if(!(failure instanceof FinalApiBudget.ConfirmationChanged))budget.finish(false);
            if(failure instanceof PermissionDenied denied)throw denied;
            throw new IOException("FINAL_API_STOPPED: "+stopReason+" / "+failure.getClass().getSimpleName());
        }
    }
    private static boolean protocolFailure(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)return true;
            String message=Objects.toString(cause.getMessage(),"");
            if(message.contains("streaming event")||message.contains("streaming response")||message.contains("tool-call")||message.contains("tool call"))return true;
        }
        return false;
    }
    private void rejectSensitive(LLMResponse result)throws IOException{
        if(sensitiveKey!=null&&(result.content().contains(sensitiveKey)||result.toolCalls().stream().anyMatch(t->t.arguments().contains(sensitiveKey)||t.name().contains(sensitiveKey)||t.id().contains(sensitiveKey))))throw new IOException("SENSITIVE_PROVIDER_RESPONSE");
    }
    public void cancelRequests()throws IOException{stopped=true;if(stopReason.equals("NONE"))stopReason="CANCELLED";http.dispatcher().cancelAll();budget.finish(false);}
    public boolean automaticRetryEnabled(){return http.retryOnConnectionFailure();}
    public boolean redirectsEnabled(){return http.followRedirects()||http.followSslRedirects();}
    public void close(){
        closed=true;http.dispatcher().cancelAll();http.connectionPool().evictAll();var executor=http.dispatcher().executorService();executor.shutdownNow();
        try{executor.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
