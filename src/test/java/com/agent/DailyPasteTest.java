package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyPasteTest {
 @TempDir Path root;
 @Test @Timeout(20) void bracketedPasteThroughActualJlineLoopWaitsForEnter()throws Throwable{
  Path w=Files.createDirectory(root.resolve("paste"));var helper=new DailyRuntimeTest();var calls=new AtomicInteger();var error=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
  String original="解释第一段中文\r\n\r\n第二段\r第三段\n第四段\r\n```java\r\nint x = 1;\r\n```\r\n\r\n第四段\r第五段"+"字".repeat(2500);
  String normalized=original.replace("\r\n","\n").replace('\r','\n')+"\n第二次粘贴😀";var in=new java.io.PipedInputStream();var writer=new java.io.PipedOutputStream(in);var out=new java.io.ByteArrayOutputStream();
  try(var terminal=org.jline.terminal.TerminalBuilder.builder().system(false).type("xterm").encoding(java.nio.charset.StandardCharsets.UTF_8).streams(in,out).build();var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var b=new okio.Buffer();r.body().writeTo(b);var messages=new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.readUtf8()).path("messages");assertTrue(java.util.stream.StreamSupport.stream(messages.spliterator(),false).anyMatch(m->m.path("role").asText().equals("user")&&m.path("content").asText().equals(normalized)));calls.incrementAndGet();return helper.answer(r,"完成说明。");});var adapter=new DailyAgentAdapter(lease,client,null);var controller=new TuiSessionController(w,true,adapter)){
   terminal.setSize(new org.jline.terminal.Size(110,25));var sender=new Thread(()->{try{
    Thread.sleep(100);ClipboardCopyTest.write(writer,"\u001b[20");Thread.sleep(30);ClipboardCopyTest.write(writer,"0~"+original.substring(0,20));Thread.sleep(30);ClipboardCopyTest.write(writer,original.substring(20)+"\u001b[20");Thread.sleep(30);ClipboardCopyTest.write(writer,"1~\u001b[200~\n第二次粘贴😀\u001b[201~");Thread.sleep(1000);assertEquals(normalized,controller.loop().snapshot().current().input().text());assertEquals(0,calls.get());
    ClipboardCopyTest.write(writer,"\r");ClipboardCopyTest.await(()->calls.get()==1);ClipboardCopyTest.await(()->controller.loop().snapshot().current().turn()!=null&&!controller.loop().snapshot().current().turn().running()&&adapter.available());
    var history=new CliConversationManager(w).load(controller.loop().snapshot().currentSessionId()).snapshot().histories().get(CliMode.CODE);assertEquals(1,history.stream().filter(m->m.role().equals("user")).count());assertTrue(history.stream().anyMatch(m->m.role().equals("user")&&m.content().equals(normalized)));
    ClipboardCopyTest.write(writer,"草稿\u001b[200~"+"字".repeat(16385)+"\u001b[201~");ClipboardCopyTest.await(()->controller.loop().snapshot().notice().contains("INPUT_LIMIT"));assertEquals("草稿",controller.loop().snapshot().current().input().text());assertEquals(1,calls.get());
    ClipboardCopyTest.write(writer,"\u0015单行\u000e换行");ClipboardCopyTest.await(()->controller.loop().snapshot().current().input().text().equals("单行\n换行"));assertEquals(1,calls.get());
   }catch(Throwable e){error.set(e);}finally{try{ClipboardCopyTest.write(writer,"\u0011");done.await(5,TimeUnit.SECONDS);}catch(Exception e){error.compareAndSet(null,e);}}});sender.start();
   var run=com.agent.tui.prototype.TuiSessionsMain.class.getDeclaredMethod("run",org.jline.terminal.Terminal.class,TuiSessionController.class);run.setAccessible(true);try{run.invoke(null,terminal,controller);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}finally{done.countDown();sender.join();}if(error.get()!=null)throw error.get();assertEquals(1,calls.get());assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("\u001b[?2004l"));
  }
 }
}
