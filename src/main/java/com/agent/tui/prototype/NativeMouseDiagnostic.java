package com.agent.tui.prototype;

import org.jline.terminal.*;
import org.jline.keymap.*;
import org.jline.utils.InfoCmp.Capability;
import java.nio.charset.StandardCharsets;

/** Native input diagnostic only. No Agent, provider, storage, or user text logging. */
public final class NativeMouseDiagnostic {
    public static void main(String[] args)throws Exception {
        if(args.length>1||(args.length==1&&!args[0].equals("--jni-binding")))throw new IllegalArgumentException("Use optional --jni-binding");
        if(System.console()==null)throw new IllegalStateException("NATIVE_CONSOLE_REQUIRED");
        try(var terminal=TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()){
            Attributes previous=new Attributes(terminal.getAttributes());
            try {
                terminal.enterRawMode();
                boolean enabled=terminal.trackMouse(Terminal.MouseTracking.Button);
                var out=terminal.writer();
                out.println("BACKEND="+terminal.getClass().getName()+" TYPE="+terminal.getType()+" CAP_MOUSE="+terminal.hasMouseSupport()+" TRACK_RESULT="+enabled+" SIZE="+terminal.getSize());
                out.println("Move wheel / click / drag; separately press arrow keys. Ctrl-Q exits. Text is never printed.");out.flush();
                var reader=new BindingReader(terminal.reader());var keys=new KeyMap<String>();keys.setNomatch("TEXT_REDACTED");keys.setUnicode("TEXT_REDACTED");
                for(var cap:new Capability[]{Capability.key_mouse,Capability.key_up,Capability.key_down}){String sequence=KeyMap.key(terminal,cap);if(sequence!=null)keys.bind(cap.name(),sequence);}
                if(args.length==1){
                    if(!terminal.getClass().getName().equals("org.jline.terminal.impl.jni.win.NativeWinSysTerminal"))throw new IllegalStateException("JNI_WINDOWS_REQUIRED");
                    // This backend's processMouseEvent writes ESC [ M even when terminfo omits key_mouse.
                    keys.bind("key_mouse","\u001b[M");
                    out.println("BINDING=JNI_X10_EXPLICIT; isolated diagnostic only");out.flush();
                }
                keys.bind("EXIT",KeyMap.ctrl('Q'));
                while(true){String key=reader.readBinding(keys);if(key==null||key.equals("EXIT"))break;
                    if(key.equals("key_mouse")){var event=terminal.readMouseEvent(reader::readCharacter);out.println("MOUSE type="+event.getType()+" button="+event.getButton()+" x="+event.getX()+" y="+event.getY());}
                    else if(!key.equals("TEXT_REDACTED"))out.println("KEY="+key);
                    out.flush();
                }
            } finally {terminal.trackMouse(Terminal.MouseTracking.Off);terminal.writer().write("\u001b[?1002l\u001b[?1005l");terminal.flush();terminal.setAttributes(previous);}
        }
    }
}
