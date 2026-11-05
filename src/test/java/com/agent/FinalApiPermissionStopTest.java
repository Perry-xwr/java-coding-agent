package com.agent;

import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import com.agent.tui.runtime.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiPermissionStopTest {
    @TempDir Path root;
    Response reply(Request request,String data){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(data,MediaType.get("text/event-stream"))).build();}
    String call(String name,String args)throws Exception{
        return "data: "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("tool_calls",List.of(Map.of("index",0,"id","call-1","function",Map.of("name",name,"arguments",args)))),"finish_reason","tool_calls"))))+"\n\ndata: [DONE]\n\n";
    }
    void finish(TuiSessionController c,FinalApiTuiAdapter a)throws Exception{
        long end=System.nanoTime()+4_000_000_000L;while((c.loop().snapshot().current().turn().running()||!a.available())&&System.nanoTime()<end){c.pump();Thread.sleep(3);}c.pump();assertTrue(a.available());assertFalse(c.loop().snapshot().current().turn().running());
    }
    @ParameterizedTest @ValueSource(strings={"create_file","run_maven_test","execute_command"})
    void forbiddenToolNeverExecutesOrCausesSecondExchange(String name)throws Exception{
        Path w=FinalApiWorkspace.create(root.resolve(name),true);var b=new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true);var exchanges=new AtomicInteger();var processes=new AtomicInteger();
        String sse=call(name,"{\"path\":\"forbidden.txt\",\"content\":\"bad\"}");
        try(var client=new FinalApiValidationClient(b,r->{assertEquals(1,exchanges.incrementAndGet());return reply(r,sse);});
            var a=new FinalApiTuiAdapter(w,client,b,true,(cmd,dir,timeout,max)->{processes.incrementAndGet();throw new AssertionError("forbidden process");});
            var c=new TuiSessionController(w,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,FinalApiTuiAdapter.SCENARIOS.get(0));assertNotNull(c.submit());finish(c,a);
            var turn=c.loop().snapshot().current().turn();assertEquals("FAILED",turn.status());assertTrue(turn.tools().isEmpty());
            assertTrue(c.loop().snapshot().notice().contains("FINAL_API_PERMISSION_DENIED"));assertEquals("PERMISSION_DENIED",client.stopReason());
            assertEquals(name,client.deniedTool());
            assertEquals(1,exchanges.get());assertEquals(0,processes.get());assertFalse(Files.exists(w.resolve("forbidden.txt")));assertEquals(FinalApiWorkspace.POM,Files.readString(w.resolve("pom.xml")));
            assertEquals(1,b.state().attempts());assertEquals("FAILED",b.state().status());assertFalse(a.canSubmit());
            assertThrows(IOException.class,()->client.chat(List.of(Message.user("no followup"))));assertEquals(1,exchanges.get());
            var saved=new CliConversationManager(w).list().get(0).snapshot();assertFalse(saved.crossSessionMemory().enabled());
            assertTrue(saved.histories().get(CliMode.READ).stream().noneMatch(m->m.role().equals("tool")||!m.toolCalls().isEmpty()));
            assertTrue(saved.histories().get(CliMode.READ).stream().anyMatch(m->m.role().equals("system")&&m.content().startsWith(RealAgentAdapter.FAILED_TURN_MARKER)));
        }
    }
    @ParameterizedTest @ValueSource(strings={"pom.xml","missing.txt","../outside.txt"})
    void readResultsDistinguishOrdinaryFailureFromPermissionDenial(String path)throws Exception{
        Path w=FinalApiWorkspace.create(root.resolve(UUID.randomUUID().toString()),true);var b=new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true);var exchanges=new AtomicInteger();
        String sse=call("read_file","{\"path\":\""+path+"\"}");
        try(var client=new FinalApiValidationClient(b,r->{if(exchanges.incrementAndGet()==1)return reply(r,sse);return reply(r,"data: {\"choices\":[{\"delta\":{\"content\":\"如实报告读取结果。\"}}]}\n\ndata: [DONE]\n\n");});var a=new FinalApiTuiAdapter(w,client,b,true);var c=new TuiSessionController(w,true,a)){
            c.loop().edit(UiEventLoop.Edit.TEXT,FinalApiTuiAdapter.SCENARIOS.get(2));c.submit();finish(c,a);
            if(path.startsWith("..")){assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertEquals(1,exchanges.get());assertEquals("PERMISSION_DENIED",client.stopReason());}
            else{assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(2,exchanges.get());assertEquals("NONE",client.stopReason());assertEquals(path.equals("pom.xml")?"OK":"FAIL",c.loop().snapshot().current().turn().tools().get(0).status());}
        }
    }
    @Test void malformedProtocolHasDifferentFailureReason()throws Exception{
        var b=new FinalApiBudget(root.resolve("protocol"),true);var exchanges=new AtomicInteger();
        try(var client=new FinalApiValidationClient(b,r->{exchanges.incrementAndGet();return reply(r,"data: invalid-json\n\n");})){
            client.enforceLiveReadOnly();assertThrows(IOException.class,()->client.stream(List.of(Message.user("offline")),List.of(),d->{}));assertEquals("PROTOCOL_ERROR",client.stopReason());assertEquals(1,exchanges.get());assertEquals("FAILED",b.state().status());
        }
    }
}
