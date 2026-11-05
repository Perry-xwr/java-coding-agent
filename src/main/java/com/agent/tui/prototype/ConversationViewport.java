package com.agent.tui.prototype;

import com.agent.tui.runtime.UiSnapshot;
import java.util.*;

/** Derived per-width line cache. No persistence; immutable history identity avoids work on every frame. */
public final class ConversationViewport {
    private static final class Cache {
        int width;List<UiSnapshot.Chat> history=List.of();List<String> lines=new ArrayList<>();
        String streaming="";List<String> streamLines=List.of();
        List<Anchor> anchors=new ArrayList<>();
        Anchor top;int start,total,rows;boolean following=true;long revision=-1,command;
    }
    private record Anchor(int message,int character,boolean streaming) {
        Anchor(int message,int character){this(message,character,false);}
    }
    private final LinkedHashMap<String,Cache> caches=new LinkedHashMap<>(8,.75f,true);
    // Evict expensive wrapped text, not a session's small navigation position.
    private final Map<String,Cache> parked=new HashMap<>();
    private long wrappedMessages;
    public long wrappedMessages(){return wrappedMessages;}
    public List<String> visible(UiSnapshot.Session session,int width,int rows){
        Cache cache=caches.computeIfAbsent(session.sessionId(),id->{Cache old=parked.remove(id);return old==null?new Cache():old;});
        if(caches.size()>8){String id=caches.keySet().iterator().next();Cache old=caches.remove(id);
            old.width=0;old.history=List.of();old.lines=new ArrayList<>();old.anchors=new ArrayList<>();old.streaming="";old.streamLines=List.of();parked.put(id,old);}
        if(cache.width!=width){cache.width=width;cache.history=List.of();cache.lines.clear();cache.anchors.clear();cache.streaming="";cache.streamLines=List.of();}
        if(cache.history!=session.history()){
            int previous=cache.history.size();boolean append=previous<=session.history().size();
            for(int i=0;append&&i<previous;i++)append=cache.history.get(i).equals(session.history().get(i));
            if(!append){previous=0;cache.lines.clear();cache.anchors.clear();}
            for(int i=previous;i<session.history().size();i++){
                var chat=session.history().get(i);
                String role=chat.role().equals("System")?"Info":chat.role();
                if(chat.role().equals("Agent")&&!chat.state().isBlank()&&!chat.state().equals("COMPLETED"))role+=" [partial: "+chat.state()+"]";
                String content=com.agent.TuiSessionController.redactForDisplay(chat.text());
                if(chat.role().equals("System"))content=TuiStatusFormatter.present(content);
                String text=role+": "+content;
                var wrapped=PrototypeRenderer.wrap(text,width);int character=0;
                for(String line:wrapped){cache.lines.add(line);cache.anchors.add(new Anchor(i,character));character+=line.length();if(character<text.length()&&text.charAt(character)=='\n')character++;}
                if(!chat.copyKey().isBlank()){cache.lines.add("[复制]");cache.anchors.add(new Anchor(i,Integer.MAX_VALUE-1));}
                cache.lines.add("");wrappedMessages++;
                cache.anchors.add(new Anchor(i,Integer.MAX_VALUE));
            }
            cache.history=session.history();
        }
        String stream=session.turn()!=null&&session.turn().running()&&!session.turn().streamingText().isEmpty()?"Agent [streaming]: "+com.agent.TuiSessionController.redactForDisplay(session.turn().streamingText()):"";
        if(!cache.streaming.equals(stream)){cache.streaming=stream;cache.streamLines=stream.isEmpty()?List.of():PrototypeRenderer.wrap(stream,width);}
        var activity=new ArrayList<String>();
        if(session.turn()!=null){var tools=session.turn().tools();
            for(var tool:tools.subList(Math.max(0,tools.size()-2),tools.size())){
                String tag=switch(tool.status()){case "RUNNING"->"run";case "OK"->"ok";case "FAIL"->"fail";case "CANCEL_REQUESTED"->"cancel";default->"unknown";};
                activity.addAll(PrototypeRenderer.wrap(com.agent.TuiSessionController.redactForDisplay("["+tag+"] "+tool.name()+"  "+tool.summary()),width));
            }
            if(tools.size()>2)activity.add("More tools: /tools ("+tools.size()+" total)");
        }
        int original=cache.lines.size()+cache.streamLines.size(),total=original+activity.size();
        cache.total=total;cache.rows=rows;int bottom=Math.max(0,total-rows);
        if(cache.revision!=session.scrollRevision()){cache.following=true;cache.top=null;cache.command=0;cache.revision=session.scrollRevision();}
        int start=cache.following?bottom:locate(cache);
        long movement=session.scrollDelta()-cache.command;cache.command=session.scrollDelta();
        start=(int)Math.max(0,Math.min(bottom,(long)start-movement));
        cache.following=start==bottom;cache.start=start;
        cache.top=start<cache.anchors.size()?cache.anchors.get(start):new Anchor(session.history().size(),streamCharacter(cache,start-cache.lines.size()),true);
        int end=Math.min(total,start+rows);var result=new ArrayList<String>();
        for(int i=start;i<end;i++)result.add(i<cache.lines.size()?cache.lines.get(i):i<original?cache.streamLines.get(i-cache.lines.size()):activity.get(i-original));
        return List.copyOf(result);
    }
    private static int streamCharacter(Cache cache,int line){int offset=0;for(int i=0;i<Math.min(line,cache.streamLines.size());i++){offset+=cache.streamLines.get(i).length();if(offset<cache.streaming.length()&&cache.streaming.charAt(offset)=='\n')offset++;}return offset;}
    private static int locate(Cache cache){
        if(cache.top==null)return cache.start;
        if(cache.top.streaming()){
            if(!cache.streamLines.isEmpty())return cache.lines.size()+streamLine(cache,cache.top.character());
            // A completed stream becomes the last Agent message; the presentation prefix changes.
            for(int i=cache.history.size()-1;i>=0;i--)if(cache.history.get(i).role().equals("Agent")){
                cache.top=new Anchor(i,Math.max(0,cache.top.character()-("Agent [streaming]: ".length()-"Agent: ".length())));break;
            }
        }
        int result=-1,low=0,high=cache.anchors.size()-1;
        while(low<=high){int mid=(low+high)>>>1;var a=cache.anchors.get(mid);
            if(a.message()<cache.top.message()||a.message()==cache.top.message()&&a.character()<=cache.top.character()){result=mid;low=mid+1;}else high=mid-1;}
        if(result>=0&&cache.anchors.get(result).message()!=cache.top.message())result=-1;
        if(result>=0)return result;
        if(cache.top.message()==cache.history.size()){
            return cache.lines.size()+streamLine(cache,cache.top.character());
        }
        return cache.start;
    }
    private static int streamLine(Cache cache,int character){int offset=0,line=0;for(int i=0;i<cache.streamLines.size();i++){if(offset>character)break;line=i;offset+=cache.streamLines.get(i).length();if(offset<cache.streaming.length()&&cache.streaming.charAt(offset)=='\n')offset++;}return line;}
    String copyKeyAt(UiSnapshot state,int x,int y){
        if(state.width()<30||state.height()<8||state.localApproval()!=null||state.width()<100&&state.sessionFocus())return null;
        int left=state.width()>=100?29:0;
        if(x<left||x>=left+6||y<2)return null;
        Cache cache=caches.get(state.currentSessionId());if(cache==null||y>=2+cache.rows)return null;
        int row=cache.start+y-2;if(row>=cache.lines.size()||cache.anchors.get(row).character()!=Integer.MAX_VALUE-1||!cache.lines.get(row).equals("[复制]"))return null;
        int index=cache.anchors.get(row).message();return cache.history.get(index).copyKey();
    }
    public int thumbStart(String id){Cache c=caches.get(id);if(c==null||c.total<=c.rows)return -1;return (int)((long)c.start*(c.rows-thumbSize(id))/Math.max(1,c.total-c.rows));}
    public int thumbSize(String id){Cache c=caches.get(id);return c==null?0:Math.min(c.rows,Math.max(1,(int)((long)c.rows*c.rows/Math.max(1,c.total))));}
    private String dragging;private int grab;
    boolean pointer(org.jline.terminal.MouseEvent e,com.agent.tui.runtime.UiEventLoop loop){
        var s=loop.snapshot();Cache c=caches.get(s.currentSessionId());
        if(e.getType()==org.jline.terminal.MouseEvent.Type.Released){dragging=null;return true;}
        if(c==null||s.width()<100&&s.sessionFocus()||s.localApproval()!=null||thumbStart(s.currentSessionId())<0){dragging=null;return false;}
        boolean drag=e.getType()==org.jline.terminal.MouseEvent.Type.Dragged&&s.currentSessionId().equals(dragging);
        if(!drag&&(e.getX()!=s.width()-1||e.getY()<2||e.getY()>=2+c.rows))return false;
        int y=e.getY()-2,thumb=thumbStart(s.currentSessionId()),size=thumbSize(s.currentSessionId());
        if(e.getType()==org.jline.terminal.MouseEvent.Type.Pressed&&e.getButton()==org.jline.terminal.MouseEvent.Button.Button1){
            if(y>=thumb&&y<thumb+size){dragging=s.currentSessionId();grab=y-thumb;return true;}
            loop.scroll(y<thumb?c.rows:-c.rows);return true;
        }
        if(drag){int top=Math.max(0,Math.min(c.rows-size,y-grab));int target=(int)((long)top*(c.total-c.rows)/Math.max(1,c.rows-size));loop.scroll(c.start-target);return true;}
        return false;
    }}
