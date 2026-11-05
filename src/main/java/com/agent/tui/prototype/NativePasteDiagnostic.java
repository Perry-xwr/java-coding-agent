package com.agent.tui.prototype;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.*;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Native, zero-API event diagnostic. Never prints the clipboard or typed text. */
public final class NativePasteDiagnostic {
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT=0x0200;
    private static final long IDLE_TIMEOUT_MILLIS=120_000;
    enum WaitStatus { READY, TIMEOUT, EOF }
    record Awaited(WaitStatus status,int character) { }
    private record ConsoleInput(Class<?> api,long handle,int originalMode) { }
    private NativePasteDiagnostic() { }

    private static ConsoleInput consoleInput() throws Exception {
        Class<?> backend=Class.forName("org.jline.terminal.impl.jni.win.NativeWinSysTerminal");
        Field handleField=backend.getDeclaredField("consoleIn");handleField.setAccessible(true);
        long handle=handleField.getLong(null);
        Class<?> api=Class.forName("org.jline.nativ.Kernel32");int[] mode={0};
        int ok=(int)api.getMethod("GetConsoleMode",long.class,int[].class).invoke(null,handle,mode);
        if(ok==0)throw new IllegalStateException("GET_CONSOLE_MODE_FAILED");
        return new ConsoleInput(api,handle,mode[0]);
    }
    private static int mode(ConsoleInput input)throws Exception{
        int[] value={0};int ok=(int)input.api().getMethod("GetConsoleMode",long.class,int[].class).invoke(null,input.handle(),value);
        if(ok==0)throw new IllegalStateException("GET_CONSOLE_MODE_FAILED");return value[0];
    }
    private static void setMode(ConsoleInput input,int value)throws Exception{
        int ok=(int)input.api().getMethod("SetConsoleMode",long.class,int.class).invoke(null,input.handle(),value);
        if(ok==0)throw new IllegalStateException("SET_CONSOLE_MODE_FAILED");
    }

