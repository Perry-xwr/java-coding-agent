package com.agent.tui.prototype;

import org.jline.terminal.*;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.*;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.agent.tui.prototype.PrototypeState.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class TuiPrototypeTest {
    @Test void jlineBindingReaderDecodesChineseAndTerminalCapabilityKeys() throws Exception {
        try(var caps=new DumbTerminal("caps","xterm",InputStream.nullInputStream(),OutputStream.nullOutputStream(),StandardCharsets.UTF_8)) {
            String input="中"+org.jline.keymap.KeyMap.key(caps,Capability.key_left)+"\u007f"
                    +org.jline.keymap.KeyMap.key(caps,Capability.key_ppage)+"\t"
                    +org.jline.keymap.KeyMap.key(caps,Capability.key_down)+"\r"+org.jline.keymap.KeyMap.ctrl('Q');
            try(var terminal=new DumbTerminal("keys","xterm",new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    OutputStream.nullOutputStream(),StandardCharsets.UTF_8)) {
                var reader=new org.jline.keymap.BindingReader(terminal.reader());var map=TuiPrototypeMain.keys(terminal);
                for(var expected:List.of(TuiPrototypeMain.Key.TEXT,TuiPrototypeMain.Key.LEFT,TuiPrototypeMain.Key.BACKSPACE,
                        TuiPrototypeMain.Key.PAGE_UP,TuiPrototypeMain.Key.TAB,TuiPrototypeMain.Key.DOWN,
                        TuiPrototypeMain.Key.ENTER,TuiPrototypeMain.Key.EXIT)) assertEquals(expected,reader.readBinding(map));
            }
        }
    }
    @Test void vtCsiAndApplicationCursorArrowsAreBindingsAndUnknownCsiIsDiscarded()throws Exception{
        String arrows="\u001b[A\u001b[B\u001b[C\u001b[D\u001bOA\u001bOB\u001bOC\u001bOD";
        try(var terminal=new DumbTerminal("keys","xterm",new ByteArrayInputStream((arrows+"\u001b[999zX").getBytes(StandardCharsets.UTF_8)),OutputStream.nullOutputStream(),StandardCharsets.UTF_8)){
            var keys=TuiPrototypeMain.keys(terminal);var reader=new org.jline.keymap.BindingReader(terminal.reader());
            for(var expected:List.of(TuiPrototypeMain.Key.UP,TuiPrototypeMain.Key.DOWN,TuiPrototypeMain.Key.RIGHT,TuiPrototypeMain.Key.LEFT,
                    TuiPrototypeMain.Key.UP,TuiPrototypeMain.Key.DOWN,TuiPrototypeMain.Key.RIGHT,TuiPrototypeMain.Key.LEFT))
                assertEquals(expected,reader.readBinding(keys));
            assertEquals(TuiPrototypeMain.Key.CONTROL_SEQUENCE,reader.readBinding(keys));
            TuiPrototypeMain.discardControlSequenceTail(reader);
            assertEquals(TuiPrototypeMain.Key.TEXT,reader.readBinding(keys));assertEquals("X",reader.getLastBinding());
        }
    }
    @Test void fixedDataHasFourSessionsTenMessagesAndMemoryOffByDefaultForFirst() {
        var state=new PrototypeState();
        assertEquals(4,state.sessions.size());
        assertEquals(10,state.sessions.stream().mapToInt(s->s.messages.size()).sum());
        assertFalse(state.current().memory);
        assertTrue(state.sessions.get(1).memory);
    }
    @Test void layoutHandlesChineseColumnsNarrowSidebarAndResize() {
        var state=new PrototypeState();var renderer=new PrototypeRenderer();
        for(int[] size:new int[][]{{110,26},{90,20},{60,12},{30,8},{15,4},{1,1},{160,40}}) {
            var frame=renderer.frame(state,size[0],size[1]);
            assertEquals(size[1],frame.lines().size());
            assertTrue(frame.lines().stream().allMatch(line->line.columnLength()<=size[0]));
            assertTrue(frame.cursorColumn()<size[0] && frame.cursorRow()<size[1]);
            assertEquals(size[0]>=90 && size[1]>=8,frame.sidebar());
        }
        assertEquals(4,new AttributedString("中文").columnLength());
        assertEquals(List.of("中文","测试"),PrototypeRenderer.wrap("中文测试",4));
        assertEquals(List.of("中文","第二行"),PrototypeRenderer.wrap("中文\n第二行",10));
    }
    @Test void sidebarEnterEscapeAndPerSessionDraftsUseSameState() throws Exception {
        var state=new PrototypeState();var queue=new ArrayBlockingQueue<PrototypeState.Event>(256);
        try(var runtime=new SimulatedRuntime(queue,0)) {
            state.type("中文草稿");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.TAB,"");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.DOWN,"");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.ESC,"");
            assertEquals(0,state.active);assertEquals("中文草稿",state.input.toString());
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.TAB,"");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.DOWN,"");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.ENTER,"");
            assertEquals(1,state.active);assertEquals("",state.input.toString());assertTrue(state.current().memory);
            state.selected=0;state.choose();assertEquals("中文草稿",state.input.toString());assertFalse(state.current().memory);
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.LEFT,"");
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.BACKSPACE,"");
            assertEquals("中文稿",state.input.toString());
        }
    }
    @Test void chineseCodePointsLongInputCursorAndScrollingAreBounded() throws Exception {
        var state=new PrototypeState();state.type("中文😀");state.input.backspace();assertEquals("中文",state.input.toString());
        state.type("界".repeat(100));
        var frame=new PrototypeRenderer().frame(state,35,10);
        assertTrue(frame.cursorColumn()<35);assertEquals(8,frame.cursorRow());
        try(var runtime=new SimulatedRuntime(new ArrayBlockingQueue<>(256),0)) {
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.PAGE_UP,"");assertEquals(5,state.current().scroll);
            TuiPrototypeMain.act(state,runtime,TuiPrototypeMain.Key.PAGE_DOWN,"");assertEquals(0,state.current().scroll);
        }
    }
    @Test void taggedOldEventsCannotPolluteSelectedSessionOrNewTurn() {
        var state=new PrototypeState();state.type("first");String turn=state.begin();String id=state.current().id;
        state.selected=1;state.choose();int count=state.current().messages.size();
        assertTrue(state.accept(new PrototypeState.Event(id,turn,DELTA,"old session delta")));
        assertEquals(count,state.current().messages.size());
        assertFalse(state.accept(new PrototypeState.Event(id,"stale",DELTA,"must not enter")));
        state.selected=0;state.choose();
        assertTrue(state.current().messages.get(state.current().messages.size()-1).text().contains("old session delta"));
        assertTrue(state.accept(new PrototypeState.Event(id,turn,COMPLETED,"done")));
        assertFalse(state.accept(new PrototypeState.Event(id,turn,DELTA,"late after finish")));
    }
    @Test void backgroundStreamRequiresCorrectSimulationApprovalAndIsAppliedOnlyByOwner() throws Exception {
        var state=new PrototypeState();state.type("模拟输入");String turn=state.begin(),id=state.current().id;
        var queue=new ArrayBlockingQueue<PrototypeState.Event>(256);var kinds=new ArrayList<PrototypeState.Kind>();
        try(var runtime=new SimulatedRuntime(queue,0)) {
            runtime.start(id,turn,false,false);
            while(state.current().running) {
                var event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);kinds.add(event.kind());state.accept(event);
                if(event.kind()==APPROVAL_REQUIRED) {
                    assertFalse(runtime.resolve("other",turn,true));assertFalse(runtime.resolve(id,"wrong",true));
                    assertTrue(runtime.resolve(id,turn,true));assertFalse(runtime.resolve(id,turn,true));
                }
            }
        }
        assertTrue(kinds.containsAll(List.of(STARTED,APPROVAL_REQUIRED,DELTA,TOOL_STARTED,TOOL_FINISHED,COMPLETED)));
        assertEquals(6,kinds.stream().filter(k->k==DELTA).count());
        var error=new AtomicReference<Throwable>();
        Thread worker=new Thread(()->{try{state.accept(new PrototypeState.Event(id,turn,DELTA,"x"));}catch(Throwable t){error.set(t);}});
        worker.start();worker.join();assertInstanceOf(IllegalStateException.class,error.get());
    }
    @Test void simulatedDenialStartsNoToolAndWorkersStopOnExit() throws Exception {
        var state=new PrototypeState();state.type("deny");String turn=state.begin(),id=state.current().id;
        var queue=new ArrayBlockingQueue<PrototypeState.Event>(256);var runtime=new SimulatedRuntime(queue,0);
        var kinds=new ArrayList<PrototypeState.Kind>();
        try {
            runtime.start(id,turn,false,false);
            while(state.current().running) {
                var event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);state.accept(event);kinds.add(event.kind());
                if(event.kind()==APPROVAL_REQUIRED)runtime.resolve(id,turn,false);
            }
            assertFalse(kinds.contains(TOOL_STARTED));assertEquals("DENIED (FAKE)",state.current().status);
        } finally {runtime.close();}
        assertTrue(runtime.terminated());assertThrows(IllegalStateException.class,()->runtime.start(id,turn,false,false));
    }
    @Test void simulatedFailureIsVisibleAndNotReportedAsSuccess() throws Exception {
        var state=new PrototypeState();state.type("fail");String turn=state.begin(),id=state.current().id;
        var queue=new ArrayBlockingQueue<PrototypeState.Event>(256);
        try(var runtime=new SimulatedRuntime(queue,0)) {
            runtime.start(id,turn,false,true);
            while(state.current().running) {
                var event=queue.poll(2,TimeUnit.SECONDS);assertNotNull(event);state.accept(event);
                if(event.kind()==APPROVAL_REQUIRED)runtime.resolve(id,turn,true);
            }
        }
        assertEquals("FAILED (FAKE)",state.current().status);assertTrue(state.current().tool.contains("FAIL:"));
    }
    @Test void displayUpdatesDifferentiallyAndRestoresAttributesAfterException() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var terminal=new DumbTerminal("test","xterm",InputStream.nullInputStream(),bytes,StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(110,26));String attributes=terminal.getAttributes().toString();
            var scope=new PrototypeScreen(terminal);
            assertThrows(IOException.class,()->{
                try(scope) {
                    scope.enter();var frame=new PrototypeRenderer().frame(new PrototypeState(),110,26);
                    scope.draw(frame,110,26);int initial=bytes.size();scope.draw(frame,110,26);
                    assertTrue(bytes.size()-initial<20,"unchanged frame must not be appended again");
                    scope.draw(new PrototypeRenderer().frame(new PrototypeState(),60,12),60,12);
                    throw new IOException("test exit");
                }
            });
            int restored=bytes.size();scope.close();assertEquals(restored,bytes.size());
            assertEquals(attributes,terminal.getAttributes().toString());
            String exitSequence=Curses.tputs(terminal.getStringCapability(Capability.exit_ca_mode));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(exitSequence));
        }
    }
    @Test void controlSequencesCannotBeRenderedAsInstructionsToTerminal() {
        assertFalse(PrototypeRenderer.clean("a\u001b[2J\tb").contains("\u001b"));
        assertEquals("a b",PrototypeRenderer.clean("a\tb"));
    }
}
