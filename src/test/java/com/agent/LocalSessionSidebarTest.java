package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import com.agent.tui.prototype.*;
import com.agent.tui.runtime.UiEventLoop;
import org.jline.terminal.MouseEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalSessionSidebarTest {
 @TempDir Path root;
 @Test void twoProjectsRemainLocalThroughMouseDeleteRestoreAndRestart()throws Exception{
  Path a=Files.createDirectory(root.resolve("A")),b=Files.createDirectory(root.resolve("B")),catalog=root.resolve("catalog");var registry=new GlobalWorkspaceRegistry(catalog);
  for(Path w:List.of(a,b))try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("No network");});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){
   c.enableGlobalRegistry(registry);for(int i=0;i<(w.equals(a)?3:2);i++){assertTrue(c.createSession());assertTrue(c.rename(w.getFileName()+"会话"+i));}
  }
  Map<Path,byte[]> foreign=new HashMap<>();for(var h:new CliConversationManager(b).headers()){Path file=b.resolve(".agent-cli/conversations/"+h.id()+".json");foreign.put(file,Files.readAllBytes(file));}byte[] registryBytes=Files.readAllBytes(catalog.resolve("workspaces.json"));var registryTime=Files.getLastModifiedTime(catalog.resolve("workspaces.json"));
  try(var lease=DailyWorkspace.acquire(a);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError();});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(a,true,adapter)){
   c.enableGlobalRegistry(registry);var headers=new CliConversationManager(a).headers();assertEquals(headers.stream().map(CliConversationHeader::id).toList(),c.loop().snapshot().sessions().stream().map(s->s.sessionId()).toList());assertEquals(3,c.loop().snapshot().sessions().size());
   c.loop().resize(120,30);var mouse=TuiSessionsMain.class.getDeclaredMethod("routeMouse",MouseEvent.class,UiEventLoop.class,int.class);mouse.setAccessible(true);mouse.invoke(null,new MouseEvent(MouseEvent.Type.Pressed,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),5,6),c.loop(),3);
   String target=headers.get(2).id();assertEquals(target,c.loop().snapshot().selectedSessionId());var helper=new SessionTrashTest();helper.key(c,"DELETE");assertTrue(c.deleteConfirmationPending());helper.key(c,"ENTER");assertEquals(2,c.loop().snapshot().sessions().size());assertFalse(c.loop().snapshot().sessions().stream().anyMatch(s->s.sessionId().equals(target)));assertTrue(c.loop().snapshot().sessions().stream().anyMatch(s->s.sessionId().equals(c.loop().snapshot().selectedSessionId())));
   helper.command(c,"/session restore "+target);assertEquals(3,c.loop().snapshot().sessions().size());assertTrue(c.select(target));assertFalse(TuiSessionsMain.frame(c.loop().snapshot(),new ConversationViewport(),true).lines().toString().contains("B会话"));assertEquals(0,client.attempts());
  }
  for(Path w:List.of(a,b))try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError();});var adapter=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,adapter)){c.enableGlobalRegistry(registry);assertEquals(w.equals(a)?3:2,c.loop().snapshot().sessions().size());}
  for(var entry:foreign.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));assertArrayEquals(registryBytes,Files.readAllBytes(catalog.resolve("workspaces.json")));assertEquals(registryTime,Files.getLastModifiedTime(catalog.resolve("workspaces.json")));assertEquals(5,registry.entries().size());
 }
}
