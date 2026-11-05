package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import com.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryManagementTest {
    @TempDir Path workspace;
    String id() { return UUID.randomUUID().toString(); }
    LocalCrossSessionMemory service() { return service(new SharedMemoryStore(workspace),true); }
    LocalCrossSessionMemory service(SharedMemoryStore store,boolean on) { return new LocalCrossSessionMemory(store,()->on,workspace); }
    Path library() { return workspace.resolve(".agent-cli/memory/shared.json"); }
    @Test void listAndSearchReturnAuthorizedStableMetadata() throws Exception {
        String source = id(); var memory = service().rememberAuthorized(source,"项目使用 Java 17");
        assertEquals(List.of(memory),service().listAuthorized());
        assertEquals(memory.memoryId(),service().retrieveAuthorized(id(),"Java 版本").get(0).memory().memoryId());
        assertEquals(source,service().listAuthorized().get(0).sourceSessionId());
    }
    @Test void updateRetainsIdentityProvenanceAndCreationButAdvancesUpdatedTime() throws Exception {
        var old = service().rememberAuthorized(id(),"项目使用 Java 17");
        var updated = service().updateAuthorized(old.memoryId(),"项目使用 Java 21");
        assertEquals(old.memoryId(),updated.memoryId()); assertEquals(old.createdAt(),updated.createdAt());
        assertEquals(old.sourceSessionId(),updated.sourceSessionId()); assertEquals(old.sourceType(),updated.sourceType());
        assertEquals(old.sharingAuthorization(),updated.sharingAuthorization()); assertEquals(old.workspaceScope(),updated.workspaceScope());
        assertTrue(Instant.parse(updated.updatedAt()).isAfter(Instant.parse(old.updatedAt())));
        assertEquals("项目使用 Java 21",new SharedMemoryStore(workspace).read().get(0).content());
        assertTrue(service().retrieveAuthorized(id(),"Java").stream().noneMatch(h -> h.memory().content().contains("17")));
    }
    @Test void forgetIsDurableAndAffectsOnlyExactId() throws Exception {
        var one = service().rememberAuthorized(id(),"Java 17"); var two=service().rememberAuthorized(id(),"Python syntax");
        service().forgetAuthorized(one.memoryId());
        assertEquals(List.of(two),new SharedMemoryStore(workspace).read());
        assertTrue(service().retrieveAuthorized(id(),"Java").isEmpty());
    }
    @Test void offManagementNeverTouchesStoreEvenForInvalidParameters() throws Exception {
        SharedMemoryStore forbidden = new SharedMemoryStore(workspace) {
            public List<LongTermMemory> read() { fail("OFF read"); return List.of(); }
            public LongTermMemory update(String id,String scope,String text) { fail("OFF update"); return null; }
            public void forget(String id,String scope) { fail("OFF delete"); }
        };
        var off=service(forbidden,false);
        assertThrows(IllegalStateException.class,off::listAuthorized);
        assertThrows(IllegalStateException.class,()->off.updateAuthorized("bad","text"));
        assertThrows(IllegalStateException.class,()->off.forgetAuthorized("bad"));
        assertEquals(List.of(),off.retrieveAuthorized(id(),"Java"));
        assertFalse(Files.exists(workspace.resolve(".agent-cli")));
    }
    @Test void invalidMissingAndForeignScopeIdsCannotChangeAnyBytes() throws Exception {
        var memory=service().rememberAuthorized(id(),"Java 17"); byte[] before=Files.readAllBytes(library());
        for(String bad:List.of("",memory.memoryId().substring(0,8),"../shared", "not-a-uuid")) {
            assertThrows(IllegalArgumentException.class,()->service().updateAuthorized(bad,"Java 21"));
            assertThrows(IllegalArgumentException.class,()->service().forgetAuthorized(bad));
        }
        String absent=id();
        assertThrows(NoSuchElementException.class,()->service().updateAuthorized(absent,"Java 21"));
        assertThrows(NoSuchElementException.class,()->service().forgetAuthorized(absent));
        var foreign=new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->true,workspace.resolve("other"));
        assertEquals(List.of(),foreign.listAuthorized());
        assertThrows(NoSuchElementException.class,()->foreign.updateAuthorized(memory.memoryId(),"Java 21"));
        assertThrows(NoSuchElementException.class,()->foreign.forgetAuthorized(memory.memoryId()));
        assertArrayEquals(before,Files.readAllBytes(library()));
    }
    @Test void invalidUpdateTextLeavesDataUnchanged() throws Exception {
        var m=service().rememberAuthorized(id(),"Java 17"); byte[] before=Files.readAllBytes(library());
        for(String text:List.of("","  ","a".repeat(1025),"GLM_API_KEY=fictional-value","password=fictional"))
            assertThrows(IllegalArgumentException.class,()->service().updateAuthorized(m.memoryId(),text));
        assertArrayEquals(before,Files.readAllBytes(library()));
    }
    @Test void exactDuplicateRememberAndUpdateDoNotSilentlyAccumulateOrMerge() throws Exception {
        var a=service().rememberAuthorized(id(),"Java 17"); var b=service().rememberAuthorized(id(),"Java 21");
        byte[] before=Files.readAllBytes(library());
        for(int i=0;i<4;i++) {
            var duplicate=assertThrows(SharedMemoryStore.DuplicateMemoryException.class,()->service().rememberAuthorized(id(),"Java 17"));
            assertEquals(a.memoryId(),duplicate.existingId());
        }
        assertThrows(SharedMemoryStore.DuplicateMemoryException.class,()->service().updateAuthorized(b.memoryId(),"Java 17"));
        assertArrayEquals(before,Files.readAllBytes(library()));
        assertEquals(a,service().updateAuthorized(a.memoryId(),a.content()));
        assertEquals(2,service().listAuthorized().size()); // Conflicting statements remain explicit independent records.
    }
    @Test void failedUpdateAndDeletePreserveOriginalAtomicLibrary() throws Exception {
        var old=service().rememberAuthorized(id(),"Java 17"); byte[] before=Files.readAllBytes(library());
        var broken=service(new SharedMemoryStore(workspace,(p,b)->{Files.write(p,new byte[]{1});throw new IOException("controlled failure");}),true);
        assertThrows(IOException.class,()->broken.updateAuthorized(old.memoryId(),"Java 21"));
        assertArrayEquals(before,Files.readAllBytes(library()));
        assertThrows(IOException.class,()->broken.forgetAuthorized(old.memoryId()));
        assertArrayEquals(before,Files.readAllBytes(library()));
    }
    @Test void malformedJsonCannotBeOverwrittenByManagement() throws Exception {
        var m=service().rememberAuthorized(id(),"Java 17"); Files.writeString(library(),"{broken");
        byte[] broken=Files.readAllBytes(library());
        assertThrows(IOException.class,()->service().updateAuthorized(m.memoryId(),"Java 21"));
        assertThrows(IOException.class,()->service().forgetAuthorized(m.memoryId()));
        assertThrows(IOException.class,()->service().rememberAuthorized(id(),"Python"));
        assertArrayEquals(broken,Files.readAllBytes(library()));
    }
    @Test void fakeLlmE2eUsesUpdatedContentThenNoDeletedContentIncludingEchoAndRestart() throws Exception {
        Recording llm=new Recording(); var profiles=profiles(llm); var cli=cli(profiles,new ByteArrayOutputStream());
        run(cli,"/new\n/memory global on\n/memory remember 项目使用 Java 17\n/exit\n");
        String memoryId=service().listAuthorized().get(0).memoryId();
        String sourceId=service().listAuthorized().get(0).sourceSessionId();
        run(cli,"/new\n/memory global on\n请问项目使用什么 Java 版本？\n/exit\n");
        present(llm.last(),"项目使用 Java 17");
        Path sourceSnapshot=workspace.resolve(".agent-cli/conversations/" + sourceId + ".json");
        byte[] sourceBefore=Files.readAllBytes(sourceSnapshot);
        String bId=new CliConversationManager(workspace).list().stream().filter(c->!c.id().equals(sourceId)).findFirst().orElseThrow().id();
        var historyBefore=List.copyOf(profiles.code().history());
        run(cli,"/memory update " + memoryId + " 项目使用 Java 21\n/exit\n");
        assertEquals(historyBefore,profiles.code().history());
        run(cli,"请问项目使用什么 Java 版本？\n/exit\n");
        present(llm.last(),"项目使用 Java 21"); absent(llm.last(),"项目使用 Java 17"); absent(llm.last(),"ECHO_17");
        var afterUpdate=List.copyOf(profiles.code().history());
        run(cli,"/memory forget " + memoryId + "\n/exit\n"); assertEquals(afterUpdate,profiles.code().history());
        run(cli,"请问项目使用什么 Java 版本？\n/exit\n");
        absent(llm.last(),"CROSS_SESSION_MEMORY_DATA"); absent(llm.last(),"ECHO_17"); absent(llm.last(),"ECHO_21");
        assertTrue(profiles.code().history().stream().anyMatch(m->m.content().contains("ECHO_17"))); // History not erased.
        Recording restarted=new Recording(); var fresh=cli(profiles(restarted),new ByteArrayOutputStream());
        run(fresh,"/switch " + bId + "\n请问项目使用什么 Java 版本？\n/exit\n"); absent(restarted.last(),"CROSS_SESSION_MEMORY_DATA");
        assertTrue(service().listAuthorized().isEmpty());
        assertArrayEquals(sourceBefore,Files.readAllBytes(sourceSnapshot),"Other session source history must not be rewritten");
        assertTrue(new CliConversationManager(workspace).load(sourceId).snapshot().crossSessionMemory().enabled());
    }
    @Test void restartedCliRetrievesUpdatedContentWithOriginalSessionConsent() throws Exception {
        Recording first=new Recording(); var cli=cli(profiles(first),new ByteArrayOutputStream());
        run(cli,"/memory global on\n/memory remember 项目使用 Java 17\n/exit\n");
        var memory=service().listAuthorized().get(0);
        run(cli,"/memory update " + memory.memoryId() + " 项目使用 Java 21\n/exit\n");
        Recording nextProcess=new Recording(); var restored=cli(profiles(nextProcess),new ByteArrayOutputStream());
        run(restored,"/switch " + memory.sourceSessionId() + "\n请问项目使用什么 Java 版本？\n/exit\n");
        present(nextProcess.last(),"项目使用 Java 21"); absent(nextProcess.last(),"项目使用 Java 17");
    }
    @Test void cliManagementOffDeniesWithoutLibraryAccessThenOnListsAndSearches() throws Exception {
        var m=service().rememberAuthorized(id(),"Java 17");
        ByteArrayOutputStream output=new ByteArrayOutputStream(); Recording llm=new Recording(); var cli=cli(profiles(llm),output);
        byte[] before=Files.readAllBytes(library());
        run(cli,"/memory list\n/memory search Java\n/memory update " + m.memoryId() + " Java 21\n/memory forget " + m.memoryId() + "\n/memory status\n/exit\n");
        String off=output.toString(StandardCharsets.UTF_8); assertFalse(off.contains(m.memoryId())); assertFalse(off.contains("Java 17"));
        assertTrue(off.contains("NOT_ACCESSED")); assertArrayEquals(before,Files.readAllBytes(library()));
        output.reset(); run(cli,"/memory global on\n/memory list\n/memory search Java\n/memory status\n/exit\n");
        String on=output.toString(StandardCharsets.UTF_8); assertTrue(on.contains(m.memoryId())); assertTrue(on.contains("source="));
        assertTrue(on.contains("relevance=")); assertTrue(on.contains("Shared authorized entries=1")); assertEquals(0,llm.requests.size());
    }
    @Test void cliReportsDuplicateMissingIdAndDeletionScopeWithoutChangingHistory() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); var cli=cli(profiles(new Recording()),output);
        run(cli,"/memory global on\n/memory remember Java 17\n/memory remember Java 17\n/memory update not-id Java 21\n/memory forget " + id() + "\n/exit\n");
        String text=output.toString(StandardCharsets.UTF_8); assertTrue(text.contains("already exists")); assertTrue(text.contains("not found"));
        var m=service().listAuthorized().get(0); output.reset(); run(cli,"/memory forget " + m.memoryId() + "\n/exit\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Original conversation history and prior files are not erased"));
    }
    CliSessions profiles(LLMClient llm) { return CliAgentFactory.createProfiles(llm,workspace,AgentEventListener.NO_OP); }
    InteractiveCli cli(CliSessions profiles,ByteArrayOutputStream output) { return new InteractiveCli(profiles,new ConsoleUi(new PrintStream(output,true,StandardCharsets.UTF_8)),workspace); }
    void run(InteractiveCli cli,String input)throws IOException {cli.run(new BufferedReader(new StringReader(input)));}
    static void present(List<Message> messages,String text){assertTrue(messages.stream().anyMatch(m->m.content().contains(text)),text);}
    static void absent(List<Message> messages,String text){assertFalse(messages.stream().anyMatch(m->m.content().contains(text)),text);}
    static class Recording implements LLMClient {
        List<List<Message>> requests=new ArrayList<>();
        public LLMResponse chat(List<Message> messages){
            requests.add(List.copyOf(messages));
            String shared=messages.stream().filter(m->m.content().contains("CROSS_SESSION_MEMORY_DATA")).map(Message::content).findFirst().orElse("");
            return new LLMResponse(shared.contains("Java 17")?"ECHO_17":shared.contains("Java 21")?"ECHO_21":"offline answer",List.of());
        }
        List<Message> last(){return requests.get(requests.size()-1);}
    }
}
