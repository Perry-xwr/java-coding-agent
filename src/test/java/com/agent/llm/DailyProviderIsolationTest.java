package com.agent.llm;

import com.agent.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyProviderIsolationTest {
    @TempDir Path root;
    @Test void retryDisabledNoQuotaAndSyntheticKeyNeverEntersReplyOrSession()throws Exception{
        String key="FICTIONAL_DAILY_KEY_NOT_REAL";var constructor=DailyDeepSeekClient.class.getDeclaredConstructor(String.class,DailyDeepSeekClient.MockTransport.class);constructor.setAccessible(true);
        DailyDeepSeekClient.MockTransport fake=r->new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create("data: {\"choices\":[{\"delta\":{\"content\":\""+key+"\"}}]}\n\ndata: [DONE]\n\n",MediaType.get("text/event-stream"))).build();
        try(var client=constructor.newInstance(key,fake)){
            var field=DailyDeepSeekClient.class.getDeclaredField("http");field.setAccessible(true);var http=(OkHttpClient)field.get(client);
            assertFalse(http.retryOnConnectionFailure());assertFalse(http.followRedirects());assertFalse(http.followSslRedirects());
            var output=new StringBuilder();var error=assertThrows(IOException.class,()->client.stream(List.of(Message.user("safe")),List.of(),output::append));assertFalse(error.toString().contains(key));assertFalse(output.toString().contains(key));
            Path w=Files.createDirectory(root.resolve("workspace"));try(var lease=DailyWorkspace.acquire(w);var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
                c.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.TEXT,"This is "+key);assertNull(c.submit());assertTrue(new CliConversationManager(w).list().isEmpty());assertEquals(1,client.attempts());
            }
        }
    }
}
