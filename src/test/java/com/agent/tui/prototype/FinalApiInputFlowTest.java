package com.agent.tui.prototype;

import com.agent.*;
import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiInputFlowTest {
    @TempDir Path root;
    @Test @Timeout(20) void utf8KeyboardEnterThreeTurnsAndRestartThroughSharedLoop()throws Throwable{
        Path w=FinalApiWorkspace.create(root.resolve("run"),false);var b=new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true);
        var dispatch=new AtomicInteger();var received=new CopyOnWriteArrayList<String>();
        var input=new PipedInputStream();var writer=new PipedOutputStream(input);var output=new ByteArrayOutputStream();
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();String id;var firstId=new AtomicReference<String>();
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,output).build();
            var client=new FinalApiValidationClient(b,request->{
                dispatch.incrementAndGet();var body=new okio.Buffer();request.body().writeTo(body);
                var messages=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.readByteArray()).path("messages");
                String text="";for(var message:messages)if(message.path("role").asText().equals("user"))text=message.path("content").asText();received.add(text);
                return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("offline").body(okhttp3.ResponseBody.create("data: {\"choices\":[{\"delta\":{\"content\":\"中文回复\"}}]}\n\ndata: [DONE]\n\n",okhttp3.MediaType.get("text/event-stream"))).build();
            });var adapter=new FinalApiTuiAdapter(w,client,b,false);var controller=new TuiSessionController(w,true,adapter)){
            terminal.setSize(new Size(100,24));assertFalse(controller.loop().snapshot().sessionFocus());assertFalse(controller.loop().snapshot().chatFocus());
            var keyboard=new Thread(()->{
                try{
                    Thread.sleep(100);
                    write(writer,"你好\r");await(controller,adapter,dispatch,1);
                    firstId.set(controller.loop().snapshot().currentSessionId());
                    write(writer,"hello\r");await(controller,adapter,dispatch,2);
                    writer.write(9);writer.write(9);write(writer,"中文第一X行");
                    write(writer,org.jline.keymap.KeyMap.key(terminal,org.jline.utils.InfoCmp.Capability.key_left));writer.write(127);
                    write(writer,org.jline.keymap.KeyMap.key(terminal,org.jline.utils.InfoCmp.Capability.key_right));
                    writer.write(14);terminal.setSize(new Size(65,18));write(writer,"English second line\r");await(controller,adapter,dispatch,3);
                    write(writer,"/new\r");long until=System.nanoTime()+2_000_000_000L;
                    while(controller.loop().snapshot().currentSessionId().equals(firstId.get())&&System.nanoTime()<until)Thread.sleep(3);
                    assertNotEquals(firstId.get(),controller.loop().snapshot().currentSessionId());
                    write(writer,"新会话中文");writer.write(9);write(writer,org.jline.keymap.KeyMap.key(terminal,org.jline.utils.InfoCmp.Capability.key_down));writer.write(13);writer.flush();
                    until=System.nanoTime()+2_000_000_000L;while(!controller.loop().snapshot().currentSessionId().equals(firstId.get())&&System.nanoTime()<until)Thread.sleep(3);
                    assertEquals(firstId.get(),controller.loop().snapshot().currentSessionId());
                    writer.write(9);write(writer,org.jline.keymap.KeyMap.key(terminal,org.jline.utils.InfoCmp.Capability.key_up));writer.write(13);writer.flush();
                    until=System.nanoTime()+2_000_000_000L;while(controller.loop().snapshot().currentSessionId().equals(firstId.get())&&System.nanoTime()<until)Thread.sleep(3);
                    assertEquals("新会话中文",controller.loop().snapshot().current().input().text());
                    writer.write(13);writer.flush();await(controller,adapter,dispatch,4);
                    write(writer,"fifth\r");Thread.sleep(80);assertEquals(4,dispatch.get());
                }catch(Throwable e){failure.set(e);}
                finally{try{writer.write(17);writer.flush();done.await(6,TimeUnit.SECONDS);}catch(Exception e){failure.compareAndSet(null,e);}}
            });keyboard.start();
            try{TuiSessionsMain.run(terminal,controller);}finally{done.countDown();keyboard.join();}
            assertNull(failure.get(),()->"INPUT_PATH: "+failure.get());
            assertEquals(List.of("你好","hello","中文第一行\nEnglish second line","新会话中文"),received);assertEquals(4,b.state().attempts());
            assertEquals(65,controller.loop().snapshot().width());assertEquals(18,controller.loop().snapshot().height());
            var saved=new CliConversationManager(w).list().get(0);id=saved.id();
            assertEquals(1,saved.snapshot().histories().get(CliMode.READ).stream().filter(m->m.role().equals("assistant")&&!m.content().isBlank()).count());
        }
        try(var client=new FinalApiValidationClient(b,r->{throw new AssertionError("restart must not send");});var a=new FinalApiTuiAdapter(w,client,b,false);var c=new TuiSessionController(w,true,a)){
            assertEquals(id,c.loop().snapshot().currentSessionId());assertEquals(1,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("You")).count());
            assertTrue(c.select(firstId.get()));assertEquals(3,c.loop().snapshot().current().history().stream().filter(m->m.role().equals("You")).count());
            assertFalse(c.loop().snapshot().current().memory());assertEquals(4,b.state().attempts());
        }
    }
    @Test void noConsoleCannotOpenNativeInteractiveScreen(){
        Assumptions.assumeTrue(System.console()==null,"requires headless test executor");
        assertEquals("NATIVE_CONSOLE_REQUIRED",assertThrows(IOException.class,TuiSessionsMain::openNativeTerminal).getMessage());
    }
    @Test void liveFixedPromptGateRemainsInForceWithoutAnyNetwork()throws Exception{
        Path w=FinalApiWorkspace.create(root.resolve("live-config-only"),true);var b=new FinalApiBudget(w.getParent().resolve("evidence/http-ledger"),true);
        try(var client=new FinalApiValidationClient(b,r->{throw new AssertionError("LIVE policy test must not dispatch");});var a=new FinalApiTuiAdapter(w,client,b,true);var c=new TuiSessionController(w,true,a)){
            c.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.TEXT,"你好");assertNull(c.submit());
            assertEquals("你好",c.loop().snapshot().current().input().text());assertTrue(c.loop().snapshot().notice().contains("FINAL_API_FIXED_SCENARIOS_ONLY"));assertEquals(0,b.state().attempts());
        }
    }
    static void write(OutputStream output,String text)throws IOException{output.write(text.getBytes(StandardCharsets.UTF_8));output.flush();}
    static void await(TuiSessionController c,FinalApiTuiAdapter a,AtomicInteger dispatch,int n)throws Exception{
        long deadline=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<deadline){var current=c.loop().snapshot().current();if(dispatch.get()==n&&current.turn()!=null&&!current.turn().running()&&a.available())return;Thread.sleep(3);}
        throw new AssertionError("dispatch="+dispatch+", input="+c.loop().snapshot().current().input().text()+", sessionFocus="+c.loop().snapshot().sessionFocus()+", notice="+c.loop().snapshot().notice());
    }
}
