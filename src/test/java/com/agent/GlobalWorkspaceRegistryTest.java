package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import com.agent.tui.prototype.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class GlobalWorkspaceRegistryTest {
 @TempDir Path root;
 @Test void realDailyControllersRegisterRestoreRenderAndRefuseForeign()throws Exception{
  Path a=Files.createDirectory(root.resolve("projectA")),b=Files.createDirectory(root.resolve("projectB")),catalog=root.resolve("catalog");
  for(Path w:new Path[]{a,b})try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("no HTTP");});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
   c.enableGlobalRegistry(new GlobalWorkspaceRegistry(catalog));assertTrue(c.createSession());assertTrue(c.rename("历史标题"));assertEquals(0,client.attempts());
  }
  var registry=new GlobalWorkspaceRegistry(catalog);assertEquals(2,registry.entries().size());assertEquals(b.toString(),registry.entries().get(0).workspace());registry.register(a.resolve("."));assertEquals(2,registry.entries().size());
  try(var lease=DailyWorkspace.acquire(a);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("no HTTP");});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(a,true,adapter)){
   c.enableGlobalRegistry(registry);var sessions=c.loop().snapshot().sessions();assertEquals(1,sessions.size());assertTrue(sessions.stream().anyMatch(s->s.title().equals("历史标题")));assertTrue(sessions.stream().noneMatch(s->s.sessionId().startsWith("foreign:")));
   String foreign=registry.entries().stream().filter(e->e.workspace().equals(b.toString())).findFirst().orElseThrow().key();String current=c.loop().snapshot().currentSessionId();assertFalse(c.select(foreign));assertEquals(current,c.loop().snapshot().currentSessionId());assertEquals(a,c.workspace());assertTrue(c.select(current));
   String frame=TuiSessionsMain.frame(c.loop().snapshot(),new ConversationViewport(),true).lines().toString();assertFalse(frame.contains("projectB"));assertEquals(0,client.attempts());
  }
  int count=new CliConversationManager(a).headers().size();Files.writeString(catalog.resolve("workspaces.json"),"broken");assertThrows(java.io.IOException.class,registry::entries);assertThrows(java.io.IOException.class,()->registry.register(a));assertEquals("broken",Files.readString(catalog.resolve("workspaces.json")));assertEquals(count,new CliConversationManager(a).headers().size());
 }
 @Test void unavailableWorkspaceRemainsRegistered()throws Exception{
  Path workspace=Files.createDirectory(root.resolve("gone"));var registry=new GlobalWorkspaceRegistry(root.resolve("catalog"));registry.register(workspace);Files.move(workspace,root.resolve("moved"));assertEquals("UNAVAILABLE",registry.entries().get(0).status());
 }
}
