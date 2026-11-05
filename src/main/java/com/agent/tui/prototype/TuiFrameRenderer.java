package com.agent.tui.prototype;

import com.agent.TuiSessionController;
import com.agent.tui.runtime.*;
import org.jline.utils.*;
import java.util.*;

/** Presentation only: quiet hierarchy, keyboard-first layout and bounded panes. */
public final class TuiFrameRenderer {
    private static final boolean NO_COLOR=Boolean.getBoolean("agent.tui.noColor");
    private static final AttributedStyle PLAIN=AttributedStyle.DEFAULT;
    private static AttributedStyle color(int value){return NO_COLOR?PLAIN:PLAIN.foreground(value);}
    private static AttributedStyle accent(){return color(14).bold();}
    private static AttributedStyle muted(){return color(8);}
    private static AttributedStyle lineStyle(String text){
        if(text.equals("[复制]"))return color(6).bold();
        if(text.startsWith("[fail]")||text.contains("[partial: FAILED]")||text.startsWith("ERROR:")||text.startsWith("FAILED"))return color(1).bold();
        if(text.startsWith("[ok]")||text.startsWith("[saved]")||text.startsWith("已记住"))return color(2);
        if(text.startsWith("[run]")||text.startsWith("[cancel]")||text.startsWith("[unknown]")||text.startsWith("Agent [streaming]"))return color(3);
        if(text.startsWith("Info:")||text.startsWith("Memory diagnostics")||text.startsWith("Semantic request budget"))return color(4);
        return PLAIN;
    }
    private static AttributedString messageLine(String text,int width){
        if(width<=0)return new AttributedString("");String value=TuiSessionController.redactForDisplay(text);
        int split=-1;AttributedStyle label=PLAIN,body=PLAIN;
        if(value.startsWith("You:")){split=4;label=color(6).bold();}
        else if(value.startsWith("Agent [partial: FAILED]:")){split=value.indexOf(':')+1;label=color(1).bold();}
        else if(value.startsWith("Agent [streaming]:")){split=value.indexOf(':')+1;label=color(3).bold();}
        else if(value.startsWith("Agent:")){split=6;label=color(2).bold();}
        else if(value.startsWith("Info:")){split=5;label=color(4).bold();body=color(7);}
        else if(value.startsWith("Tool:")){split=5;label=muted().bold();body=color(7);}
        var builder=new AttributedStringBuilder();
        if(split>0)builder.style(label).append(value.substring(0,split)).style(body).append(value.substring(split));
        else builder.style(lineStyle(value)).append(value);
        var clipped=builder.toAttributedString().columnSubSequence(0,width);int pad=Math.max(0,width-clipped.columnLength());
        return new AttributedStringBuilder().append(clipped).append(" ".repeat(pad),PLAIN).toAttributedString();
    }

