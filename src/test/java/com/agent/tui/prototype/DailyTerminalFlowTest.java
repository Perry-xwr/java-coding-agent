package com.agent.tui.prototype;

import com.agent.*;
import com.agent.llm.*;
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

class DailyTerminalFlowTest {
    @TempDir Path root;
    @Test @Timeout(20) void dailyKeyboardUsesSameLoopAndPersistsRealCoreMockHttp()throws Throwable{
        Path w=Files.createDirectory(root.resolve("project"));var received=new CopyOnWriteArrayList<String>();
        var input=new PipedInputStream();var writer=new PipedOutputStream(input);var output=new ByteArrayOutputStream();var done=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,output).build();var lease=DailyWorkspace.acquire(w);
            var client=DailyDeepSeekClient.offline(r->{var buffer=new okio.Buffer();r.body().writeTo(buffer);var messages=new com.fasterxml.jackson.databind.ObjectMapper().readTree(buffer.readByteArray()).path("messages");String last="";for(var m:messages)if(m.path("role").asText().equals("user"))last=m.path("content").asText();received.add(last);
                return new okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("offline").body(okhttp3.ResponseBody.create("data: {\"choices\":[{\"delta\":{\"content\":\"中文\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"回复\"}}]}\n\ndata: [DONE]\n\n",okhttp3.MediaType.get("text/event-stream"))).build();});
            var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            terminal.setSize(new Size(100,25));assertFalse(c.loop().snapshot().sessionFocus());
            var keyboard=new Thread(()->{try{
                Thread.sleep(100);write(writer,"\u001b[A\u001b[B\u001b[C\u001b[D\u001bOA\u001bOB\u001bOC\u001bOD".repeat(20));
                write(writer,"\u001b");Thread.sleep(200);write(writer,"[A");
                write(writer,"\u001b[M`((你好\u001b[Ma((\r");waitFor(c,a,received,1);
                write(writer,"\u001b[<35;70;8M".repeat(100)+"\u001b[<64;70;8M\u001b[<0;5;6M\u001b[<0;5;6m");Thread.sleep(50);
                writer.write(9);writer.write(9);write(writer,"解释 Java");writer.write(14);terminal.setSize(new Size(70,20));write(writer,"不要修改文件");write(writer,"\u001b[A\u001b[B\u001b[C\u001b[D");write(writer,"\r");waitFor(c,a,received,2);
                write(writer,"\u001b[5~\u001b[6~/status\r");Thread.sleep(60);assertEquals(2,client.attempts());
            }catch(Throwable failure){error.set(failure);}finally{try{writer.write(17);writer.flush();done.await(5,TimeUnit.SECONDS);}catch(Exception failure){error.compareAndSet(null,failure);}}});keyboard.start();
            try{TuiSessionsMain.run(terminal,c);}finally{done.countDown();keyboard.join();}assertNull(error.get());
            assertEquals(List.of("你好","解释 Java\n不要修改文件"),received);assertEquals(70,c.loop().snapshot().width());
            var saved=new CliConversationManager(w).list().get(0);assertEquals(2,saved.snapshot().histories().get(CliMode.CODE).stream().filter(m->m.role().equals("assistant")&&!m.content().isBlank()).count());assertFalse(saved.snapshot().crossSessionMemory().enabled());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\u001b[?1000l"));
        }
    }
    static void write(OutputStream stream,String value)throws IOException{stream.write(value.getBytes(StandardCharsets.UTF_8));stream.flush();}
    static void waitFor(TuiSessionController c,DailyAgentAdapter a,List<String> requests,int expected)throws Exception{
        long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){var turn=c.loop().snapshot().current().turn();if(requests.size()==expected&&turn!=null&&!turn.running()&&a.available())return;Thread.sleep(3);}throw new AssertionError("Daily input did not complete requests="+requests+" draft="+c.loop().snapshot().current().input().text()+" turn="+c.loop().snapshot().current().turn());
    }
}
