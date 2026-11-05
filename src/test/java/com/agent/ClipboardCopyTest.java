package com.agent;
import com.agent.llm.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class ClipboardCopyTest {
 @TempDir Path root;
 @Test void longOriginalMarkdownCopiesThroughF6CommandAndRestart()throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));String answer="# 中文 English 🚀\n\n```java\nint sum = 2 + 3;\n```\n|列|值|\n|--|--|\n"+"长回答，不应按屏幕折行。\n".repeat(500);var copied=new AtomicReference<String>();var helper=new DailyRuntimeTest();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->helper.answer(r,answer));var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.clipboardForTesting(copied::set);assertFalse(c.copy(false));assertNull(copied.get());helper.send(c,a,"解释文本，不修改文件。");
   var keyboard=new SessionTrashTest();keyboard.key(c,"COPY");assertEquals(answer,copied.get());c.loop().chatFocus(true);copied.set(null);keyboard.key(c,"COPY");assertEquals(answer,copied.get());
   keyboard.command(c,"/copy session");assertTrue(c.copyConfirmationPending());keyboard.key(c,"ESC");assertFalse(c.copyConfirmationPending());assertEquals(answer,copied.get());
   keyboard.command(c,"/copy session");keyboard.key(c,"ENTER");assertTrue(copied.get().startsWith("User:\n解释文本"));assertTrue(copied.get().contains("Agent:\n"+answer));assertFalse(copied.get().contains("tool_calls"));
   c.clipboardForTesting(text->{throw new IllegalStateException("SENSITIVE_FAILURE_DETAIL");});assertFalse(c.copy(false));assertTrue(c.loop().snapshot().notice().contains("COPY_FAILED"));assertFalse(c.loop().snapshot().notice().contains("SENSITIVE_FAILURE_DETAIL"));
   assertEquals(1,client.attempts());
  }
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("no network");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.clipboardForTesting(copied::set);new SessionTrashTest().command(c,"/copy");assertEquals(answer,copied.get());
  }
 }
 @Test void protocolAndFailedTurnExcludedAndCapacityIsNotSilentlyTruncated(){
  var call=Message.assistant("可见读取进度",List.of(new ToolCall("id","read_file","{\"secret\":\"HIDDEN\"}")));
  var history=List.of(Message.system("system private"),Message.user("原问题"),call,new Message("tool","HIDDEN_OUTPUT"),Message.assistant("完整最终回复\n```text\n中文🚀\n```"),Message.user("失败问题"),Message.assistant("不完整答案"),Message.system(RealAgentAdapter.FAILED_TURN_MARKER+"FAILED"));
  assertEquals("可见读取进度\n\n完整最终回复\n```text\n中文🚀\n```",ConversationCopy.select(history,false));String session=ConversationCopy.select(history,true);assertTrue(session.contains("可见读取进度"));assertFalse(session.contains("HIDDEN"));assertFalse(session.contains("不完整答案"));assertTrue(session.indexOf("原问题")<session.indexOf("完整最终回复"));
  assertThrows(IllegalArgumentException.class,()->ConversationCopy.select(List.of(Message.user("x")),false));assertThrows(IllegalArgumentException.class,()->ConversationCopy.select(List.of(Message.user("x"),Message.assistant("字".repeat(ConversationCopy.MAX_BYTES))),false));
 }
 @Test @Timeout(20) void actualJlineF6AndCopyConfirmationNeverSubmitExtraModelRequest()throws Throwable{
  Path w=Files.createDirectory(root.resolve("keyboard"));var helper=new DailyRuntimeTest();String answer="完整中文🚀\n```java\nint n=1;\n```\n".repeat(100);var copies=new java.util.concurrent.atomic.AtomicInteger();var text=new AtomicReference<String>();var error=new AtomicReference<Throwable>();var done=new java.util.concurrent.CountDownLatch(1);
  var input=new java.io.PipedInputStream();var writer=new java.io.PipedOutputStream(input);var output=new java.io.ByteArrayOutputStream();
  try(var t=org.jline.terminal.TerminalBuilder.builder().system(false).type("xterm").encoding(java.nio.charset.StandardCharsets.UTF_8).streams(input,output).build();var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->helper.answer(r,answer));var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.clipboardForTesting(value->{text.set(value);copies.incrementAndGet();});t.setSize(new org.jline.terminal.Size(110,25));String f6=org.jline.keymap.KeyMap.key(t,org.jline.utils.InfoCmp.Capability.key_f6);assertNotNull(f6);
   var sender=new Thread(()->{try{
    Thread.sleep(100);write(writer,"解释文本，不修改文件。\r");await(()->c.loop().snapshot().current().turn()!=null&&!c.loop().snapshot().current().turn().running()&&a.available());
    write(writer,"草稿"+f6);await(()->copies.get()==1);assertEquals(answer,text.get());assertEquals("草稿",c.loop().snapshot().current().input().text());write(writer,"\u0007"+f6);await(()->copies.get()==2);write(writer,"\u0007\u0015/copy session\r");await(()->c.loop().snapshot().notice().contains("复制对话"));write(writer,"\u001b");await(()->c.loop().snapshot().notice().contains("复制已取消"));assertEquals(2,copies.get());
   }catch(Throwable failure){error.set(failure);}finally{try{write(writer,"\u0011");done.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){error.compareAndSet(null,failure);}}});sender.start();
   var run=com.agent.tui.prototype.TuiSessionsMain.class.getDeclaredMethod("run",org.jline.terminal.Terminal.class,TuiSessionController.class);run.setAccessible(true);try{run.invoke(null,t,c);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}finally{done.countDown();sender.join();}assertNull(error.get());assertEquals(1,client.attempts());
  }
 }
 @Test @Timeout(20) void inFlightReplyCannotReachClipboard()throws Exception{
  Path w=Files.createDirectory(root.resolve("running"));var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var helper=new DailyRuntimeTest();var copied=new AtomicReference<String>();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new java.io.IOException(e);}return helper.answer(r,"完成回复");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.clipboardForTesting(copied::set);c.loop().edit(com.agent.tui.runtime.UiEventLoop.Edit.TEXT,"解释，不修改文件。");c.submit();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(c.copy(false));assertNull(copied.get());release.countDown();helper.finish(c,a);assertTrue(c.copy(false));assertEquals("完成回复",copied.get());
  }finally{release.countDown();}
 }
 static void write(java.io.OutputStream out,String text)throws java.io.IOException{out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();}
 static void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+4_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());} @Test void f6CannotActInsideDeleteOrMavenConfirmation()throws Exception{
  Path w=Files.createDirectory(root.resolve("guards"));var helper=new DailyRuntimeTest();var writes=new java.util.concurrent.atomic.AtomicInteger();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->helper.answer(r,"完整回复"));var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.clipboardForTesting(text->writes.incrementAndGet());helper.send(c,a,"解释，不修改文件。");String first=c.loop().snapshot().currentSessionId();c.createSession();var keyboard=new SessionTrashTest();keyboard.sidebar(c,first);keyboard.key(c,"DELETE");assertTrue(c.deleteConfirmationPending());keyboard.key(c,"COPY");assertTrue(c.deleteConfirmationPending());assertEquals(0,writes.get());keyboard.key(c,"ESC");assertEquals(2,new CliConversationManager(w).headers().size());c.select(first);
   c.loop().approvalView(new com.agent.tui.runtime.UiSnapshot.ApprovalView("approval",first,"turn","Maven","test",true));keyboard.key(c,"COPY");assertEquals(0,writes.get());assertNotNull(c.loop().snapshot().localApproval());c.loop().approvalView(null);
   c.clipboardForTesting(text->{throw new ClipboardSink.Failure("BUSY");});assertFalse(c.copy(false));assertTrue(c.loop().snapshot().notice().contains("BUSY"));assertEquals(1,client.attempts());
  }
 } @Test void mouseButtonCopiesEntireHistoricalMultiSegmentReplyOverThreeScreens()throws Exception{
  Path w=Files.createDirectory(root.resolve("click"));var helper=new DailyRuntimeTest();String preamble="先读取相关资料。";String longReply="# 中文 Markdown 🚀\n```java\nint value=5;\n```\n"+"正文原始段落，不能按屏幕裁切。\n".repeat(150);var step=new java.util.concurrent.atomic.AtomicInteger();var copied=new AtomicReference<String>();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{int n=step.incrementAndGet();if(n==1)return helper.reply(r,helper.event(Map.of("content",preamble,"tool_calls",List.of(Map.of("index",0,"id","read","function",Map.of("name","read_file","arguments","{\"path\":\"note.txt\"}")))))+"data: [DONE]\n\n");return helper.answer(r,n==2?longReply:"第二轮简短回复");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   Files.writeString(w.resolve("note.txt"),"PRIVATE_TOOL_RESULT_NOT_FOR_COPY");c.clipboardForTesting(copied::set);helper.send(c,a,"解释资料，不修改文件。");c.loop().resize(110,25);var viewport=new com.agent.tui.prototype.ConversationViewport();clickVisibleCopy(c,viewport);assertEquals(preamble+"\n\n"+longReply,copied.get());assertFalse(copied.get().contains("PRIVATE_TOOL_RESULT"));assertTrue(copied.get().split("\n").length>3*25);
   String firstKey=c.loop().snapshot().current().history().stream().filter(m->!m.copyKey().isBlank()).findFirst().orElseThrow().copyKey();helper.send(c,a,"再解释一句，不修改文件。");
   assertEquals(2,c.loop().snapshot().current().history().stream().filter(m->!m.copyKey().isBlank()).count());
   copied.set(null);int historicalRow=-1;
   var hit=com.agent.tui.prototype.ConversationViewport.class.getDeclaredMethod("copyKeyAt",com.agent.tui.runtime.UiSnapshot.class,int.class,int.class);hit.setAccessible(true);
   for(int i=0;i<100&&historicalRow<0;i++){
    com.agent.tui.prototype.TuiSessionsMain.frame(c.loop().snapshot(),viewport,true);
    for(int row=2;row<20;row++)if(firstKey.equals(hit.invoke(viewport,c.loop().snapshot(),30,row))){historicalRow=row;break;}
    if(historicalRow<0)c.loop().scroll(1);
   }
   assertTrue(historicalRow>=2,"Historical reply must have a real visible click target");clickRow(c,viewport,historicalRow);assertEquals(preamble+"\n\n"+longReply,copied.get());
   c.createSession();assertFalse(c.copyReply(firstKey));assertEquals(3,client.attempts());
  }
 }
 void clickVisibleCopy(TuiSessionController c,com.agent.tui.prototype.ConversationViewport viewport)throws Exception{
  var frame=com.agent.tui.prototype.TuiSessionsMain.frame(c.loop().snapshot(),viewport,true);int row=-1;for(int i=0;i<frame.lines().size();i++)if(frame.lines().get(i).toString().contains("[复制]"))row=i;assertTrue(row>=2);
  clickRow(c,viewport,row);
 }
 void clickRow(TuiSessionController c,com.agent.tui.prototype.ConversationViewport viewport,int row)throws Exception{
  byte[] report=new byte[]{32,(byte)(32+31),(byte)(32+row+1)};
  try(var t=org.jline.terminal.TerminalBuilder.builder().system(false).type("xterm").streams(new java.io.ByteArrayInputStream(report),new java.io.ByteArrayOutputStream()).build()){
   var method=com.agent.tui.prototype.TuiSessionsMain.class.getDeclaredMethod("mouse",org.jline.terminal.Terminal.class,org.jline.keymap.BindingReader.class,com.agent.tui.runtime.UiEventLoop.class,com.agent.tui.prototype.ConversationViewport.class,TuiSessionController.class);method.setAccessible(true);method.invoke(null,t,new org.jline.keymap.BindingReader(t.reader()),c.loop(),viewport,c);
  }
 }}
