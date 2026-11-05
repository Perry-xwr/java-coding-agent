package com.agent.tui.prototype;

import org.jline.terminal.Terminal;
import org.jline.terminal.MouseEvent;
import org.jline.keymap.KeyMap;
import org.jline.keymap.BindingReader;
import org.jline.utils.InfoCmp.Capability;
import java.util.EnumSet;

/** Mouse bindings for JLine's X10 reports and Windows Terminal's SGR reports. */
final class TerminalMouseBinding {
    static boolean windowsJni(Terminal terminal){return terminal.getClass().getName().equals("org.jline.terminal.impl.jni.win.NativeWinSysTerminal");}
    static String prefix(boolean windowsJni,String capability){return windowsJni?"\u001b[M":capability;}
    static String prefix(Terminal terminal){return prefix(windowsJni(terminal),KeyMap.key(terminal,Capability.key_mouse));}
    static String sgrPrefix(){return "\u001b[<";}
    static boolean enable(Terminal terminal){
        if(!(windowsJni(terminal)||terminal.hasMouseSupport()))return false;
        // Clear stale motion modes, then request only button/release/wheel reports.
        terminal.writer().write("\u001b[?1002l\u001b[?1003l");terminal.flush();
        return terminal.trackMouse(Terminal.MouseTracking.Button);
    }

    /** Parse one SGR report after BindingReader consumed ESC [ &lt;. */
    static MouseEvent readSgr(BindingReader input){
        StringBuilder report=new StringBuilder(24);
        for(int i=0;i<48;i++){
            int available;
            do{available=input.peekCharacter(250);if(Thread.currentThread().isInterrupted())throw new IllegalStateException("INTERRUPTED_SGR_MOUSE_REPORT");}
            while(available==-2);
            if(available<0)throw new IllegalStateException("INCOMPLETE_SGR_MOUSE_REPORT");
            int c=input.readCharacter();
            if(c=='M'||c=='m')return decodeSgr(report.toString(),(char)c);
            if((c>='0'&&c<='9')||c==';')report.append((char)c);
            else throw new IllegalArgumentException("INVALID_SGR_MOUSE_REPORT");
        }
        throw new IllegalArgumentException("OVERSIZED_SGR_MOUSE_REPORT");
    }

    static MouseEvent decodeSgr(String parameters,char terminator){
        String[] fields=parameters.split(";",-1);
        if(fields.length!=3||!(terminator=='M'||terminator=='m'))throw new IllegalArgumentException("INVALID_SGR_MOUSE_REPORT");
        final int code,x,y;
        try{code=Integer.parseInt(fields[0]);x=Integer.parseInt(fields[1]);y=Integer.parseInt(fields[2]);}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("INVALID_SGR_MOUSE_REPORT");}
        if(code<0||code>255||x<1||y<1)throw new IllegalArgumentException("INVALID_SGR_MOUSE_REPORT");
        var modifiers=EnumSet.noneOf(MouseEvent.Modifier.class);
        if((code&4)!=0)modifiers.add(MouseEvent.Modifier.Shift);
        if((code&8)!=0)modifiers.add(MouseEvent.Modifier.Alt);
        if((code&16)!=0)modifiers.add(MouseEvent.Modifier.Control);
        boolean wheel=(code&64)!=0,motion=(code&32)!=0;
        int buttonCode=code&3;
        MouseEvent.Button button=switch(buttonCode){case 0->MouseEvent.Button.Button1;case 1->MouseEvent.Button.Button2;case 2->MouseEvent.Button.Button3;default->MouseEvent.Button.NoButton;};
        MouseEvent.Type type;
        if(wheel){type=MouseEvent.Type.Wheel;button=(code&1)==0?MouseEvent.Button.WheelUp:MouseEvent.Button.WheelDown;}
        else if(terminator=='m')type=MouseEvent.Type.Released;
        else if(motion)type=buttonCode==3?MouseEvent.Type.Moved:MouseEvent.Type.Dragged;
        else type=buttonCode==3?MouseEvent.Type.Moved:MouseEvent.Type.Pressed;
        return new MouseEvent(type,button,modifiers,x-1,y-1);
    }
}
