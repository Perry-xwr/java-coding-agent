package com.agent.tui.prototype;

import org.jline.reader.impl.BufferImpl;
import java.util.*;

/** In-memory fake data only. Never opens production histories or Memory. */
public final class PrototypeState {
    public enum Kind { STARTED, DELTA, COMPLETED, TOOL_STARTED, TOOL_FINISHED, TOOL_FAILED, APPROVAL_REQUIRED, ERROR }
    public record Event(String sessionId, String turnId, Kind kind, String text) { }
    public record Chat(String role, String text, String turnId) { }
    public static final class Session {
        public final String id, title;
        public final List<Chat> messages = new ArrayList<>();
        public boolean memory, running, approval;
        public String turnId = "", status = "IDLE", tool = "No tool (simulation)", draft = "";
        public int draftCursor, scroll;
        Session(String id, String title, boolean memory) { this.id=id; this.title=title; this.memory=memory; }
    }
    public final List<Session> sessions = new ArrayList<>();
    public final BufferImpl input = new BufferImpl();
    private final Thread owner = Thread.currentThread();
    public int active, selected;
    public boolean sidebarFocus;
    public PrototypeState() {
        String[] titles = {"项目架构讨论", "Memory 隔离", "中文输入测试", "工具反馈演示"};
        for (int i=0;i<4;i++) sessions.add(new Session("demo-"+(i+1), titles[i], i==1));
        for (int i=0;i<10;i++) {
            Session session=sessions.get(i<4?0:1+(i-4)/2);
            session.messages.add(new Chat(i%2==0?"You":"Agent", i%2==0
                    ? "模拟历史问题 "+(i+1)+"：请解释项目结构。\n这是一条多行中文消息，不来自真实会话。"
                    : "模拟历史回答：核心层负责业务，界面层负责显示。\n工具结果与自然语言声明应当区分。", "history"));
        }
    }
    void owned() { if(Thread.currentThread()!=owner) throw new IllegalStateException("UI state has one owner"); }
    public Session current() { return sessions.get(active); }
    public void choose() {
        owned(); current().draft=input.toString(); current().draftCursor=input.cursor();
        active=selected; input.clear(); input.write(current().draft); input.cursor(current().draftCursor); sidebarFocus=false;
    }
    public String begin() {
        owned(); Session s=current();
        if(s.running || input.toString().isBlank()) return null;
        String text=input.toString(); input.clear(); s.draft=""; s.draftCursor=0;
        s.turnId=UUID.randomUUID().toString(); s.running=true; s.approval=false; s.scroll=0; s.status="STARTING";
        s.messages.add(new Chat("You", text, s.turnId)); s.messages.add(new Chat("Agent", "", s.turnId));
        while(s.messages.size()>100) s.messages.remove(0);
        return s.turnId;
    }
    public boolean accept(Event event) {
        owned(); Session s=sessions.stream().filter(x->x.id.equals(event.sessionId())).findFirst().orElse(null);
        if(s==null || !s.turnId.equals(event.turnId()) || !s.running) return false;
        switch(event.kind()) {
            case STARTED -> s.status="STREAMING (FAKE)";
            case APPROVAL_REQUIRED -> {s.approval=true; s.status="SIMULATED APPROVAL: Y/N";}
            case DELTA -> {
                int index=s.messages.size()-1; Chat previous=s.messages.get(index);
                if(previous.text().length()+event.text().length()<=20_000)
                    s.messages.set(index,new Chat("Agent",previous.text()+event.text(),s.turnId));
            }
            case TOOL_STARTED -> {s.approval=false; s.tool="RUNNING: "+event.text();}
            case TOOL_FINISHED -> s.tool="OK: "+event.text();
            case TOOL_FAILED -> {s.approval=false; s.tool="FAIL: "+event.text();}
            case COMPLETED -> {s.running=false; s.approval=false; s.status=event.text();}
            case ERROR -> {s.running=false; s.approval=false; s.status="ERROR: "+event.text();}
        }
        return true;
    }
    public void type(String text) {
        owned(); if(input.length()+text.codePointCount(0,text.length())>2048) return;
        text.codePoints().filter(cp->!Character.isISOControl(cp)).forEach(input::write);
    }
}
