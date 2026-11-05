package com.agent;

import com.agent.llm.*;
import com.agent.tui.runtime.*;
import com.agent.tui.prototype.*;
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

class Tui5TerminalFlowTest {
    @TempDir Path root;
    @Test @Timeout(20) void multiLineKeyboardSubmissionFocusSwitchToolsAndRestart()throws Throwable{
        Path w=TuiSessionWorkspace.create(root.resolve("workspace"));var asked=new AtomicReference<String>();var calls=new AtomicInteger();
        StreamingLlmClient provider=new StreamingLlmClient(){
            public LLMResponse chat(List<Message> m){throw new AssertionError();}
            public LLMResponse stream(List<Message> m,List<ToolDefinition> t,LlmStreamListener listener){calls.incrementAndGet();asked.set(m.stream().filter(x->x.role().equals("user")).reduce((a,b)->b).orElseThrow().content());listener.onTextDelta("中文");listener.onTextDelta("分块回复。");return new LLMResponse("中文分块回复。",List.of());}
        };
        var input=new PipedInputStream();var sender=new PipedOutputStream(input);var output=new ByteArrayOutputStream();String id;
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,output).build();
            var adapter=new RealAgentAdapter(w,provider);var c=new TuiSessionController(w,true,adapter)){
            terminal.setSize(new Size(100,24));var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
            var keyboard=new Thread(()->{try{
                Thread.sleep(120);sender.write("解释中文".getBytes(StandardCharsets.UTF_8));sender.write(14);sender.write("第二行不要修改文件".getBytes(StandardCharsets.UTF_8));sender.write(13);sender.flush();
                long end=System.nanoTime()+5_000_000_000L;while((c.loop().snapshot().current().turn()==null||c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<end)Thread.sleep(5);
                // History focus Enter must return to editing, never resubmit the previous task.
                sender.write(7);sender.write(13);sender.write("/rename 多行会话\r/tools\r".getBytes(StandardCharsets.UTF_8));sender.flush();Thread.sleep(100);
                sender.write(17);sender.flush();done.await(8,TimeUnit.SECONDS);
            }catch(Throwable error){failure.set(error);}});keyboard.start();
            var run=TuiSessionsMain.class.getDeclaredMethod("run",Terminal.class,TuiSessionController.class);run.setAccessible(true);
            try{run.invoke(null,terminal,c);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}finally{done.countDown();keyboard.join();}
            assertNull(failure.get());assertEquals(1,calls.get());assertEquals("解释中文\n第二行不要修改文件",asked.get());
            var saved=new CliConversationManager(w).list().get(0);id=saved.id();assertEquals("多行会话",saved.title());
            assertEquals(1,saved.snapshot().histories().get(CliMode.CODE).stream().filter(m->m.role().equals("assistant")).count());assertFalse(saved.snapshot().crossSessionMemory().enabled());
        }
        try(var adapter=new RealAgentAdapter(w,provider);var restored=new TuiSessionController(w,true,adapter)){
            assertEquals(id,restored.loop().snapshot().currentSessionId());assertEquals("解释中文\n第二行不要修改文件",restored.loop().snapshot().current().history().get(0).text());
        }
    }
}