    public static PrototypeRenderer.Frame frame(UiSnapshot state,ConversationViewport viewport,boolean writable){
        int w=Math.max(1,state.width()),h=Math.max(1,state.height());var rows=new ArrayList<AttributedString>();
        if(w<30||h<8){rows.add(fitted(state.localApproval()==null?"Resize to >=30x8  ·  Ctrl-Q exits":"Approval pending  ·  resize to read details",w,accent()));
            while(rows.size()<h)rows.add(new AttributedString(" ".repeat(w)));return new PrototypeRenderer.Frame(List.copyOf(rows),h-1,0,false);}

        var current=state.current();var turn=current.turn();
        var input=InputViewport.layout(current.input(),Math.max(1,w-7),Math.min(3,Math.max(1,h/6)));
        int inputRows=input.lines().size(),body=Math.max(1,h-4-inputRows),side=w>=100?28:0,chatWidth=w-(side>0?side+1:0);
        String capabilities=writable?current.capabilities():"READ-ONLY";
        var brand=new AttributedStringBuilder().style(accent()).append("Agent").style(PLAIN)
                .append("  ·  "+current.provider()+" / "+current.model()).style(muted()).append("   "+capabilities).toAttributedString();
        rows.add(fitAttributed(brand,w));

        String focus=state.sessionFocus()?"SESSIONS":state.chatFocus()?"HISTORY":"INPUT";
        String context=current.profile()+"  ·  "+current.title()+"  ·  "+focus+(w>=125?"  ·  "+current.updatedAt():"");
        if(side>0){var head=new AttributedStringBuilder().style(accent()).append("SESSIONS").style(muted()).append("  "+state.sessions().size()).toAttributedString();rows.add(join(head,context,side,chatWidth-1));}
        else rows.add(fitted(context,w,accent()));

        int selected=0;for(int i=0;i<state.sessions().size();i++)if(state.sessions().get(i).sessionId().equals(state.selectedSessionId()))selected=i;
        int page=Math.max(1,body/2),first=selected/page*page;
        var chat=viewport.visible(current,Math.max(1,chatWidth-1),body);
        if(side==0&&state.sessionFocus()){
            var picker=new ArrayList<String>();
            for(int row=0;row<body;row++){
                int index=first+row/2;
                if(index>=state.sessions().size()){picker.add("");continue;}
                var session=state.sessions().get(index);
                picker.add(row%2==0?(index==selected?"> ":"  ")
                        +(session.sessionId().equals(state.currentSessionId())?"● ":"  ")+session.title()
                        :"    "+session.updatedAt()+"  "+session.sessionId().substring(0,Math.min(6,session.sessionId().length())));
            }
            chat=picker;
        }
        for(int row=0;row<body;row++){
            var line=new AttributedStringBuilder();
            if(side>0){int index=first+row/2;String label="";boolean chosen=false,active=false;
                if(index<state.sessions().size()){var session=state.sessions().get(index);chosen=state.sessionFocus()&&index==selected;active=session.sessionId().equals(state.currentSessionId());
                    label=row%2==0?(chosen?"> ":"  ")+(active?"● ":"  ")+session.title():"    "+session.updatedAt()+"  "+session.sessionId().substring(0,Math.min(6,session.sessionId().length()));}
                AttributedStyle rowStyle=chosen?PLAIN.inverse().bold():active?color(6).bold():row%2==1?muted():PLAIN;
                line.style(rowStyle).append(PrototypeRenderer.fit(label,side)).style(PLAIN).append(" ");
            }
            String text=row<chat.size()?chat.get(row):"";
            if(side==0&&state.sessionFocus())line.style(row/2==selected-first?PLAIN.inverse().bold():PLAIN).append(PrototypeRenderer.fit(text,chatWidth-1));
            else line.append(messageLine(text,chatWidth-1));
            int thumb=viewport.thumbStart(current.sessionId());line.style(muted()).append(thumb<0||side==0&&state.sessionFocus()?" ":row>=thumb&&row<thumb+viewport.thumbSize(current.sessionId())?"┃":"│");
            rows.add(line.toAttributedString());
        }

        String memory=current.profile().equals("NOT_LOADED")?"Memory UNKNOWN":"Memory "+(current.memory()?"ON":"OFF")+"  ·  Auto "+(current.autoMemory()?"ON":"OFF")+"  ·  "+current.retrievalMode();
        String status=memory+"  ·  "+(turn==null?"IDLE":turn.status());
        if(!state.notice().isBlank())status=state.notice();
        if(state.notice().isBlank()&&turn!=null&&!turn.error().isBlank())status="ERROR  ·  "+turn.error()+"  ·  "+memory;
        AttributedStyle statusStyle=status.startsWith("ERROR")||status.contains("FAILED")?color(1).bold():status.startsWith("APPROVAL")?color(3).bold():status.startsWith("已记住")||status.startsWith("已复制")?color(2):muted();
        if(state.localApproval()!=null){status="APPROVAL  ·  "+(state.localApproval().nativeRequest()?"R1 exact nonce required":"DEMO denial only")+"  ·  PgUp details  ·  Esc cancels";statusStyle=color(3).bold();}
        rows.add(fitted(status,w,statusStyle));

        int inputStart=rows.size();
        for(int i=0;i<inputRows;i++){
            String prefix=i==0?"› You ":"      ";
            var prompt=new AttributedStringBuilder().style(i==0?accent():muted()).append(prefix).style(PLAIN).append(input.lines().get(i)).toAttributedString();
            rows.add(fitAttributed(prompt,w));
        }
        String hint=state.sessionFocus()?"Session "+(selected+1)+"/"+state.sessions().size()+"  ·  Enter open  ·  Delete trash  ·  Esc back"
                :state.chatFocus()?"History  ·  ↑↓ scroll  ·  PgUp/PgDn page  ·  End follow  ·  Enter return"
                :w<45?"Enter send  ·  Ctrl-Q exit":w<90?"Enter send  ·  Ctrl-N newline  ·  Tab/Ctrl-G focus  ·  Ctrl-Q exit"
                :"Enter send  ·  Ctrl-N/Alt-Enter newline  ·  F6 copy  ·  Ctrl-G history  ·  Tab sessions  ·  Ctrl-Q exit";
        rows.add(fitted(hint,w,muted()));
        return new PrototypeRenderer.Frame(List.copyOf(rows),inputStart+input.cursorRow(),Math.min(w-1,6+input.cursorColumn()),side>0);
    }

    private static AttributedString fitAttributed(AttributedString value,int width){
        if(width<=0)return new AttributedString("");var clipped=value.columnSubSequence(0,width);int pad=Math.max(0,width-clipped.columnLength());
        return new AttributedStringBuilder().append(clipped).append(" ".repeat(pad),PLAIN).toAttributedString();
    }
    private static AttributedString fitted(String text,int width,AttributedStyle style){return fitAttributed(new AttributedString(text,style),width);}
    private static AttributedString join(AttributedString left,String right,int leftWidth,int rightWidth){
        return fitAttributed(new AttributedStringBuilder().append(left).append(" ",muted()).append(right,PLAIN).toAttributedString(),leftWidth+1+rightWidth);
    }
}
