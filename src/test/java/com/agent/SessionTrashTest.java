package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import com.agent.tui.prototype.*;
import com.agent.tui.runtime.UiEventLoop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionTrashTest {
 @TempDir Path root;
 void command(TuiSessionController c,String text)throws Exception{c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,text);var m=TuiSessionsMain.class.getDeclaredMethod("submit",TuiSessionController.class);m.setAccessible(true);m.invoke(null,c);}
 @Test void dailyCommandDeleteCancelRestoreAndStaleWriter()throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("No HTTP");});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
   assertTrue(c.createSession());String first=c.loop().snapshot().currentSessionId();assertTrue(c.rename("保留标题"));
   var old=new CliConversationManager(w);old.activate(old.load(first));
   assertTrue(c.createSession());assertTrue(c.createSession());String active=c.loop().snapshot().currentSessionId();
   Path source=w.resolve(".agent-cli/conversations/"+first+".json");byte[] original=Files.readAllBytes(source);
   command(c,"/session delete "+first);assertTrue(Files.exists(source));command(c,"/session cancel");assertArrayEquals(original,Files.readAllBytes(source));
   command(c,"/session delete "+active);assertTrue(Files.exists(w.resolve(".agent-cli/conversations/"+active+".json")));
   command(c,"/session delete "+first);command(c,"/session confirm-delete "+first);assertFalse(Files.exists(source));assertEquals(2,new CliConversationManager(w).headers().size());
   assertThrows(java.io.IOException.class,()->old.checkpoint(old.active().snapshot()));
   command(c,"/session trash");assertTrue(c.loop().snapshot().current().history().toString().contains(first));
   command(c,"/session restore "+first);assertArrayEquals(original,Files.readAllBytes(source));assertEquals(3,new CliConversationManager(w).headers().size());assertEquals("保留标题",new CliConversationManager(w).load(first).title());assertEquals(0,client.attempts());
   assertThrows(java.io.IOException.class,()->new SessionTrash(w).restore(first));assertThrows(java.io.IOException.class,()->new SessionTrash(w).delete("../bad","x"));
  }
 }
 @Test void changedSnapshotRefusesMove()throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError();});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
   c.createSession();String id=c.loop().snapshot().currentSessionId();var trash=new SessionTrash(w);String fingerprint=trash.fingerprint(id);c.rename("changed");assertThrows(java.io.IOException.class,()->trash.delete(id,fingerprint));assertEquals(1,new CliConversationManager(w).headers().size());
  }
 }
 @SuppressWarnings({"unchecked","rawtypes"}) void key(TuiSessionController c,String name)throws Exception{Class type=Class.forName("com.agent.tui.prototype.TuiPrototypeMain$Key");var m=TuiSessionsMain.class.getDeclaredMethod("key",TuiSessionController.class,type,String.class);m.setAccessible(true);m.invoke(null,c,Enum.valueOf(type,name),"");}
 @Test void selectedDeleteEnterAndEscUseFrozenTargetAndInputDeleteEdits()throws Exception{
  Path w=Files.createDirectory(root.resolve("keyboard"));try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("no HTTP");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.createSession();String first=c.loop().snapshot().currentSessionId();c.createSession();c.createSession();String active=c.loop().snapshot().currentSessionId();
   c.loop().edit(UiEventLoop.Edit.TEXT,"中文");c.loop().edit(UiEventLoop.Edit.HOME,"");key(c,"DELETE");assertEquals("文",c.loop().snapshot().current().input().text());assertFalse(c.deleteConfirmationPending());
   sidebar(c,first);key(c,"DELETE");assertTrue(c.deleteConfirmationPending());key(c,"ESC");assertEquals(3,new CliConversationManager(w).headers().size());
   sidebar(c,first);key(c,"DELETE");assertTrue(c.deleteConfirmationPending());c.loop().moveSelection(1);key(c,"ENTER");assertEquals(2,new CliConversationManager(w).headers().size());assertFalse(Files.exists(w.resolve(".agent-cli/conversations/"+first+".json")));assertEquals(active,c.loop().snapshot().currentSessionId());
   command(c,"/session restore "+first);assertEquals(3,new CliConversationManager(w).headers().size());sidebar(c,active);key(c,"DELETE");assertFalse(c.deleteConfirmationPending());sidebar(c,first);c.loop().approvalView(new com.agent.tui.runtime.UiSnapshot.ApprovalView("pending",active,"turn","Maven","test",true));key(c,"DELETE");assertFalse(c.deleteConfirmationPending());assertEquals(3,new CliConversationManager(w).headers().size());c.loop().approvalView(null);assertEquals(0,client.attempts());
  }
 } void sidebar(TuiSessionController c,String id){c.loop().focus(true);var s=c.loop().snapshot();int target=0,current=0;for(int i=0;i<s.sessions().size();i++){if(s.sessions().get(i).sessionId().equals(id))target=i;if(s.sessions().get(i).sessionId().equals(s.selectedSessionId()))current=i;}c.loop().moveSelection(target-current);}
}
