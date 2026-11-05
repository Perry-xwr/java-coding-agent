package com.agent.tui.prototype;

import com.agent.tui.runtime.UiSnapshot;
import com.agent.TuiSessionController;
import org.jline.utils.WCWidth;
import java.util.*;

/** Pure code-point/terminal-column layout. Never rewrites the draft or confirmation material. */
public final class InputViewport {
    public record Layout(List<String> lines,int cursorRow,int cursorColumn,int firstLine,int totalLines,boolean hidden) { }
    public static Layout layout(UiSnapshot.Input input,int columns,int maxRows){
        int width=Math.max(1,columns),rows=Math.max(1,maxRows);
        if(TuiSessionController.redactForDisplay(input.text()).startsWith("[potentially sensitive")){
            String mask="[sensitive draft hidden; Ctrl-U clears]";var lines=PrototypeRenderer.wrap(mask,width);
            int start=Math.max(0,lines.size()-rows);var visible=List.copyOf(lines.subList(start,lines.size()));
            return new Layout(visible,visible.size()-1,Math.min(width,new org.jline.utils.AttributedString(visible.get(visible.size()-1)).columnLength()),start,lines.size(),true);
        }
        int[] points=input.text().codePoints().toArray();int cursor=Math.max(0,Math.min(points.length,input.cursor()));
        var all=new ArrayList<String>();var line=new StringBuilder();int x=0,cy=0,cx=0;
        for(int i=0;i<points.length;i++){
            int cp=points[i],size=Math.max(0,WCWidth.wcwidth(cp));
            if(cp!='\n'&&size>0&&x+size>width&&!line.isEmpty()){all.add(line.toString());line.setLength(0);x=0;}
            if(i==cursor){cy=all.size();cx=x;}
            if(cp=='\n'){all.add(line.toString());line.setLength(0);x=0;}
            else{line.appendCodePoint(cp);x+=size;}
        }
        if(cursor==points.length){cy=all.size();cx=x;}all.add(line.toString());
        int start=Math.max(0,cy-rows+1);int end=Math.min(all.size(),start+rows);
        return new Layout(List.copyOf(all.subList(start,end)),cy-start,cx,start,all.size(),false);
    }
}
