package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Tui3TerminalFlowTest {
    @TempDir Path root;
    @Test @Timeout(20) void realAgentReadStreamDisplayAndExit()throws Exception{
        Path w=TuiSessionWorkspace.create(root.resolve("isolated"));Files.writeString(w.resolve("demo.txt"),"中文只读示例");
        var input=new PipedInputStream();var sender=new PipedOutputStream(input);var output=new ByteArrayOutputStream();
        try(var terminal=TerminalBuilder.builder().system(false).encoding(java.nio.charset.StandardCharsets.UTF_8).type("xterm").streams(input,output).build();
            var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            terminal.setSize(new Size(110,26));var done=new CountDownLatch(1);var sent=new java.util.concurrent.atomic.AtomicBoolean();
            var writer=new Thread(()->{try{
                Thread.sleep(150);sender.write("解释 demo.txt，不要修改文件。\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));sender.flush();
                long end=System.nanoTime()+6_000_000_000L;
                while(System.nanoTime()<end){var turn=c.loop().snapshot().current().turn();if(turn!=null&&!turn.running())break;Thread.sleep(10);}
                sender.write(17);sender.flush();sent.set(true);done.await(8,TimeUnit.SECONDS);
            }catch(Exception failure){throw new RuntimeException(failure);}});
            writer.start();try{TuiSessionsMain.run(terminal,c);}finally{done.countDown();writer.join();}
            assertTrue(sent.get());var saved=new CliConversationManager(w).list();assertEquals(1,saved.size());
            var history=saved.get(0).snapshot().histories().get(CliMode.CODE);
            assertEquals("解释 demo.txt，不要修改文件。",history.stream().filter(m->m.role().equals("user")).findFirst().orElseThrow().content());
            assertEquals(1,history.stream().filter(m->m.role().equals("tool")).count());
            assertEquals(1,history.stream().filter(m->m.role().equals("assistant")&&!m.content().isBlank()).count());
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
            assertTrue(output.size()>0);
        }
    }
}
