package com.agent.tui.prototype;

import org.jline.terminal.*;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only this prototype's alternate screen. Idempotent restore also serves shutdown cleanup. */
final class PrototypeScreen implements AutoCloseable {
    private final Terminal terminal;
    private final Attributes previous;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Display display;
    private final boolean mouse;
    private boolean entered;
    private int width=-1,height=-1;
    PrototypeScreen(Terminal terminal) {
        this(terminal,false);
    }
    PrototypeScreen(Terminal terminal,boolean mouse) {
        this.mouse=mouse;
        this.terminal=terminal; previous=new Attributes(terminal.getAttributes()); display=new Display(terminal,true);
    }
    synchronized void enter() {
        terminal.enterRawMode(); entered=true;
        terminal.puts(Capability.enter_ca_mode); terminal.puts(Capability.clear_screen); terminal.flush();
        if(!terminal.getType().contains("dumb")){terminal.writer().write("\u001b[?2004h");terminal.flush();}
        if(mouse&&!TerminalMouseBinding.enable(terminal)){
            terminal.writer().println("Mouse unavailable; use PgUp/PgDn or Ctrl-G history.");terminal.flush();
        }
    }
    synchronized void draw(PrototypeRenderer.Frame frame,int columns,int rows) {
        if(closed.get())return;
        if(width!=columns || height!=rows) {display.resize(rows,columns);display.reset();width=columns;height=rows;}
        // JLine Display owns/mutates its working list while diffing consecutive frames.
        display.update(new java.util.ArrayList<>(frame.lines()),new Size(columns,rows).cursorPos(frame.cursorRow(),frame.cursorColumn()));
    }
    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        try {
            if(entered) {try{if(mouse){terminal.trackMouse(Terminal.MouseTracking.Off);terminal.writer().write("\u001b[?1002l\u001b[?1003l\u001b[?1005l");}}finally{if(!terminal.getType().contains("dumb"))terminal.writer().write("\u001b[?2004l");terminal.puts(Capability.cursor_normal);terminal.puts(Capability.exit_ca_mode);terminal.flush();}}
        } finally { terminal.setAttributes(previous); }
    }
}
