package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tool.*;
import com.agent.tui.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Tui5PresentationTest {
    FakeAgentAdapter fake(){return new FakeAgentAdapter(r->FakeAgentAdapter.Scenario.STREAM,40,100);}
    UiEventLoop loop(){return new UiEventLoop(List.of(new UiEventLoop.SessionSeed("A","中文会话",false,List.of()),new UiEventLoop.SessionSeed("B","第二会话",true,List.of())),fake(),16);}
    @Test void chineseCodePointEditingLogicalLinesAndDraftIsolation(){
        try(var ui=loop()){
            ui.edit(UiEventLoop.Edit.TEXT,"中文🚀");ui.edit(UiEventLoop.Edit.LEFT,"");ui.edit(UiEventLoop.Edit.BACKSPACE,"");assertEquals("中🚀",ui.snapshot().current().input().text());
            ui.edit(UiEventLoop.Edit.NEWLINE,"");ui.edit(UiEventLoop.Edit.TEXT,"第二行");assertEquals("中\n第二行🚀",ui.snapshot().current().input().text());
            ui.edit(UiEventLoop.Edit.UP,"");assertEquals(1,ui.snapshot().current().input().cursor());ui.edit(UiEventLoop.Edit.DOWN,"");assertEquals(5,ui.snapshot().current().input().cursor());
            ui.select("B");assertTrue(ui.snapshot().current().input().text().isEmpty());ui.select("A");assertEquals("中\n第二行🚀",ui.snapshot().current().input().text());
            ui.chatFocus(true);assertTrue(ui.snapshot().chatFocus());ui.focus(true);assertFalse(ui.snapshot().chatFocus());
        }
    }
    @Test void wrappedInputKeepsCursorVisibleAndSensitiveDraftsAreNotPainted(){
        var layout=InputViewport.layout(new UiSnapshot.Input("中e\u0301🚀",4),4,2);assertEquals(1,layout.cursorRow());assertEquals(2,layout.cursorColumn());
        layout=InputViewport.layout(new UiSnapshot.Input("甲\n乙\n丙\n丁",7),8,2);assertEquals(List.of("丙","丁"),layout.lines());assertEquals(1,layout.cursorRow());
        String canary="sk-syntheticsecret123456";layout=InputViewport.layout(new UiSnapshot.Input(canary,canary.length()),40,3);assertTrue(layout.hidden());assertFalse(String.join("",layout.lines()).contains(canary));
    }
    @Test void insertionLimitAndBusySubmitPreserveWholeDraft(){
        try(var ui=loop()){
            ui.edit(UiEventLoop.Edit.TEXT,"解释中文");assertNotNull(ui.submit());ui.edit(UiEventLoop.Edit.TEXT,"等待中的草稿\n第二行");String draft=ui.snapshot().current().input().text();assertNull(ui.submit());assertEquals(draft,ui.snapshot().current().input().text());
            ui.edit(UiEventLoop.Edit.TEXT,"字".repeat(16384));assertEquals(draft,ui.snapshot().current().input().text());assertTrue(ui.snapshot().notice().contains("INPUT_LIMIT"));
        }
    }
    @Test void layoutAdaptsToResizeNarrowSessionPickerAndMultiLineInput(){
        try(var ui=loop()){
            ui.edit(UiEventLoop.Edit.TEXT,"中文输入".repeat(20)+"\n下一段");var viewport=new ConversationViewport();
            for(int[] size:List.of(new int[]{120,28},new int[]{70,16},new int[]{30,8},new int[]{12,3})){
                ui.resize(size[0],size[1]);var frame=TuiSessionsMain.frame(ui.snapshot(),viewport,true);
                assertEquals(size[1],frame.lines().size());assertTrue(frame.cursorRow()>=0&&frame.cursorRow()<size[1]);assertTrue(frame.cursorColumn()>=0&&frame.cursorColumn()<size[0]);
                assertTrue(frame.lines().stream().allMatch(line->line.columnLength()<=size[0]));
            }
            ui.resize(70,18);ui.focus(true);ui.moveSelection(1);String shown=TuiSessionsMain.frame(ui.snapshot(),viewport,true).lines().toString();assertTrue(shown.contains("第二会话"));assertTrue(shown.contains(">"));
        }
    }
    @Test void partialTextAndToolFailureAreExplicitWithoutLeakingRawBodies(){
        try(var ui=new UiEventLoop(List.of(new UiEventLoop.SessionSeed("A","title",false,List.of(new UiSnapshot.Chat("Agent","未完成文本","FAILED")))),fake(),8)){
            String text=TuiSessionsMain.frame(ui.snapshot(),new ConversationViewport(),true).lines().toString();assertTrue(text.contains("partial: FAILED"));
            var result=ToolResult.failureWithOutput(ToolErrorCode.TEST_FAILED,"untrusted", "DO_NOT_DISPLAY_RAW_OUTPUT",Map.of("testStatus","TEST_FAILED","testsRun",1,"diagnosticSummary","expected 5, actual 6"));
            assertEquals("TEST_FAILED tests=1",TuiToolPresentation.summary(result));assertTrue(TuiToolPresentation.details(result).contains("expected 5"));assertFalse(TuiToolPresentation.details(result).contains("DO_NOT_DISPLAY_RAW_OUTPUT"));
            assertFalse(TuiToolPresentation.details(ToolResult.success("sk-syntheticsecret123456")).contains("sk-syntheticsecret"));
        }
    }
    @Test void tenThousandMessageFixtureDoesNotRewrapOnTypingScrollingOrStatus(){
        var history=new ArrayList<UiSnapshot.Chat>();for(int i=0;i<10000;i++)history.add(new UiSnapshot.Chat(i%2==0?"You":"Agent","中文历史 "+i+" - offline fixture"));
        try(var ui=new UiEventLoop(List.of(new UiEventLoop.SessionSeed("A","large",false,history)),fake(),8)){
            var viewport=new ConversationViewport();long start=System.nanoTime();TuiSessionsMain.frame(ui.snapshot(),viewport,true);long initial=System.nanoTime()-start;
            assertEquals(10000,viewport.wrappedMessages());start=System.nanoTime();
            for(int i=0;i<100;i++){ui.edit(UiEventLoop.Edit.TEXT,"字");ui.scroll(1);ui.notice("status "+i);TuiSessionsMain.frame(ui.snapshot(),viewport,true);}
            assertEquals(10000,viewport.wrappedMessages());long repeated=System.nanoTime()-start;ui.resize(60,14);TuiSessionsMain.frame(ui.snapshot(),viewport,true);assertEquals(20000,viewport.wrappedMessages());
            System.out.println("TUI5_RENDER_FIXTURE messages=10000 repeatedFrames=100 rewrappedBeforeResize=0 initialMs="+initial/1_000_000+" repeatMs="+repeated/1_000_000);
        }
    }
}
