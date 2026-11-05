package com.agent.tui.prototype;

import org.jline.utils.*;
import java.util.*;

/** Pure column-aware frame construction. Rendering never mutates conversation state. */
public final class PrototypeRenderer {
    public record Frame(List<AttributedString> lines,int cursorRow,int cursorColumn,boolean sidebar) { }
    static String clean(String text) { return text.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]"," "); }
    static String fit(String text,int width) {
        if(width<=0)return "";
        var part=new AttributedString(clean(text)).columnSubSequence(0,width);
        return part.toString()+" ".repeat(Math.max(0,width-part.columnLength()));
    }
    static List<String> wrap(String text,int width) {
        var lines=new ArrayList<String>();
        for(String paragraph:clean(text).split("\n",-1)) {
            var line=new StringBuilder(); int columns=0;
            for(int cp:paragraph.codePoints().toArray()) {
                int size=Math.max(0,WCWidth.wcwidth(cp));
                if(columns+size>width && !line.isEmpty()) {lines.add(line.toString());line.setLength(0);columns=0;}
                if(size<=width){line.appendCodePoint(cp);columns+=size;}
            }
            lines.add(line.toString());
        }
        return lines;
    }
    public Frame frame(PrototypeState state,int width,int height) {
        width=Math.max(1,width); height=Math.max(1,height);
        if(width<30 || height<8) {
            var rows=new ArrayList<AttributedString>();
            rows.add(new AttributedString(fit("TUI-0: resize >=30x8; Ctrl-Q exit",width)));
            while(rows.size()<height)rows.add(new AttributedString(" ".repeat(width)));
            return new Frame(rows,height-1,0,false);
        }
        var session=state.current(); boolean sidebar=width>=90; int side=sidebar?26:0;
        int body=height-5, chatWidth=width-(sidebar?side+1:0);
        var history=new ArrayList<String>();
        for(var message:session.messages) history.addAll(wrap(message.role()+": "+message.text(),chatWidth));
        int end=Math.max(0,history.size()-Math.min(session.scroll,Math.max(0,history.size()-body)));
        int start=Math.max(0,end-body);
        var rows=new ArrayList<AttributedString>();
        rows.add(new AttributedString(fit("Agent TUI-0 | FAKE ONLY | no APIs / no files",width),AttributedStyle.BOLD));
        rows.add(new AttributedString(fit(session.title+" | model=FAKE | Memory="+(session.memory?"ON":"OFF")+" | "+session.status,width)));
        for(int i=0;i<body;i++) {
            String chat=start+i<end?history.get(start+i):"";
            var line=new AttributedStringBuilder();
            if(sidebar) {
                int first=Math.max(0,state.selected-body+1); int index=first+i;
                String label=index<state.sessions.size()?(index==state.active?"* ":"  ")+state.sessions.get(index).title:"";
                line.style(state.sidebarFocus && index==state.selected?AttributedStyle.INVERSE:AttributedStyle.DEFAULT)
                        .append(fit(label,side)).style(AttributedStyle.DEFAULT).append("|");
            }
            line.append(fit(chat,chatWidth)); rows.add(line.toAttributedString());
        }
        rows.add(new AttributedString(fit("Tool: "+session.tool+" | focus="+(state.sidebarFocus?"sessions":"input")+" | PgUp/PgDn history",width)));
        String prefix=state.sidebarFocus?"Select: ":session.approval?"SIMULATED approval [Y/N]: ":"You > ";
        int available=Math.max(1,width-prefix.length()-1);
        String before=state.input.upToCursor();
        int cursorColumns=new AttributedString(before).columnLength();
        int offset=Math.max(0,cursorColumns-available);
        String visible=new AttributedString(state.input.toString()).columnSubSequence(offset,offset+available).toString();
        rows.add(new AttributedString(fit(prefix+visible,width)));
        rows.add(new AttributedString(fit(state.sidebarFocus
                ? "Sessions: "+(state.selected+1)+"/4 "+state.sessions.get(state.selected).title+" | Enter select, Esc input"
                : "Tab sessions | F2 Memory | F3 fail demo | Enter send | Ctrl-Q exit",width)));
        return new Frame(List.copyOf(rows),height-2,Math.min(width-1,prefix.length()+cursorColumns-offset),sidebar);
    }
}
