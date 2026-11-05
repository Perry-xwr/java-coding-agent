package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MemoryConsentTest {
 @TempDir Path root;
 @Test void actualDailyLifecycleConsentNewSessionRestartAndRevocation()throws Exception{
  Path w=Files.createDirectory(root.resolve("project")),file=root.resolve("user/catalog/consent.json");var store=new MemoryConsentStore(file);String oldOff;
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError("No API");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.createSession();oldOff=c.loop().snapshot().currentSessionId();c.createSession();c.enableMemoryConsent(store);assertFalse(c.loop().snapshot().current().memory());
   c.memoryCommand("global on");assertFalse(store.read().enabled());c.memoryCommand("consent cancel");assertFalse(store.read().enabled());
   c.memoryCommand("global on");assertTrue(c.memoryCommand("consent confirm"));assertTrue(store.read().enabled());assertTrue(c.loop().snapshot().current().memory());assertFalse(store.read().autoConsent());
   assertTrue(c.memoryCommand("consent auto on"));assertTrue(store.read().autoConsent());assertTrue(c.loop().snapshot().current().autoMemory());
   c.createSession();assertTrue(c.loop().snapshot().current().memory());assertTrue(c.select(oldOff));assertFalse(c.loop().snapshot().current().memory());assertTrue(store.read().enabled());assertEquals(0,client.attempts());
  }
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError();});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(file));c.createSession();assertTrue(c.loop().snapshot().current().memory());assertTrue(store.read().autoConsent());
   c.memoryCommand("consent auto off");assertFalse(store.read().autoConsent());assertTrue(store.read().enabled());c.memoryCommand("global off");assertFalse(c.loop().snapshot().current().memory());assertTrue(c.memoryCommand("compact"));c.createSession();assertFalse(c.loop().snapshot().current().memory());
  }
  assertFalse(new MemoryConsentStore(file).read().enabled());
 }
 @Test void staleUpdateCorruptionAndWriteFailureFailClosed()throws Exception{
  Path file=root.resolve("settings/consent.json");var one=new MemoryConsentStore(file);var stale=one.read();one.update(stale,false,true);assertThrows(java.io.IOException.class,()->new MemoryConsentStore(file).update(stale,true,false));assertTrue(one.read().autoConsent());
  Files.writeString(file,"broken");assertThrows(java.io.IOException.class,one::read);assertEquals("broken",Files.readString(file));
  Path w=Files.createDirectory(root.resolve("project"));try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{throw new AssertionError();});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(one);c.createSession();assertFalse(c.loop().snapshot().current().memory());
   Path parent=root.resolve("notDirectory");Files.writeString(parent,"file");c.enableMemoryConsent(new MemoryConsentStore(parent.resolve("consent.json")));c.memoryCommand("global on");assertFalse(c.memoryCommand("consent confirm"));assertFalse(c.loop().snapshot().current().memory());
  }
 }
 @Test void offRemovesNewSharedInjectionFromActualDailyRequests()throws Exception{
  Path w=Files.createDirectory(root.resolve("scope"));var helper=new DailyRuntimeTest();var bodies=new java.util.ArrayList<String>();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{var b=new okio.Buffer();r.body().writeTo(b);bodies.add(b.readUtf8());return helper.answer(r,"只解释已提供的上下文。");});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   c.enableMemoryConsent(new MemoryConsentStore(root.resolve("user/consent.json")));c.memoryCommand("global on");c.memoryCommand("consent confirm");c.createSession();c.memoryCommand("remember Java 17 CONSENT_CANARY");c.createSession();helper.send(c,a,"解释 Java 版本，不修改文件。");assertTrue(bodies.get(0).contains("CONSENT_CANARY"));c.memoryCommand("global off");helper.send(c,a,"解释 Java 版本，不修改文件。");assertFalse(bodies.get(1).contains("CONSENT_CANARY"));
  }
 }
}
