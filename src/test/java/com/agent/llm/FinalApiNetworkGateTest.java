package com.agent.llm;

import com.agent.validation.FinalApiBudget;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiNetworkGateTest {
    @TempDir Path root;
    @Test void syntheticKeyEchoIsRejectedAcrossDeltaBoundariesWithoutExposure()throws Exception{
        String key="FICTIONAL-KEY-NOT-A-CREDENTIAL";var constructor=FinalApiValidationClient.class.getDeclaredConstructor(FinalApiBudget.class,FinalApiValidationClient.MockTransport.class,String.class);constructor.setAccessible(true);
        var output=new StringBuilder();FinalApiValidationClient.MockTransport transport=r->{
            String s="data: {\"choices\":[{\"delta\":{\"content\":\"FICTIONAL-KEY-\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"NOT-A-CREDENTIAL\"}}]}\n\ndata: [DONE]\n\n";
            return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(s,MediaType.get("text/event-stream"))).build();
        };
        var b=new FinalApiBudget(root.resolve("secret-echo"),true);
        try(var client=constructor.newInstance(b,transport,key)){
            var failure=assertThrows(IOException.class,()->client.stream(List.of(Message.user("offline")),List.of(),output::append));
            assertFalse(output.toString().contains(key));assertFalse(failure.toString().contains(key));assertEquals("FAILED",b.state().status());
        }
        var safeOutput=new StringBuilder();FinalApiValidationClient.MockTransport safe=r->new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create("data: {\"choices\":[{\"delta\":{\"content\":\"中文短回复\"}}]}\n\ndata: [DONE]\n\n",MediaType.get("text/event-stream"))).build();
        try(var client=constructor.newInstance(new FinalApiBudget(root.resolve("safe-echo"),true),safe,key)){
            assertEquals("中文短回复",client.stream(List.of(Message.user("offline")),List.of(),safeOutput::append).content());assertEquals("中文短回复",safeOutput.toString());
        }
        FinalApiValidationClient.MockTransport nameEcho=r->{
            String s="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"echo\",\"function\":{\"name\":\""+key+"\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n";
            return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(s,MediaType.get("text/event-stream"))).build();
        };
        try(var client=constructor.newInstance(new FinalApiBudget(root.resolve("name-echo"),true),nameEcho,key)){
            client.enforceLiveReadOnly();var error=assertThrows(IOException.class,()->client.stream(List.of(Message.user("offline")),List.of(),d->{}));
            assertFalse(error.toString().contains(key));assertEquals("",client.deniedTool());
        }
    }
    @Test void actualNetworkInterceptorRejectsFollowupAndMissingPermitWithoutSockets()throws Exception{
        var request=new AtomicReference<Request>();
        try(var client=new FinalApiValidationClient(new FinalApiBudget(root,true),r->{request.set(r);return reply(r,200);})){
            client.chat(List.of(Message.user("offline")));
            var field=FinalApiValidationClient.class.getDeclaredField("http");field.setAccessible(true);
            var http=(OkHttpClient)field.get(client);var gate=http.networkInterceptors().get(0);var sent=new AtomicInteger();
            gate.intercept(new Chain(request.get(),sent,200)).close();assertEquals(1,sent.get());
            assertThrows(IOException.class,()->gate.intercept(new Chain(request.get(),sent,200)));assertEquals(1,sent.get());
            assertThrows(IOException.class,()->gate.intercept(new Chain(new Request.Builder().url("https://offline.invalid").build(),sent,200)));assertEquals(1,sent.get());
            // A fresh reserved request with retryable HTTP status is thrown before retry machinery sees it.
            client.chat(List.of(Message.user("next offline")));
            assertThrows(IOException.class,()->gate.intercept(new Chain(request.get(),sent,503)));assertEquals(2,sent.get());
            assertFalse(http.retryOnConnectionFailure());assertFalse(http.followRedirects());assertFalse(http.followSslRedirects());
        }
    }
    static Response reply(Request r,int code){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("offline").body(ResponseBody.create("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",MediaType.get("application/json"))).build();}
    record Chain(Request request,AtomicInteger sent,int status) implements Interceptor.Chain {
        public Response proceed(Request r){sent.incrementAndGet();return reply(r,status);}
        public Connection connection(){return null;}
        public Call call(){return null;}
        public int connectTimeoutMillis(){return 1000;}public Interceptor.Chain withConnectTimeout(int t,TimeUnit u){return this;}
        public int readTimeoutMillis(){return 1000;}public Interceptor.Chain withReadTimeout(int t,TimeUnit u){return this;}
        public int writeTimeoutMillis(){return 1000;}public Interceptor.Chain withWriteTimeout(int t,TimeUnit u){return this;}
    }
}