    /** READ_EXPIRED (-2) is an idle interval, not EOF. */
    static Awaited awaitCharacter(BindingReader reader,long timeoutMillis)throws IOException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0,timeoutMillis));
        while(true){
            long remaining=deadline-System.nanoTime();if(remaining<=0)return new Awaited(WaitStatus.TIMEOUT,-1);
            int value=reader.peekCharacter(Math.min(100,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
            if(value==NonBlockingReader.READ_EXPIRED)continue;
            if(value==NonBlockingReader.EOF)return new Awaited(WaitStatus.EOF,-1);
            return new Awaited(WaitStatus.READY,value);
        }
    }

    public static void main(String[] args)throws Exception {
        if(args.length>1||(args.length==1&&!args[0].equals("--vt-input-experiment")))throw new IllegalArgumentException("Use optional --vt-input-experiment");
        if(System.console()==null)throw new IllegalStateException("NATIVE_CONSOLE_REQUIRED");
        boolean experiment=args.length==1;ConsoleInput console=consoleInput();int original=console.originalMode();
        String exitReason="EXCEPTION";boolean modeRestored=false;
        try{
            try(Terminal terminal=TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()){
                var previous=new Attributes(terminal.getAttributes());var out=terminal.writer();
                try{
                    terminal.enterRawMode();out.write("\u001b[?2004h");out.flush();boolean mouse=TerminalMouseBinding.enable(terminal);
                    int current=mode(console);
                    out.println("BACKEND="+terminal.getClass().getName()+" TYPE="+terminal.getType()+" ORIGINAL_INPUT_MODE=0x"+Integer.toHexString(original)+" CURRENT_INPUT_MODE=0x"+Integer.toHexString(current)+" VT_INPUT_BEFORE="+((current&ENABLE_VIRTUAL_TERMINAL_INPUT)!=0)+" READ_PATH=JLine_BindingReader_over_NativeWinSysTerminal_ReadConsoleInput ENABLE_SEQUENCE_SENT=ESC[?2004h MOUSE_ENABLED="+mouse);out.flush();
                    if(experiment){setMode(console,current|ENABLE_VIRTUAL_TERMINAL_INPUT);current=mode(console);out.println("VT_INPUT_EXPERIMENT=ENABLED CURRENT_INPUT_MODE=0x"+Integer.toHexString(current)+" VT_INPUT_AFTER="+((current&ENABLE_VIRTUAL_TERMINAL_INPUT)!=0)+" READ_PATH_UNCHANGED=NativeWinSysTerminal_ReadConsoleInput");out.flush();}
                    out.println("INPUT_WAITING timeout_seconds=120. Paste only synthetic non-secret text. Text is not displayed/stored. Ctrl-Q exits.");out.flush();
                    BindingReader reader=new BindingReader(terminal.reader());KeyMap<TuiPrototypeMain.Key> keys=TuiPrototypeMain.keys(terminal);
                    String mousePrefix=TerminalMouseBinding.prefix(terminal);if(mousePrefix!=null)keys.bind(TuiPrototypeMain.Key.MOUSE,mousePrefix);
                    String f6=KeyMap.key(terminal,org.jline.utils.InfoCmp.Capability.key_f6);if(f6!=null)keys.bind(TuiPrototypeMain.Key.COPY,f6);
                    keys.bind(TuiPrototypeMain.Key.NEWLINE,KeyMap.ctrl('N'));
                    boolean paste=false;int count=0,cr=0,lf=0,reads=0;StringBuilder marker=new StringBuilder();long lastActivity=System.nanoTime();
                    while(true){
                        long idleMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-lastActivity);
                        Awaited available=awaitCharacter(reader,Math.max(0,IDLE_TIMEOUT_MILLIS-idleMillis));
                        if(available.status()==WaitStatus.TIMEOUT){exitReason="TIMEOUT";break;}
                        if(available.status()==WaitStatus.EOF){exitReason="EOF";break;}
                        if(paste){
                            int value=reader.readCharacter();if(value==NonBlockingReader.EOF){exitReason="EOF";break;}
                            lastActivity=System.nanoTime();reads++;marker.appendCodePoint(value);String end="\u001b[201~";
                            while(!end.startsWith(marker.toString())){int cp=marker.codePointAt(0);count++;if(cp=='\r')cr++;if(cp=='\n')lf++;marker.delete(0,Character.charCount(cp));}
                            if(marker.toString().equals(end)){paste=false;out.println("PASTE_END_DETECTED chars="+count+" CR="+cr+" LF="+lf+" character_reads="+reads);marker.setLength(0);}
                        }else{
                            TuiPrototypeMain.Key key=reader.readBinding(keys,null,false);lastActivity=System.nanoTime();
                            if(key==TuiPrototypeMain.Key.EXIT){exitReason="CTRL_Q";break;}
                            if(key==TuiPrototypeMain.Key.PASTE_START){paste=true;count=cr=lf=reads=0;marker.setLength(0);out.println("PASTE_START_DETECTED");}
                            else if(key==TuiPrototypeMain.Key.ENTER)out.println("ENTER_OUTSIDE_BRACKET");
                            else if(key==TuiPrototypeMain.Key.MOUSE){MouseEvent event=terminal.readMouseEvent(reader::readCharacter);out.println("MOUSE type="+event.getType()+" button="+event.getButton()+" x="+event.getX()+" y="+event.getY());}
                            else if(key==TuiPrototypeMain.Key.UP)out.println("KEY=UP");else if(key==TuiPrototypeMain.Key.DOWN)out.println("KEY=DOWN");
                            else if(key==TuiPrototypeMain.Key.PAGE_UP)out.println("KEY=PAGE_UP");else if(key==TuiPrototypeMain.Key.PAGE_DOWN)out.println("KEY=PAGE_DOWN");
                            else if(key==TuiPrototypeMain.Key.COPY)out.println("KEY=F6");else if(key==TuiPrototypeMain.Key.NEWLINE)out.println("KEY=CTRL_N");
                        }
                        out.flush();
                    }
                    if(paste)out.println("INCOMPLETE_PASTE_MARKER");
                }finally{
                    try{terminal.trackMouse(Terminal.MouseTracking.Off);out.write("\u001b[?1002l\u001b[?1005l\u001b[?2004l");out.flush();}
                    finally{terminal.setAttributes(previous);}
                }
            }
        }catch(Exception|Error failure){exitReason="EXCEPTION";throw failure;}
        finally{
            try{setMode(console,original);modeRestored=(mode(console)==original);}
            finally{System.out.println("CONSOLE_INPUT_MODE_RESTORED="+(modeRestored?"0x"+Integer.toHexString(original):"FAILED")+" EXIT_REASON="+exitReason);System.out.flush();}
        }
    }
}
