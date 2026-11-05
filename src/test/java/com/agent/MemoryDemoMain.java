package com.agent;
import com.agent.llm.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
/** D1 test-only interactive launcher; never constructs a network Provider. */
public final class MemoryDemoMain {
 public static void main(String[] args)throws Exception {
  if(args.length!=1)throw new IllegalArgumentException("Supply an explicit isolated workspace");
  Path workspace=Path.of(args[0]).toAbsolutePath().normalize();
  if(!workspace.startsWith(Path.of("D:/Development/agent-cli/benchmark-runs").toAbsolutePath().normalize()))throw new IllegalArgumentException("Demo workspace must be isolated under benchmark-runs");
  Files.createDirectories(workspace);
  var ui=new ConsoleUi(System.out,"FAKE (OFFLINE ONLY)","scripted-memory-demo");
  LLMClient fake=messages->{
   var block=messages.stream().filter(m->m.content().startsWith("CROSS_SESSION_MEMORY_DATA")).map(Message::content).findFirst().orElse("");
   return new LLMResponse(block.isEmpty()?"[FAKE] No shared memory in this request; this is not a real model answer.":"[FAKE] Shared context received: "+(block.contains("Java 21")?"Java 21":block.contains("Java 17")?"Java 17":"authorized data"),List.of());
  };
  // This scripted client cannot edit files. Use the real CHAT profile, rather than
  // pretending a text-only reply satisfies UNIFIED's mutation completion guard.
  var cli=new InteractiveCli(CliAgentFactory.createProfiles(fake,workspace,ui),ui,workspace,CliMode.CHAT);
  cli.embeddingProviderForTest(new com.agent.memory.FakeEmbeddingProvider(Map.of(),1));
  cli.runInteractive();
 }
}
