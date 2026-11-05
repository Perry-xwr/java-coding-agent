package com.agent.tui.prototype;

import org.jline.keymap.*;
import org.jline.terminal.*;
import org.jline.utils.InfoCmp.Capability;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent entry: no Agent, Provider factory, SessionStore, MemoryStore, or subprocess. */
public final class TuiPrototypeMain {
    enum Key { TEXT,TAB,UP,DOWN,LEFT,RIGHT,HOME,END,BACKSPACE,DELETE,ENTER,ESC,PAGE_UP,PAGE_DOWN,MEMORY,FAIL,EXIT,NEWLINE,CLEAR_INPUT,CHAT_FOCUS,TOOLS,MOUSE,CONTROL_SEQUENCE,COPY,PASTE_START }
    static KeyMap<Key> keys(Terminal terminal) {
        var map=new KeyMap<Key>(); map.setNomatch(Key.TEXT);map.setUnicode(Key.TEXT);map.setAmbiguousTimeout(500);
        bind(map,terminal,Key.UP,Capability.key_up);bind(map,terminal,Key.DOWN,Capability.key_down);
        bind(map,terminal,Key.LEFT,Capability.key_left);bind(map,terminal,Key.RIGHT,Capability.key_right);
        // Windows VT input uses normal CSI arrows even when windows-vtp terminfo advertises
        // application-cursor sequences (or omits the key capabilities entirely).
        map.bind(Key.UP,"\u001b[A","\u001bOA");map.bind(Key.DOWN,"\u001b[B","\u001bOB");
        map.bind(Key.RIGHT,"\u001b[C","\u001bOC");map.bind(Key.LEFT,"\u001b[D","\u001bOD");
        bind(map,terminal,Key.HOME,Capability.key_home);bind(map,terminal,Key.END,Capability.key_end);
        bind(map,terminal,Key.PAGE_UP,Capability.key_ppage);bind(map,terminal,Key.PAGE_DOWN,Capability.key_npage);
        bind(map,terminal,Key.DELETE,Capability.key_dc);bind(map,terminal,Key.BACKSPACE,Capability.key_backspace);
        bind(map,terminal,Key.MEMORY,Capability.key_f2);bind(map,terminal,Key.FAIL,Capability.key_f3);
        map.bind(Key.ENTER,"\r","\n");map.bind(Key.TAB,"\t");map.bind(Key.ESC,KeyMap.esc());
        map.bind(Key.PASTE_START,"\u001b[200~");
        // Catch unknown CSI/SS3 sequences as protocol input, never as editable text.
        map.bind(Key.CONTROL_SEQUENCE,"\u001b[","\u001bO");
        map.bind(Key.BACKSPACE,KeyMap.ctrl('H'),"\u007f"); map.bind(Key.EXIT,KeyMap.ctrl('Q'),KeyMap.ctrl('C'));
        return map;
    }
    private static void bind(KeyMap<Key> keys,Terminal terminal,Key key,Capability capability) {
        String value=KeyMap.key(terminal,capability);if(value!=null)keys.bind(key,value);
    }
    static void discardControlSequenceTail(org.jline.keymap.BindingReader input){
        for(int i=0;i<64;i++){
            int available;
            do{available=input.peekCharacter(250);if(Thread.currentThread().isInterrupted())return;}while(available==-2);
            if(available<0)return;
            int value=input.readCharacter();if(value>=0x40&&value<=0x7e)return;
        }
    }
    static boolean act(PrototypeState state,SimulatedRuntime runtime,Key key,String text) {
        state.owned(); var session=state.current();
        if(key==Key.EXIT)return false;
        if(key==Key.TAB){state.sidebarFocus=!state.sidebarFocus;state.selected=state.active;return true;}
        if(key==Key.ESC) {
            if(state.sidebarFocus){state.sidebarFocus=false;return true;}
            if(session.approval)runtime.resolve(session.id,session.turnId,false);
            state.sidebarFocus=false;return true;
        }
        if(key==Key.PAGE_UP || key==Key.PAGE_DOWN) {
            session.scroll=Math.max(0,Math.min(10_000,session.scroll+(key==Key.PAGE_UP?5:-5))); return true;
        }
        if(state.sidebarFocus) {
            if(key==Key.UP)state.selected=Math.max(0,state.selected-1);
            else if(key==Key.DOWN)state.selected=Math.min(3,state.selected+1);
            else if(key==Key.ENTER)state.choose();
            return true;
        }
        if(session.approval) {
            if(key==Key.TEXT && (text.equalsIgnoreCase("y") || text.equalsIgnoreCase("n")))
                runtime.resolve(session.id,session.turnId,text.equalsIgnoreCase("y"));
            return true;
        }
        switch(key) {
            case MEMORY -> session.memory=!session.memory;
            case UP -> session.scroll=Math.min(10_000,session.scroll+1);
            case DOWN -> session.scroll=Math.max(0,session.scroll-1);
            case LEFT -> state.input.move(-1);
            case RIGHT -> state.input.move(1);
            case HOME -> state.input.cursor(0);
            case END -> state.input.cursor(state.input.length());
            case BACKSPACE -> state.input.backspace();
            case DELETE -> state.input.delete();
            case ENTER,FAIL -> {
                if(key==Key.FAIL && state.input.length()==0)state.type("模拟失败演示");
                String turn=state.begin();if(turn!=null)runtime.start(session.id,turn,session.memory,key==Key.FAIL);
            }
            case TEXT -> state.type(text);
            default -> { }
        }
        return true;
    }
    static void run(Terminal terminal,AtomicBoolean running)throws Exception {
        var state=new PrototypeState();var renderer=new PrototypeRenderer();
        var queue=new ArrayBlockingQueue<PrototypeState.Event>(256);
        try(var simulator=new SimulatedRuntime(queue);var screen=new PrototypeScreen(terminal)) {
            Thread hook=new Thread(()->{try {simulator.close();}finally {screen.close();}},"tui0-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);
            var previousInt=terminal.handle(Terminal.Signal.INT,signal->running.set(false));
            var previousQuit=terminal.handle(Terminal.Signal.QUIT,signal->running.set(false));
            try {
                screen.enter();var reader=new BindingReader(terminal.reader());var keys=keys(terminal);
                long nextFrame=0;boolean dirty=true;int oldWidth=-1,oldHeight=-1;
                while(running.get()) {
                    for(int i=0;i<64;i++) {var event=queue.poll();if(event==null)break;dirty|=state.accept(event);}
                    int character=reader.peekCharacter(15);
                    if(character==-1)break;
                    if(character>=0) {
                        Key key=reader.readBinding(keys,null,false);
                        if(key!=null){running.set(act(state,simulator,key,reader.getLastBinding()));dirty=true;}
                    }
                    Size size=terminal.getSize();int w=Math.max(1,size.getColumns()),h=Math.max(1,size.getRows());
                    dirty|=w!=oldWidth || h!=oldHeight;
                    if(dirty && System.nanoTime()>=nextFrame) {
                        screen.draw(renderer.frame(state,w,h),w,h);oldWidth=w;oldHeight=h;dirty=false;
                        nextFrame=System.nanoTime()+50_000_000L; // At most 20 display refreshes/s.
                    }
                }
            } finally {
                simulator.close();screen.close();
                terminal.handle(Terminal.Signal.INT,previousInt);terminal.handle(Terminal.Signal.QUIT,previousQuit);
                try {Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException shuttingDown){ }
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1 && args[0].equals("--headless-demo")) {
            var state=new PrototypeState();
            for(var line:new PrototypeRenderer().frame(state,110,26).lines())System.out.println(line.toString());
            return;
        }
        if(args.length>0)throw new IllegalArgumentException("Only --headless-demo is supported");
        if(System.console()==null) {
            System.out.println("TUI0_NATIVE_NOT_AVAILABLE: run direct java in a native console; use --headless-demo for offline layout.");return;
        }
        try(Terminal terminal=TerminalBuilder.builder().system(true).encoding(java.nio.charset.StandardCharsets.UTF_8)
                .provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()) {
            for(var cap:List.of(Capability.enter_ca_mode,Capability.exit_ca_mode,Capability.clear_screen,Capability.cursor_address,
                    Capability.key_up,Capability.key_down,Capability.key_left,Capability.key_right))
                if(terminal.getStringCapability(cap)==null)throw new IOException("Native terminal missing required capabilities");
            run(terminal,new AtomicBoolean(true));
        }
    }
}
