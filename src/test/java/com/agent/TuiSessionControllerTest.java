package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import com.agent.memory.*;
import com.agent.tui.runtime.*;
import com.agent.tui.prototype.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class TuiSessionControllerTest {
    @TempDir Path root;
    Path workspace()throws IOException{return TuiSessionWorkspace.create(root.resolve("workspace"));}
    FakeAgentAdapter fake(){return new FakeAgentAdapter(r->r.text().equals("error")?FakeAgentAdapter.Scenario.ERROR:FakeAgentAdapter.Scenario.STREAM,5,200);}
    void text(TuiSessionController c,String text){c.loop().edit(UiEventLoop.Edit.CLEAR,"");c.loop().edit(UiEventLoop.Edit.TEXT,text);}
    void until(TuiSessionController c,BooleanSupplier done)throws Exception{
        long deadline=System.nanoTime()+4_000_000_000L;
        while(!done.getAsBoolean()&&System.nanoTime()<deadline){c.pump();Thread.sleep(3);}assertTrue(done.getAsBoolean());c.pump();
    }
    void finish(TuiSessionController c)throws Exception{until(c,()->!c.loop().snapshot().current().turn().running());}
    CliSessions profiles(Path workspace){return CliAgentFactory.createProfiles(messages->{throw new IOException("Validation only");},workspace,AgentEventListener.NO_OP);}
    CliConversation legacy(Path w,CliMode mode,boolean global,String user)throws Exception{
        var profiles=profiles(w);var histories=new EnumMap<CliMode,List<Message>>(profiles.histories());
        histories.put(mode,List.of(histories.get(mode).get(0),Message.user(user),Message.assistant("legacy answer")));
        var snapshot=new CliSessionSnapshot(1,Instant.now().toString(),mode,null,histories,new CliWorkingContext().snapshot(),
                SessionSummary.empty(),CrossSessionState.off().withEnabled(global));
        return new CliConversationManager(w).create(snapshot);
    }
    long replies(CliConversation c,CliMode mode){return c.snapshot().histories().get(mode).stream().filter(m->m.role().equals("assistant")&&m.content().startsWith("[FAKE]")).count();}

    @Test void renderEverySubmissionPhase()throws Exception{
        Path w=workspace();try(var c=new TuiSessionController(w,true,fake())){
            var viewport=new ConversationViewport();
            for(int i=0;i<3;i++){text(c,"中文消息"+i);TuiSessionsMain.frame(c.loop().snapshot(),viewport,true);assertNotNull(c.submit());
                TuiSessionsMain.frame(c.loop().snapshot(),viewport,true);
                until(c,()->{TuiSessionsMain.frame(c.loop().snapshot(),viewport,true);return !c.loop().snapshot().current().turn().running();});
            }assertTrue(c.prepareExit());
        }
    }
    @Test void startupDoesNotCreateAndFirstMessageIsSavedBeforeFakeStartsAndNamed()throws Exception{
        Path w=workspace();var calls=new AtomicInteger();AgentAdapter adapter=new AgentAdapter(){
            final FakeAgentAdapter delegate=fake();public Mode mode(){return Mode.FAKE_ONLY;}
            public RunHandle start(Request request,EventSink sink){
                calls.incrementAndGet();try{
                    var saved=new CliConversationManager(w).load(request.sessionId());
                    assertTrue(saved.snapshot().histories().get(CliMode.CODE).stream().anyMatch(m->m.role().equals("user")&&m.content().equals(request.text())));
                }catch(IOException failure){throw new UncheckedIOException(failure);}return delegate.start(request,sink);
            }
            public void close(){delegate.close();}
        };
        String id;
        try(var c=new TuiSessionController(w,true,adapter)){
            assertTrue(new CliConversationManager(w).list().isEmpty());assertFalse(c.loop().snapshot().current().memory());
            text(c,"帮我分析一下这个 Java 项目的整体架构");assertNotNull(c.submit());finish(c);
            id=c.loop().snapshot().currentSessionId();assertEquals(1,calls.get());
            var saved=new CliConversationManager(w).load(id);assertEquals("分析 Java 项目架构",saved.title());assertEquals(1,replies(saved,CliMode.CODE));
            text(c,"继续解释");c.submit();finish(c);assertEquals(id,c.loop().snapshot().currentSessionId());
        }
        try(var restarted=new TuiSessionController(w,true,fake())){
            assertEquals(id,restarted.loop().snapshot().currentSessionId());assertEquals(4,restarted.loop().snapshot().current().history().size());
            var output=new ByteArrayOutputStream();var p=profiles(w);
            new InteractiveCli(p,new ConsoleUi(new PrintStream(output,true,StandardCharsets.UTF_8)),w)
                    .run(new BufferedReader(new StringReader("/switch "+id+"\n/history\n/exit\n")));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("继续解释"));
            assertEquals(2,replies(new CliConversationManager(w).load(id),CliMode.CODE));
        }
    }
    @Test void explicitNewAndManualRenamePreserveOffDefaultsAndNamesAfterRestart()throws Exception{
        Path w=workspace();String id;
        try(var c=new TuiSessionController(w,true,fake())){
            assertTrue(c.createSession());id=c.loop().snapshot().currentSessionId();assertTrue(c.rename("人工命名"));
            text(c,"这个标题不能覆盖人工命名");c.submit();finish(c);
            assertEquals("人工命名",new CliConversationManager(w).load(id).title());
            assertTrue(c.createSession());assertFalse(c.loop().snapshot().current().memory());
            assertEquals(2,new CliConversationManager(w).headers().size());
            assertEquals(2,c.loop().snapshot().sessions().size());
        }
        try(var c=new TuiSessionController(w,true,fake())){assertTrue(c.select(id));assertEquals("人工命名",c.loop().snapshot().current().title());}
    }
    @Test void streamingOriginIsSavedWithoutContaminatingNewSessionAndMemoryIsNotChanged()throws Exception{
        Path w=workspace();var a=legacy(w,CliMode.READ,true,"A private");
        try(var c=new TuiSessionController(w,true,new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.STREAM,40,200))){
            assertEquals("READ",c.loop().snapshot().current().profile());assertTrue(c.loop().snapshot().current().memory());
            text(c,"A followup");c.submit();assertTrue(c.createSession());String b=c.loop().snapshot().currentSessionId();
            assertFalse(c.loop().snapshot().current().memory());assertTrue(c.loop().snapshot().current().history().isEmpty());
            until(c,()->c.loop().snapshot().sessions().stream().filter(s->s.sessionId().equals(a.id())).allMatch(s->!s.turn().running()));
            assertTrue(c.loop().snapshot().current().history().isEmpty());assertEquals(b,c.loop().snapshot().currentSessionId());
            assertEquals(1,replies(new CliConversationManager(w).load(a.id()),CliMode.READ));
            assertEquals(0,replies(new CliConversationManager(w).load(b),CliMode.CODE));
            assertTrue(c.select(a.id()));assertTrue(c.loop().snapshot().current().memory());assertEquals("READ",c.loop().snapshot().current().profile());
            assertFalse(Files.exists(w.resolve(".agent-cli/memory/shared.json")));
        }
    }
    @Test void cancelledAndFailedRepliesAreNotPersistedAsValidAssistantMessages()throws Exception{
        Path w=workspace();
        try(var c=new TuiSessionController(w,true,new FakeAgentAdapter(r->r.text().equals("error")?FakeAgentAdapter.Scenario.ERROR:FakeAgentAdapter.Scenario.STREAM,40,200))){
            text(c,"cancel");c.submit();until(c,()->!c.loop().snapshot().current().turn().streamingText().isEmpty());
            c.loop().cancelCurrent();c.pump();String id=c.loop().snapshot().currentSessionId();
            assertEquals(0,replies(new CliConversationManager(w).load(id),CliMode.CODE));
            text(c,"error");c.submit();finish(c);assertEquals("FAILED",c.loop().snapshot().current().turn().status());
            assertEquals(0,replies(new CliConversationManager(w).load(id),CliMode.CODE));
        }
        try(var c=new TuiSessionController(w,true,fake())){assertEquals(2,c.loop().snapshot().current().history().size());}
    }
    @Test void userSaveFailureRetainsInputAndStartsNoAdapter()throws Exception{
        Path w=workspace();var calls=new AtomicInteger();AgentAdapter adapter=new AgentAdapter(){
            public Mode mode(){return Mode.FAKE_ONLY;}public RunHandle start(Request r,EventSink e){calls.incrementAndGet();throw new AssertionError();}public void close(){}
        };
        try(var c=new TuiSessionController(w,true,adapter,(m,s)->{throw new IOException("controlled save failure");})){
            text(c,"must remain");assertNull(c.submit());assertEquals("must remain",c.loop().snapshot().current().input().text());
            assertEquals(0,calls.get());assertTrue(c.loop().snapshot().notice().contains("USER_MESSAGE_SAVE_FAILED"));
        }
    }
    @Test void assistantSaveFailureBlocksSwitchAndRetryPersistsExactlyOnce()throws Exception{
        Path w=workspace();var failure=new AtomicBoolean(true);
        try(var c=new TuiSessionController(w,true,fake(),(m,s)->{
            if(failure.get()&&s.histories().values().stream().flatMap(List::stream).anyMatch(message->message.content().startsWith("[FAKE]")))throw new IOException("controlled failure");m.checkpoint(s);
        })){
            assertTrue(c.createSession());String b=c.loop().snapshot().currentSessionId();assertTrue(c.createSession());String a=c.loop().snapshot().currentSessionId();
            text(c,"keep reply");c.submit();finish(c);assertEquals(1,c.unsavedCount());
            assertFalse(c.select(b));assertEquals(a,c.loop().snapshot().currentSessionId());assertEquals(2,c.loop().snapshot().current().history().size());
            assertEquals(0,replies(new CliConversationManager(w).load(a),CliMode.CODE));
            failure.set(false);assertTrue(c.flush(a));assertTrue(c.select(a));
            text(c,"another");c.submit();finish(c);assertEquals(2,replies(new CliConversationManager(w).load(a),CliMode.CODE));
            assertTrue(c.select(b));assertTrue(c.loop().snapshot().current().history().isEmpty());
        }
    }
    @Test void failureAtExitIsExplicitAndDoesNotOverwriteOriginalHistory()throws Exception{
        Path w=workspace();var c=new TuiSessionController(w,true,fake(),(m,s)->{
            if(s.histories().values().stream().flatMap(List::stream).anyMatch(message->message.content().startsWith("[FAKE]")))throw new IOException("failure");m.checkpoint(s);
        });
        text(c,"exit");c.submit();finish(c);String id=c.loop().snapshot().currentSessionId();
        assertFalse(c.prepareExit());assertFalse(c.loop().snapshot().closed());
        assertThrows(IOException.class,c::close);assertEquals(1,c.unsavedCount());assertEquals(2,c.loop().snapshot().current().history().size());
        assertEquals(0,replies(new CliConversationManager(w).load(id),CliMode.CODE));assertTrue(c.loop().snapshot().closed());
    }
    @Test void unmarkedUserWorkspaceIsReadOnlyAndSharedStoreNeverAccessed()throws Exception{
        Path w=Files.createDirectory(root.resolve("user-project"));var saved=legacy(w,CliMode.CHAT,true,"private user text");
        Files.createDirectories(w.resolve(".agent-cli/memory"));Path shared=w.resolve(".agent-cli/memory/shared.json");Files.writeString(shared,"deliberately invalid shared JSON; must not be read");
        byte[] before=Files.readAllBytes(w.resolve(".agent-cli/conversations/"+saved.id()+".json"));
        try(var adapter=fake()){assertThrows(IOException.class,()->new TuiSessionController(w,true,adapter));}
        try(var c=new TuiSessionController(w,false,fake())){
            assertEquals("CHAT",c.loop().snapshot().current().profile());assertTrue(c.loop().snapshot().current().memory());
            assertFalse(c.createSession());assertFalse(c.rename("no"));text(c,"no Fake writes");assertNull(c.submit());
        }
        assertArrayEquals(before,Files.readAllBytes(w.resolve(".agent-cli/conversations/"+saved.id()+".json")));
        assertEquals("deliberately invalid shared JSON; must not be read",Files.readString(shared));
    }
    @Test void metadataSkipsUnselectedSnapshotAndCorruptSelectionKeepsCurrent()throws Exception{
        Path w=workspace();var good=legacy(w,CliMode.CODE,false,"good");String bad=UUID.randomUUID().toString();
        String json="{\"version\":1,\"id\":\""+bad+"\",\"title\":\"bad snapshot\",\"createdAt\":\"2020-01-01T00:00:00Z\",\"updatedAt\":\"2020-01-01T00:00:00Z\",\"snapshot\":{\"summary\":\"private canary, not a summary object\"}}";
        Files.writeString(w.resolve(".agent-cli/conversations/"+bad+".json"),json);
        assertEquals(2,new CliConversationManager(w).headers().size());
        try(var c=new TuiSessionController(w,true,fake())){
            assertEquals(good.id(),c.loop().snapshot().currentSessionId());assertFalse(c.select(bad));assertEquals(good.id(),c.loop().snapshot().currentSessionId());
            assertFalse(c.loop().snapshot().current().history().toString().contains("private canary"));
        }
    }
    @Test void historyFoldsToolsAndRedactsCredentialLikeOldTextAndTitles()throws Exception{
        Path w=workspace();var p=profiles(w);var histories=new EnumMap<CliMode,List<Message>>(p.histories());
        histories.put(CliMode.CODE,List.of(histories.get(CliMode.CODE).get(0),Message.user("old text"),
                Message.assistant("",List.of(new ToolCall("call","read_file","{\"path\":\"a.java\"}"))),
                Message.tool("call","HUGE_TOOL_CANARY".repeat(1000)),Message.assistant("multiline\n中文")));
        var saved=new CliConversationManager(w).create(new CliSessionSnapshot(1,Instant.now().toString(),CliMode.CODE,null,histories,new CliWorkingContext().snapshot()));
        Path file=w.resolve(".agent-cli/conversations/"+saved.id()+".json");String document=Files.readString(file).replace("New session","sk-SyntheticCredentialNotReal").replace("old text","password=SyntheticPasswordOnly");Files.writeString(file,document);
        try(var c=new TuiSessionController(w,false,fake())){
            var view=c.loop().snapshot().current();String preview=view.title()+view.history();
            assertFalse(preview.contains("Synthetic"));assertFalse(preview.contains("HUGE_TOOL_CANARY"));assertTrue(preview.contains("folded"));
            assertTrue(view.history().stream().anyMatch(m->m.text().equals("multiline\n中文")));
        }
    }
    @Test void longHistoryUsesCachedWrappingAndSurvivesScrollAndResize()throws Exception{
        Path w=workspace();var p=profiles(w);var histories=new EnumMap<CliMode,List<Message>>(p.histories());var messages=new ArrayList<Message>();messages.add(histories.get(CliMode.CODE).get(0));
        for(int i=0;i<500;i++){messages.add(Message.user("第 "+i+" 条中文问题\n多行消息"));messages.add(Message.assistant("answer "+i));}histories.put(CliMode.CODE,messages);
        new CliConversationManager(w).create(new CliSessionSnapshot(1,Instant.now().toString(),CliMode.CODE,null,histories,new CliWorkingContext().snapshot()));
        try(var c=new TuiSessionController(w,true,fake())){
            var cache=new ConversationViewport();var first=c.loop().snapshot().current().history();TuiSessionsMain.frame(c.loop().snapshot(),cache,true);long wrapped=cache.wrappedMessages();assertEquals(1000,wrapped);
            c.loop().edit(UiEventLoop.Edit.TEXT,"草稿😀");c.loop().scroll(100);TuiSessionsMain.frame(c.loop().snapshot(),cache,true);
            assertSame(first,c.loop().snapshot().current().history());assertEquals(wrapped,cache.wrappedMessages());
            c.loop().resize(60,12);var frame=TuiSessionsMain.frame(c.loop().snapshot(),cache,true);assertFalse(frame.sidebar());assertEquals(12,frame.lines().size());
            assertTrue(frame.lines().stream().allMatch(line->line.columnLength()<=60));assertEquals(wrapped+1000,cache.wrappedMessages());
        }
    }
    @Test void headersAreSortedAndTruncatedJsonFailsWithoutHanging()throws Exception{
        Path w=workspace();var a=legacy(w,CliMode.CODE,false,"a");Thread.sleep(3);var b=legacy(w,CliMode.CODE,false,"b");
        var m=new CliConversationManager(w);assertEquals(b.id(),m.headers().get(0).id());assertEquals(a.id(),m.headers().get(1).id());
        Files.writeString(w.resolve(".agent-cli/conversations/"+b.id()+".json"),"{");
        assertThrows(IOException.class,m::headers);
    }
    @Test void readOnlyMarkedWorkspaceCannotPersistEvenIfCallerBypassesInputController()throws Exception{
        Path w=workspace();var saved=legacy(w,CliMode.CODE,false,"original");Path file=w.resolve(".agent-cli/conversations/"+saved.id()+".json");byte[] before=Files.readAllBytes(file);
        try(var c=new TuiSessionController(w,false,fake())){
            text(c,"bypassed submit");c.loop().submit();finish(c);
            assertTrue(c.loop().snapshot().notice().contains("NOT_SAVED"));assertEquals(0,c.unsavedCount());
        }
        assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void unloadedSessionsShowDatesButNoPrivateHistories()throws Exception{
        Path w=workspace();var a=legacy(w,CliMode.READ,true,"A private");var b=legacy(w,CliMode.CHAT,false,"B private");
        try(var c=new TuiSessionController(w,true,fake())){
            assertEquals(b.id(),c.loop().snapshot().currentSessionId());
            var other=c.loop().snapshot().sessions().stream().filter(s->s.sessionId().equals(a.id())).findFirst().orElseThrow();
            assertFalse(other.updatedAt().isBlank());assertTrue(other.history().isEmpty());assertEquals("NOT_LOADED",other.profile());
            assertTrue(c.select(a.id()));assertEquals("READ",c.loop().snapshot().current().profile());assertTrue(c.loop().snapshot().current().memory());
        }
    }
    @Test void malformedUserStoreCannotLeakJsonTokensThroughEntrypointErrors()throws Exception{
        Path w=Files.createDirectory(root.resolve("inspect"));Path directory=Files.createDirectories(w.resolve(".agent-cli/conversations"));
        Files.writeString(directory.resolve(UUID.randomUUID()+".json"),"{\"snapshot\":SYNTHETIC_SECRET_TOKEN}");
        var error=assertThrows(IOException.class,()->TuiSessionsMain.main(new String[]{"--inspect",w.toString(),"--headless-demo"}));
        assertTrue(error.getMessage().startsWith("TUI2_FAILED"));assertFalse(error.getMessage().contains("SYNTHETIC"));assertNull(error.getCause());assertEquals(0,error.getSuppressed().length);
    }
}
