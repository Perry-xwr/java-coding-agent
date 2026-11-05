package com.agent.tui.prototype;

import com.agent.tui.runtime.*;
import org.jline.keymap.BindingReader;
import org.jline.terminal.*;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TUI-1 offline host. Reuses TUI-0 rendering/screen; the new event loop is the only authoritative state. */
public final class TuiRuntimeMain {
    static List<UiEventLoop.SessionSeed> seeds(){
        return new PrototypeState().sessions.stream().map(s->new UiEventLoop.SessionSeed(s.id,s.title,s.memory,
                s.messages.stream().map(m->new UiSnapshot.Chat(m.role(),m.text())).toList())).toList();
    }
    static UiEventLoop create(){
        return new UiEventLoop(seeds(),new FakeAgentAdapter(request->request.text().startsWith("/fail")
                ?FakeAgentAdapter.Scenario.TOOL_FAILURE:request.text().startsWith("/error")?FakeAgentAdapter.Scenario.ERROR
                :request.text().startsWith("/approve")?FakeAgentAdapter.Scenario.APPROVAL:FakeAgentAdapter.Scenario.TOOL_SUCCESS,70,30_000),64);
    }
    /** One-way, discarded render projection; not a second session store or mutable application model. */
    public static PrototypeRenderer.Frame frame(UiSnapshot snapshot){
        var view=new PrototypeState();view.sessions.clear();
        for(var session:snapshot.sessions()){
            var target=new PrototypeState.Session(session.sessionId(),session.title(),session.memory());
            for(var chat:session.history())target.messages.add(new PrototypeState.Chat(chat.role(),chat.text(),"history"));
            var turn=session.turn();
            if(turn!=null){
                target.turnId=turn.turnId();target.running=turn.running();target.status=turn.status();
                target.approval=turn.approval()!=null&&turn.approval().status().equals("PENDING");
                if(turn.running())target.messages.add(new PrototypeState.Chat("Agent",turn.streamingText(),turn.turnId()));
                if(!turn.tools().isEmpty()){
                    var tool=turn.tools().get(turn.tools().size()-1);target.tool=tool.status()+": "+tool.name()+" "+tool.summary();
                }
                if(!turn.error().isBlank())target.tool="ERROR: "+turn.error();
                if(turn.approval()!=null){
                    if(turn.approval().status().equals("PENDING"))target.tool="FAKE approval "+turn.approval().requestId()+" "+turn.approval().operation()+" PENDING";
                    else target.tool+=" | approval="+turn.approval().status();
                }
            }
            target.scroll=session.scroll();view.sessions.add(target);
        }
        for(int i=0;i<snapshot.sessions().size();i++){
            if(snapshot.sessions().get(i).sessionId().equals(snapshot.currentSessionId()))view.active=i;
            if(snapshot.sessions().get(i).sessionId().equals(snapshot.selectedSessionId()))view.selected=i;
        }
        view.sidebarFocus=snapshot.sessionFocus();view.input.write(snapshot.current().input().text());view.input.cursor(snapshot.current().input().cursor());
        var base=new PrototypeRenderer().frame(view,snapshot.width(),snapshot.height());var lines=new ArrayList<>(base.lines());
        lines.set(0,new AttributedString(PrototypeRenderer.fit("Agent TUI-1 | FAKE adapter | native gate NOT VERIFIED",snapshot.width()),AttributedStyle.BOLD));
        if(snapshot.width()>=30&&snapshot.height()>=8){
            var current=snapshot.current();var turn=current.turn();
            lines.set(1,new AttributedString(PrototypeRenderer.fit(current.title()+" | "+current.provider()+"/"+current.model()
                    +" | Memory="+(current.memory()?"ON":"OFF")+" | "+(turn==null?"IDLE":turn.status()),snapshot.width())));
            lines.set(lines.size()-1,new AttributedString(PrototypeRenderer.fit("Tab sessions | Esc cancel | /approve /error /fail demos | Ctrl-Q exit",snapshot.width())));
        }
        return new PrototypeRenderer.Frame(List.copyOf(lines),base.cursorRow(),base.cursorColumn(),base.sidebar());
    }
    private static boolean key(UiEventLoop loop,TuiPrototypeMain.Key key,String text){
        var snapshot=loop.snapshot();var current=snapshot.current();
        if(key==TuiPrototypeMain.Key.EXIT)return false;
        if(key==TuiPrototypeMain.Key.TAB){loop.focus(!snapshot.sessionFocus());return true;}
        if(key==TuiPrototypeMain.Key.ESC){if(snapshot.sessionFocus())loop.focus(false);else loop.cancelCurrent();return true;}
        if(key==TuiPrototypeMain.Key.PAGE_UP||key==TuiPrototypeMain.Key.PAGE_DOWN){loop.scroll(key==TuiPrototypeMain.Key.PAGE_UP?5:-5);return true;}
        if(snapshot.sessionFocus()){
            if(key==TuiPrototypeMain.Key.UP)loop.moveSelection(-1);
            else if(key==TuiPrototypeMain.Key.DOWN)loop.moveSelection(1);
            else if(key==TuiPrototypeMain.Key.ENTER)loop.enterSelection();return true;
        }
        var turn=current.turn();
        if(turn!=null&&turn.approval()!=null&&turn.approval().status().equals("PENDING")){
            if(key==TuiPrototypeMain.Key.TEXT&&(text.equalsIgnoreCase("y")||text.equalsIgnoreCase("n")))
                loop.resolveSimulatedApproval(current.sessionId(),turn.turnId(),turn.approval().requestId(),text.equalsIgnoreCase("y"));
            return true;
        }
        switch(key){
            case MEMORY -> loop.memory(!current.memory());
            case ENTER -> loop.submit();
            case FAIL -> {loop.edit(UiEventLoop.Edit.CLEAR,"");loop.edit(UiEventLoop.Edit.TEXT,"/fail");loop.submit();}
            case UP -> loop.scroll(1);case DOWN -> loop.scroll(-1);
            case TEXT -> loop.edit(UiEventLoop.Edit.TEXT,text);
            case LEFT -> loop.edit(UiEventLoop.Edit.LEFT,"");case RIGHT -> loop.edit(UiEventLoop.Edit.RIGHT,"");
            case HOME -> loop.edit(UiEventLoop.Edit.HOME,"");case END -> loop.edit(UiEventLoop.Edit.END,"");
            case BACKSPACE -> loop.edit(UiEventLoop.Edit.BACKSPACE,"");case DELETE -> loop.edit(UiEventLoop.Edit.DELETE,"");
            default -> { }
        }
        return true;
    }
    private static void run(Terminal terminal)throws Exception {
        try(var loop=create();var screen=new PrototypeScreen(terminal)){
            var running=new AtomicBoolean(true);
            var previousInt=terminal.handle(Terminal.Signal.INT,signal->running.set(false));
            // A shutdown hook only restores the screen. Reducer state is never mutated off-owner.
            Thread hook=new Thread(()->{try{loop.shutdownBackend();}finally{screen.close();}},"tui1-screen-cleanup");Runtime.getRuntime().addShutdownHook(hook);
            try {
                screen.enter();var reader=new BindingReader(terminal.reader());var keys=TuiPrototypeMain.keys(terminal);
                long nextFrame=0;boolean dirty=true;
                while(running.get()){
                    dirty|=loop.pump(64)>0;int next=reader.peekCharacter(15);if(next==-1)break;
                    if(next>=0){var key=reader.readBinding(keys,null,false);if(key!=null){running.set(key(loop,key,reader.getLastBinding()));dirty=true;}}
                    var size=terminal.getSize();var before=loop.snapshot();
                    if(before.width()!=Math.max(1,size.getColumns())||before.height()!=Math.max(1,size.getRows())){loop.resize(size.getColumns(),size.getRows());dirty=true;}
                    if(dirty&&System.nanoTime()>=nextFrame){var snapshot=loop.snapshot();screen.draw(frame(snapshot),snapshot.width(),snapshot.height());dirty=false;nextFrame=System.nanoTime()+50_000_000L;}
                }
            } finally {
                try{loop.close();}finally{screen.close();terminal.handle(Terminal.Signal.INT,previousInt);
                    try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException shutdown){ }}
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--headless-demo")){
            try(var loop=create()){
                loop.edit(UiEventLoop.Edit.TEXT,"离线 UI-1 事件适配验证");loop.submit();long deadline=System.nanoTime()+5_000_000_000L;
                while(loop.snapshot().current().turn().running()&&System.nanoTime()<deadline){loop.pump(64);Thread.sleep(5);}
                if(loop.snapshot().current().turn().running())throw new IllegalStateException("Fake demo timed out");
                frame(loop.snapshot()).lines().forEach(line->System.out.println(line.toString()));
            }return;
        }
        if(args.length!=0)throw new IllegalArgumentException("Only --headless-demo is supported");
        if(System.console()==null){System.out.println("TUI1_NATIVE_NOT_AVAILABLE; independent offline layer only; no API requests");return;}
        try(var terminal=TerminalBuilder.builder().system(true).encoding(java.nio.charset.StandardCharsets.UTF_8)
                .provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()){
            for(var capability:List.of(Capability.enter_ca_mode,Capability.exit_ca_mode,Capability.cursor_address,Capability.clear_screen))
                if(terminal.getStringCapability(capability)==null)throw new IOException("Unsupported terminal");
            run(terminal);
        }
    }
}
