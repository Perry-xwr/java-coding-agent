package com.agent;

import com.agent.llm.*;
import com.agent.tool.execution.*;
import com.agent.execution.secure.*;
import com.agent.tui.prototype.TuiSessionsMain;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class Tui4BKeyboardTest {
    @TempDir Path root;
    @Test @Timeout(25) void actualJlineInputPreservesNonceAndRejectsTrailingWhitespace()throws Throwable{
        for(boolean trailing:List.of(false,true)){
            Path w=TuiSessionWorkspace.create(root.resolve(trailing?"reject":"approve"));Files.writeString(w.resolve("pom.xml"),"<project/>");
            var calls=new AtomicInteger();var steps=new AtomicInteger();
            LLMClient model=m->steps.incrementAndGet()==1?new LLMResponse("",List.of(new ToolCall("maven","run_maven_test","{}"))):new LLMResponse("审批和执行状态以系统证据为准。",List.of());
            var input=new PipedInputStream();var keyboard=new PipedOutputStream(input);var output=new ByteArrayOutputStream();
            try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,output).build();
                var channel=TestingTuiChannels.create();var adapter=new RealAgentAdapter(w,model,true,channel,(a,b,c,d)->{calls.incrementAndGet();return new ProcessExecutionResult(0,false,"Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",false,1);},w.resolve(".m2/repository"));var c=new TuiSessionController(w,true,adapter)){
                terminal.setSize(new Size(70,20));var done=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
                var human=new Thread(()->{try{
                    Thread.sleep(100);keyboard.write("请运行 Maven 测试并说明结果，不要修改文件。\r".getBytes(StandardCharsets.UTF_8));keyboard.flush();
                    long end=System.nanoTime()+5_000_000_000L;while(c.loop().snapshot().localApproval()==null&&System.nanoTime()<end)Thread.sleep(5);
                    var request=c.loop().snapshot().localApproval();if(request==null||!request.nativeRequest())throw new AssertionError("No native request view");
                    String shown="";long materialDeadline=System.nanoTime()+2_000_000_000L;
                    while(System.nanoTime()<materialDeadline){shown=c.loop().snapshot().current().history().stream().filter(m->m.role().equals("System")).map(m->m.text()).collect(java.util.stream.Collectors.joining("\n"));if(shown.contains("Exact confirmation: APPROVE "))break;Thread.sleep(5);}
                    var nonce=java.util.regex.Pattern.compile("Exact confirmation: APPROVE ([A-F0-9]{8})").matcher(shown);if(!nonce.find())throw new AssertionError("Confirmation material not displayed");
                    String rendered=TuiSessionsMain.frame(c.loop().snapshot(),new com.agent.tui.prototype.ConversationViewport(),true).lines().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
                    if(!rendered.contains(nonce.group(1))||rendered.contains("[potentially sensitive"))throw new AssertionError("Nonce lost by actual frame rendering/redaction");
                    keyboard.write(("/approval confirm "+request.requestId()+" APPROVE "+nonce.group(1)+(trailing?" ":"")+"\r").getBytes(StandardCharsets.UTF_8));keyboard.flush();
                    end=System.nanoTime()+5_000_000_000L;while((c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<end)Thread.sleep(5);
                    keyboard.write(17);keyboard.flush();done.await(8,TimeUnit.SECONDS);
                }catch(Throwable failure){error.set(failure);}});
                human.start();var run=TuiSessionsMain.class.getDeclaredMethod("run",Terminal.class,TuiSessionController.class);run.setAccessible(true);
                try{run.invoke(null,terminal,c);}catch(java.lang.reflect.InvocationTargetException failure){if(error.get()!=null)throw error.get();throw failure.getCause();}finally{done.countDown();human.join();}
                assertNull(error.get());assertEquals(trailing?0:1,calls.get());
                assertEquals(trailing?0:1,channel.audit().stream().filter(a->a.status().equals("START_PERMITTED")).count());
            }
        }
    }
}
