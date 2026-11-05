package com.agent.tui.prototype;
import com.agent.*;
import com.agent.tui.runtime.*;
import org.jline.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class Tui2TerminalFlowTest {
 @TempDir Path root;
 @Test void diagnosticsExcludeUntrustedMessages(){
  var out=new ByteArrayOutputStream();var error=new IllegalStateException("SYNTHETIC_SECRET_DO_NOT_LOG");
  error.addSuppressed(new IOException("SYNTHETIC_SECRET_DO_NOT_LOG"));
  TuiSessionsMain.diagnose(error,new PrintStream(out));
  assertFalse(out.toString().contains("SYNTHETIC_SECRET"));assertTrue(out.toString().contains("TUI2_CLEANUP_FAILURE"));
 }

 @Test @Timeout(20) void actualInputAndDisplayLoop() throws Throwable {
  var input=new PipedInputStream();var sender=new PipedOutputStream(input);var output=new ByteArrayOutputStream();
  try(var terminal=TerminalBuilder.builder().system(false).type("xterm").streams(input,output).build();
      var c=new TuiSessionController(TuiSessionWorkspace.create(root.resolve("w")),true,new FakeAgentAdapter(r->r.text().equals("/error")?FakeAgentAdapter.Scenario.ERROR:FakeAgentAdapter.Scenario.STREAM,10,200))){
   terminal.setSize(new Size(110,26));
   var done=new java.util.concurrent.CountDownLatch(1);
   var writer=new Thread(()->{try{Thread.sleep(200);for(String line:java.util.List.of("你好","第二条消息","第三条消息","/error","异常后继续","/new","第二个会话消息")){sender.write((line+"\r").getBytes(java.nio.charset.StandardCharsets.UTF_8));sender.flush();Thread.sleep(450);}sender.write(17);sender.flush();done.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});writer.start();
   var run=TuiSessionsMain.class.getDeclaredMethod("run",Terminal.class,TuiSessionController.class);run.setAccessible(true);
   try{run.invoke(null,terminal,c);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}finally{done.countDown();writer.join();}
   writer.join();
   var saved=new CliConversationManager(c.workspace()).list();assertEquals(2,saved.size());
   assertEquals(6,saved.stream().flatMap(s->s.snapshot().histories().get(CliMode.CODE).stream()).filter(m->m.role().equals("user")).count());
   assertEquals(5,saved.stream().flatMap(s->s.snapshot().histories().get(CliMode.CODE).stream()).filter(m->m.role().equals("assistant")).count());
   try(var restored=new TuiSessionController(c.workspace(),true,new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.NORMAL,0,100))){
    for(var session:saved){assertTrue(restored.select(session.id()));assertEquals(session.snapshot().histories().get(CliMode.CODE).stream().filter(m->!m.role().equals("system")).count(),restored.loop().snapshot().current().history().size());}
   }
  }
 }
}
