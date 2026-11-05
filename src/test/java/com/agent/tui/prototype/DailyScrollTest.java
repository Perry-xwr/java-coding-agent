package com.agent.tui.prototype;

import com.agent.tui.runtime.*;
import org.jline.keymap.BindingReader;
import org.jline.terminal.*;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyScrollTest {
    static void write(PipedOutputStream stream,String value)throws IOException{stream.write(value.getBytes(StandardCharsets.UTF_8));stream.flush();}
    UiEventLoop loop(int count){
        var history=new ArrayList<UiSnapshot.Chat>();
        for(int i=0;i<count;i++)history.add(new UiSnapshot.Chat("Agent","消息编号 "+i+" 中文历史"));
        return new UiEventLoop(List.of(new UiEventLoop.SessionSeed("A","A",false,history),new UiEventLoop.SessionSeed("B","B",false,history)),new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.STREAM,1,100),16);
    }
    @Test void coordinatesRouteToRenderedConversationWithoutChangingFocus(){
        try(var ui=loop(100)){
            ui.resize(120,25);var v=new ConversationViewport();
            for(int i=0;i<3;i++)ui.upsertSession(new UiEventLoop.SessionSeed("extra"+i,"extra"+i,false,List.of()),false);
            var before=TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString();
            TuiSessionsMain.routeMouse(new MouseEvent(MouseEvent.Type.Wheel,MouseEvent.Button.WheelDown,EnumSet.noneOf(MouseEvent.Modifier.class),2,5),ui,3);
            assertEquals("B",ui.snapshot().selectedSessionId());
            assertEquals(before,TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString());
            TuiSessionsMain.routeMouse(new MouseEvent(MouseEvent.Type.Wheel,MouseEvent.Button.WheelUp,EnumSet.noneOf(MouseEvent.Modifier.class),60,5),ui,3);
            assertNotEquals(before,TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString());
            assertFalse(ui.snapshot().sessionFocus());
            ui.edit(UiEventLoop.Edit.TEXT,"中文草稿");
            TuiSessionsMain.routeMouse(new MouseEvent(MouseEvent.Type.Wheel,MouseEvent.Button.WheelDown,EnumSet.noneOf(MouseEvent.Modifier.class),60,23),ui,3);
            assertEquals("中文草稿",ui.snapshot().current().input().text());
        }
    }
    @Test void fiftyMessagesOldestAndLatestAndLocalOutputAnchor(){
        try(var ui=loop(50)){
            var v=new ConversationViewport();v.visible(ui.snapshot().current(),30,8);
            ui.scroll(25);var before=v.visible(ui.snapshot().current(),30,8);
            ui.localOutput("新的本地状态");assertEquals(before,v.visible(ui.snapshot().current(),30,8));
            ui.scroll(Integer.MAX_VALUE);assertTrue(v.visible(ui.snapshot().current(),30,8).get(0).contains("消息编号 0"));
            ui.follow();assertTrue(String.join("",v.visible(ui.snapshot().current(),30,8)).contains("新的本地状态"));
        }
    }
    @Test void tenThousandLinesRemainAccessibleAndScrollingDoesNotRewrap(){
        try(var ui=loop(6000)){
            var v=new ConversationViewport();v.visible(ui.snapshot().current(),30,8);assertEquals(6000,v.wrappedMessages());
            ui.scroll(Integer.MAX_VALUE);assertTrue(v.visible(ui.snapshot().current(),30,8).get(0).contains("消息编号 0"));
            for(int i=0;i<100;i++){ui.scroll(-3);v.visible(ui.snapshot().current(),30,8);}
            assertEquals(6000,v.wrappedMessages());
            ui.follow();assertTrue(String.join("",v.visible(ui.snapshot().current(),30,8)).contains("5999"));
        }
    }
    @Test void sessionAnchorsAndResizeAreIndependent(){
        try(var ui=loop(1000)){
            var v=new ConversationViewport();v.visible(ui.snapshot().current(),32,10);ui.scroll(300);
            var before=v.visible(ui.snapshot().current(),32,10);ui.select("B");
            assertTrue(String.join("",v.visible(ui.snapshot().current(),32,10)).contains("999"));
            ui.select("A");assertEquals(before,v.visible(ui.snapshot().current(),32,10));
            var narrow=v.visible(ui.snapshot().current(),12,10);assertTrue(String.join("",narrow).contains("消息编号"));
            assertEquals(before.get(0),v.visible(ui.snapshot().current(),32,10).get(0));
        }
    }
    UiSnapshot.Session streaming(UiSnapshot.Session s,String text){
        return new UiSnapshot.Session(s.sessionId(),s.title(),false,s.provider(),s.model(),s.history(),s.input(),s.scroll(),
                new UiSnapshot.Turn("turn","RUNNING",text,1,List.of(),null,null,""),s.profile(),s.updatedAt(),false,"keyword",s.capabilities(),s.scrollRevision(),s.scrollDelta());
    }
    @Test void streamGrowsWithoutMovingHistoricalAnchorAndDownRestoresFollow(){
        try(var ui=loop(50)){
            var v=new ConversationViewport();v.visible(streaming(ui.snapshot().current(),"甲".repeat(60)),20,8);ui.scroll(20);
            var before=v.visible(streaming(ui.snapshot().current(),"甲".repeat(60)),20,8);
            assertEquals(before,v.visible(streaming(ui.snapshot().current(),"甲".repeat(800)),20,8));
            ui.scroll(-Integer.MAX_VALUE);v.visible(streaming(ui.snapshot().current(),"甲".repeat(800)),20,8);
            assertTrue(String.join("",v.visible(streaming(ui.snapshot().current(),"甲".repeat(800)+"最新尾部"),20,8)).contains("最新尾部"));
            ui.scroll(3);assertFalse(v.visible(streaming(ui.snapshot().current(),"甲".repeat(800)+"最新尾部"),20,8).toString().contains("最新尾部"));
        }
    }
    @Test void wheelIsDecodedByJlineAndCannotEnterDraftOrApproval(){
        try(var ui=loop(50)){
            ui.edit(UiEventLoop.Edit.TEXT,"/approval confirm exact APPROVE nonce");String draft=ui.snapshot().current().input().text();
            // JLine 3.26.3 Normal tracking uses the UTF-8 X10 report (Cb,Cx,Cy).
            for(int code:new int[]{96,97,32,35}){
                byte[] bytes=(""+(char)code+(char)113+(char)40).getBytes(StandardCharsets.UTF_8);
                try(var t=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(new ByteArrayInputStream(bytes),new ByteArrayOutputStream()).build()){
                    long previous=ui.snapshot().current().scrollDelta();TuiSessionsMain.mouse(t,new BindingReader(t.reader()),ui);
                    assertEquals(code==96?3:code==97?-3:0,ui.snapshot().current().scrollDelta()-previous);
                }catch(IOException failure){throw new UncheckedIOException(failure);}
                assertEquals(draft,ui.snapshot().current().input().text());assertNull(ui.snapshot().localApproval());
            }
        }
    }
    @Test void sgrMouseMovementWheelAndClickUseDailyBindingReaderWithoutEnteringDraft()throws Exception{
        try(var ui=loop(50)){
            ui.resize(120,25);ui.edit(UiEventLoop.Edit.TEXT,"/approval confirm exact APPROVE nonce");String draft=ui.snapshot().current().input().text();
            StringBuilder reports=new StringBuilder();for(int i=0;i<100;i++)reports.append("\u001b[<35;70;28M");
            reports.append("\u001b[<64;70;8M"); // wheel up, chat region
            reports.append("\u001b[<0;5;6M\u001b[<0;5;6m"); // sidebar press/release selects session B
            reports.append("x");
            try(var t=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(new ByteArrayInputStream(reports.toString().getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream()).build()){
                var keys=TuiPrototypeMain.keys(t);TuiSessionsMain.bindMouseKeys(keys,t);var reader=new BindingReader(t.reader());
                for(int i=0;i<100+1+2;i++){
                    assertTrue(reader.peekCharacter(500)>=0);
                    var key=reader.readBinding(keys);assertEquals(TuiPrototypeMain.Key.MOUSE,key,"lastBinding="+reader.getLastBinding()+" buffered="+reader.getCurrentBuffer());
                    if(TerminalMouseBinding.sgrPrefix().equals(reader.getLastBinding()))TuiSessionsMain.sgrMouse(reader,ui,null,null);else fail("unexpected mouse protocol");
                }
                assertTrue(reader.peekCharacter(500)>=0);assertEquals(TuiPrototypeMain.Key.TEXT,reader.readBinding(keys,null,false));assertEquals("x",reader.getLastBinding());
            }
            assertEquals(draft,ui.snapshot().current().input().text());assertEquals("B",ui.snapshot().selectedSessionId());assertNull(ui.snapshot().localApproval());
            assertTrue(ui.snapshot().current().scrollDelta()>0,"SGR wheel should scroll without changing input focus");
        }
    }
    @Test void sgrReportSplitAcrossUnderlyingReadsStaysOutOfDraft()throws Exception{
        try(var ui=loop(20);var pipe=new PipedInputStream(256);var writer=new PipedOutputStream(pipe);
            var t=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(pipe,new ByteArrayOutputStream()).build()){
            ui.resize(120,25);ui.edit(UiEventLoop.Edit.TEXT,"draft");var keys=TuiPrototypeMain.keys(t);TuiSessionsMain.bindMouseKeys(keys,t);var reader=new BindingReader(t.reader());
            var release=new java.util.concurrent.CountDownLatch(1);
            var producer=new Thread(()->{try{write(writer,"\u001b[");Thread.sleep(40);write(writer,"<64;70;8");Thread.sleep(40);write(writer,"MZ");release.await();}catch(Exception failure){throw new RuntimeException(failure);}});
            producer.start();assertTrue(reader.peekCharacter(1000)>=0);assertEquals(TuiPrototypeMain.Key.MOUSE,reader.readBinding(keys));
            assertEquals(TerminalMouseBinding.sgrPrefix(),reader.getLastBinding());TuiSessionsMain.sgrMouse(reader,ui,null,null);
            assertTrue(reader.peekCharacter(1000)>=0);assertEquals(TuiPrototypeMain.Key.TEXT,reader.readBinding(keys));assertEquals("Z",reader.getLastBinding());
            release.countDown();producer.join(1000);assertFalse(producer.isAlive());assertEquals("draft",ui.snapshot().current().input().text());assertTrue(ui.snapshot().current().scrollDelta()>0);
        }
    }
    @Test void pageKeysRemainBoundAndScreenRestoresMouseAfterFailure()throws Exception{
        var out=new ByteArrayOutputStream();
        try(var t=TerminalBuilder.builder().system(false).type("xterm").streams(new ByteArrayInputStream(new byte[0]),out).build()){
            var keys=TuiPrototypeMain.keys(t);assertEquals(TuiPrototypeMain.Key.DELETE,keys.getBound(org.jline.keymap.KeyMap.key(t,Capability.key_dc)));
            assertEquals(TuiPrototypeMain.Key.PAGE_UP,keys.getBound(org.jline.keymap.KeyMap.key(t,Capability.key_ppage)));
            assertEquals(TuiPrototypeMain.Key.PAGE_DOWN,keys.getBound(org.jline.keymap.KeyMap.key(t,Capability.key_npage)));
            var attributes=new Attributes(t.getAttributes());
            assertThrows(IllegalStateException.class,()->{try(var screen=new PrototypeScreen(t,true)){screen.enter();throw new IllegalStateException("synthetic render failure");}});
            assertEquals(attributes.toString(),t.getAttributes().toString());
        }
        String output=out.toString(StandardCharsets.UTF_8);assertTrue(output.contains("\u001b[?1002l"));assertTrue(output.contains("\u001b[?1003l"));assertTrue(output.contains("\u001b[?1002h"));assertTrue(output.contains("\u001b[?1000l"));
    }
    @Test void streamAnchorSurvivesLocalOutputAndResize(){
        try(var ui=loop(10)){
            var v=new ConversationViewport();String text="";
            for(int i=0;i<100;i++)text+="段落"+i+"中文内容\n";
            v.visible(streaming(ui.snapshot().current(),text),30,8);ui.scroll(25);
            var before=v.visible(streaming(ui.snapshot().current(),text),30,8);
            ui.localOutput("独立状态消息");
            assertEquals(before,v.visible(streaming(ui.snapshot().current(),text+"新增片段\n".repeat(10)),30,8));
            assertEquals(before.get(0),v.visible(streaming(ui.snapshot().current(),text),20,8).get(0));
        }
    }
    @Test void moreThanEightSessionsKeepNavigationWhenWrappingCacheIsEvicted(){
        try(var ui=loop(50)){
            var v=new ConversationViewport();v.visible(ui.snapshot().current(),30,8);ui.scroll(23);var before=v.visible(ui.snapshot().current(),30,8);
            for(int i=0;i<10;i++){String id="session"+i;ui.upsertSession(new UiEventLoop.SessionSeed(id,id,false,List.of()),false);ui.select(id);v.visible(ui.snapshot().current(),30,8);}
            ui.select("A");assertEquals(before,v.visible(ui.snapshot().current(),30,8));
        }
    }
    @Test void incompleteMouseReportFailsWithoutChangingDraft()throws Exception{
        try(var ui=loop(1);var t=TerminalBuilder.builder().system(false).type("xterm").streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build()){
            ui.edit(UiEventLoop.Edit.TEXT,"安全草稿");assertThrows(IllegalStateException.class,()->TuiSessionsMain.mouse(t,new BindingReader(t.reader()),ui));
            assertEquals("安全草稿",ui.snapshot().current().input().text());
        }
    }
    @Test void scrollbarDragsRenderedHistoryWithoutChangingInputFocus(){
        try(var ui=loop(1200)){
            ui.resize(120,25);var v=new ConversationViewport();String last=TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString();
            int y=2+v.thumbStart("A");
            assertTrue(v.pointer(new MouseEvent(MouseEvent.Type.Pressed,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),119,y),ui));
            assertTrue(v.pointer(new MouseEvent(MouseEvent.Type.Dragged,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),119,2),ui));
            String first=TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString();assertNotEquals(last,first);assertTrue(first.contains("消息编号 0"));assertFalse(ui.snapshot().chatFocus());assertFalse(ui.snapshot().sessionFocus());
            assertTrue(v.pointer(new MouseEvent(MouseEvent.Type.Dragged,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),119,1000),ui));
            assertEquals(last,TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString());
            v.pointer(new MouseEvent(MouseEvent.Type.Released,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),119,20),ui);
            v.pointer(new MouseEvent(MouseEvent.Type.Pressed,MouseEvent.Button.Button1,EnumSet.noneOf(MouseEvent.Modifier.class),119,2),ui);
            assertNotEquals(last,TuiSessionsMain.frame(ui.snapshot(),v,true).lines().toString());
        }
    }    @Test void nativeJniPrefixDoesNotDependOnTerminfo(){
        assertEquals("\u001b[M",TerminalMouseBinding.prefix(true,null));
        assertEquals("custom",TerminalMouseBinding.prefix(false,"custom"));
        assertNull(TerminalMouseBinding.prefix(false,null));
    }
    @Test void scrollbarReachesFinalDisplayFrame()throws Exception{
        try(var ui=loop(1000);var t=TerminalBuilder.builder().system(false).type("xterm").streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build()){
            ui.resize(120,30);var frame=TuiSessionsMain.frame(ui.snapshot(),new ConversationViewport(),true);
            assertTrue(frame.lines().stream().anyMatch(line->line.toString().endsWith("┃")));
            assertTrue(frame.lines().stream().anyMatch(line->line.toString().endsWith("│")));
            var output=new ByteArrayOutputStream();
            try(var displayTerminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(new ByteArrayInputStream(new byte[0]),output).build();var screen=new PrototypeScreen(displayTerminal,true)){
                screen.enter();screen.draw(frame,120,30);
            }
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("┃"));
        }
    }}
