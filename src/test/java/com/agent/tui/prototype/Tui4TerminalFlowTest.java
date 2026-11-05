package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class Tui4TerminalFlowTest {
    @TempDir Path root;
    @Test @Timeout(20) void keyboardMemoryCommandsAreLocalAndDenialIsExplicit()throws Exception{
        Path w=TuiSessionWorkspace.create(root.resolve("workspace"));Files.writeString(w.resolve("demo.txt"),"demo");
        var input=new PipedInputStream();var sender=new PipedOutputStream(input);var bytes=new ByteArrayOutputStream();
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,bytes).build();
            var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            terminal.setSize(new Size(120,28));var done=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
            var keyboard=new Thread(()->{try{
                Thread.sleep(100);
                for(String line:java.util.List.of("/new","/memory global on","/memory auto on","项目使用 Java 17。请解释约定，不要修改文件。")){sender.write((line+"\r").getBytes(StandardCharsets.UTF_8));sender.flush();Thread.sleep(90);}
                long end=System.nanoTime()+7_000_000_000L;
                while(System.nanoTime()<end){var turn=c.loop().snapshot().current().turn();if(turn!=null&&!turn.running()&&adapter.available())break;Thread.sleep(10);}
                sender.write("/memory pending\r/approval demo\r".getBytes(StandardCharsets.UTF_8));sender.flush();
                end=System.nanoTime()+3_000_000_000L;while(c.loop().snapshot().localApproval()==null&&System.nanoTime()<end)Thread.sleep(10);
                var request=c.loop().snapshot().localApproval();if(request==null)throw new AssertionError("No approval view");
                sender.write(("/approval deny "+request.requestId()+"\r").getBytes(StandardCharsets.UTF_8));sender.flush();Thread.sleep(80);sender.write(17);sender.flush();done.await(10,TimeUnit.SECONDS);
            }catch(Throwable failure){error.set(failure);}});
            keyboard.start();try{TuiSessionsMain.run(terminal,c);}finally{done.countDown();keyboard.join();}
            assertNull(error.get());assertEquals(1,c.approvalAudit().size());assertEquals(com.agent.execution.secure.ApprovalDecision.Status.DENIED,c.approvalAudit().get(0).decision());
            var saved=new CliConversationManager(w).list().get(0).snapshot();assertTrue(saved.crossSessionMemory().enabled());assertTrue(saved.extraction().autoEnabled());assertFalse(saved.extraction().candidates().isEmpty());
            assertEquals(1,saved.histories().get(CliMode.CODE).stream().filter(m->m.role().equals("user")).count());
            assertTrue(new CliConversationManager(w).crossSessionMemory(()->true).listAuthorized().isEmpty());
        }
    }
}
