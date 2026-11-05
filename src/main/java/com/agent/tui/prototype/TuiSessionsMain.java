package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.*;
import org.jline.utils.*;
import org.jline.utils.InfoCmp.Capability;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate TUI-2 host: Fake persistence only in explicitly marked, new isolated workspaces. */
public final class TuiSessionsMain {
    /** Shared native host setup: no stdin redirection, headless fallback or alternate keyboard loop. */
    static Terminal openNativeTerminal()throws java.io.IOException{
        if(System.console()==null)throw new java.io.IOException("NATIVE_CONSOLE_REQUIRED");
        return TerminalBuilder.builder().system(true).encoding(java.nio.charset.StandardCharsets.UTF_8)
                .provider("jni").jni(true).jna(false).jansi(false).dumb(false).build();
    }
    public static PrototypeRenderer.Frame frame(UiSnapshot snapshot,ConversationViewport viewport,boolean writable){
        return TuiFrameRenderer.frame(snapshot,viewport,writable);
    }
    private static void submit(TuiSessionController controller){
        var loop=controller.loop();String raw=loop.snapshot().current().input().text(),text=raw.trim();boolean success;
        if(text.equals("/status")&&(controller.restrictedValidation()||controller.dailyMode())){
            controller.validationStatus();loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(controller.dailyMode()&&List.of("/read","/code","/chat").contains(text)){
            if(controller.dailyProfile(text.equals("/read")?CliMode.READ:text.equals("/chat")?CliMode.CHAT:CliMode.CODE))loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(text.equals("/session")||text.startsWith("/session ")){if(controller.sessionCommand(text.length()==8?"":text.substring(9)))loop.edit(UiEventLoop.Edit.CLEAR,"");return;}
        if(text.equals("/settings")||text.startsWith("/settings memory ")){
            if(text.equals("/settings"))loop.localOutput("Settings: /settings memory on | off | status (智能记忆首次确认后自动运行)");
            else controller.memoryCommand(text.endsWith(" on")?"smart on":text.endsWith(" off")?"global off":"consent status");
            loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(text.equals("/copy")||text.equals("/copy session")){if(controller.copy(text.equals("/copy session")))loop.edit(UiEventLoop.Edit.CLEAR,"");return;}
        if(text.equals("/help")){
            loop.localOutput("F6 /copy: 完整复制最近已完成回复；/copy session: 当前 profile 对话，须确认敏感输入风险");
            if(controller.dailyMode())loop.localOutput("/memory consent status|confirm|cancel|auto on|auto off (规则自动记忆；冲突仍审核).\\n/session delete [id] (selected non-active session); confirm-delete <id>; cancel; trash; restore <id>. Shared Memory is not deleted.");
            if(controller.restrictedValidation()){
                loop.localOutput("FINAL-API READ ONLY: Enter sends; Ctrl-N newline; Tab switches focus; Ctrl-Q exits.\n/status: persistent HTTP attempts and last Provider usage; /tools: actual read-only tool results.\nGlobal/auto Memory locked OFF; edits/Maven/processes disabled. "
                        +(controller.fixedValidationPrompts()?"LIVE: only the three exact documented scenario prompts are accepted once each.":"MOCK HTTP: ordinary English/Chinese and multiline input accepted; four-attempt cap still applies.")
                        +"\nEsc cancels an active task and stops this run; no automatic retries.");
                loop.edit(UiEventLoop.Edit.CLEAR,"");return;
            }
            loop.localOutput("Enter sends; Ctrl-N or Alt-Enter inserts newline; Ctrl-U clears draft. Up/Down edits logical lines.\n"
                    +(controller.dailyMode()?"DAILY: /status shows HTTP/Usage; /read strict read-only; /code Unified; /chat no workspace tools.\n":"")
                    +"Mouse wheel scrolls history (if supported); PgUp/PgDn are the keyboard fallback.\nTab sessions; Ctrl-G history focus (arrows scroll, Home oldest, End follows, Enter/Esc returns).\n"
                    +"/new | /rename <title> | /save | /sessions | /tools [tool-id] (F4) | Esc cancel | Ctrl-Q exit\n"
                    +"/memory global on|off|status; remember <text>; list; search <query>; update <id> <text>; forget <id>\n"
                    +"/memory auto on|off|status; harvest; pending; approve <candidate-id>; reject <candidate-id>\n"
                    +"/memory review <candidate-id>; replace <candidate-id> <memory-id>; versions <id>\n"
                    +"/memory retrieval keyword|semantic|hybrid|status; status|show|compact\n"
                    +(controller.dailyMode()?"/memory semantic on|off|status: separate paid-extraction consent; rules remain default.\n":"Offline extraction only. ")+"Embedding unavailable falls back to keyword. Memory changes wait until tasks stop.\n"
                    +(controller.dailyMode()?"/diff | /approval deny <request-id>; /approval confirm <id> APPROVE <nonce>. Native R1 exact confirmation required.":"/diff | /approval demo; /approval deny <request-id>. Native R1 uses /approval confirm <id> APPROVE <nonce>; requires a native approval channel."));
            loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(text.equals("/memory")||text.startsWith("/memory ")){
            if(controller.memoryCommand(text.length()==7?"status":text.substring(8).trim()))loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(text.equals("/approval")||text.startsWith("/approval ")){
            // Keep confirmation material exact: trailing whitespace must not turn a rejected R1 phrase into approval.
            String original=raw.stripLeading();
            if(controller.approvalCommand(text.length()==9?"status":original.substring(10)))loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }
        if(text.equals("/diff")){if(controller.diffCommand())loop.edit(UiEventLoop.Edit.CLEAR,"");return;}
        if(text.equals("/tools")||text.startsWith("/tools ")){if(controller.toolDetails(text.length()==6?"":text.substring(7).trim()))loop.edit(UiEventLoop.Edit.CLEAR,"");return;}
        if(text.equals("/new")){
            loop.edit(UiEventLoop.Edit.CLEAR,"");success=controller.createSession();
        }else if(text.startsWith("/rename ")){
            success=controller.rename(text.substring(8).trim());if(success)loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }else if(text.equals("/save")){
            success=controller.savePending();if(success)loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }else if(text.equals("/sessions")){
            controller.refreshHeaders();loop.edit(UiEventLoop.Edit.CLEAR,"");return;
        }else {controller.submit();return;}
        if(!success)loop.edit(UiEventLoop.Edit.TEXT,raw);
    }
    private static boolean key(TuiSessionController controller,TuiPrototypeMain.Key key,String text){
        var loop=controller.loop();var snapshot=loop.snapshot();
        if(controller.copyConfirmationPending()){
            if(key==TuiPrototypeMain.Key.ENTER)controller.resolveCopy(true);
            else if(key==TuiPrototypeMain.Key.ESC)controller.resolveCopy(false);
            else if(key==TuiPrototypeMain.Key.EXIT){controller.resolveCopy(false);return !controller.prepareExit();}
            return true;
        }
        if(controller.memoryConsentPending()){
            if(key==TuiPrototypeMain.Key.ENTER)controller.resolveMemoryConsent(true);
            else if(key==TuiPrototypeMain.Key.ESC)controller.resolveMemoryConsent(false);
            else if(key==TuiPrototypeMain.Key.EXIT){controller.resolveMemoryConsent(false);return !controller.prepareExit();}
            return true;
        }
        if(controller.deleteConfirmationPending()){
            if(key==TuiPrototypeMain.Key.ENTER)controller.resolveSelectedDelete(true);
            else if(key==TuiPrototypeMain.Key.ESC)controller.resolveSelectedDelete(false);
            else if(key==TuiPrototypeMain.Key.EXIT){controller.resolveSelectedDelete(false);return !controller.prepareExit();}
            return true;
        }
        if(key==TuiPrototypeMain.Key.COPY){controller.copy(false);return true;}
        if(key==TuiPrototypeMain.Key.EXIT)return !controller.prepareExit();
        if(key==TuiPrototypeMain.Key.TAB){loop.focus(!snapshot.sessionFocus());return true;}
        if(key==TuiPrototypeMain.Key.CHAT_FOCUS){loop.chatFocus(!snapshot.chatFocus());return true;}
        if(key==TuiPrototypeMain.Key.ESC){if(snapshot.localApproval()!=null)controller.cancel();else if(snapshot.sessionFocus())loop.focus(false);else if(snapshot.chatFocus())loop.chatFocus(false);else controller.cancel();return true;}
        if(key==TuiPrototypeMain.Key.PAGE_UP||key==TuiPrototypeMain.Key.PAGE_DOWN){loop.scroll(key==TuiPrototypeMain.Key.PAGE_UP?5:-5);return true;}
        if(snapshot.sessionFocus()){
            if(key==TuiPrototypeMain.Key.DELETE){controller.requestSelectedDelete();return true;}
            if(key==TuiPrototypeMain.Key.UP)loop.moveSelection(-1);else if(key==TuiPrototypeMain.Key.DOWN)loop.moveSelection(1);
            else if(key==TuiPrototypeMain.Key.ENTER)controller.select(snapshot.selectedSessionId());return true;
        }
        if(snapshot.chatFocus()){
            switch(key){case UP->loop.scroll(1);case DOWN->loop.scroll(-1);case HOME->loop.scroll(Integer.MAX_VALUE);case END->loop.follow();case ENTER->loop.chatFocus(false);default->{ }}return true;
        }
        switch(key){
            case ENTER -> submit(controller);
            case NEWLINE -> loop.edit(UiEventLoop.Edit.NEWLINE,"");
            case CLEAR_INPUT -> loop.edit(UiEventLoop.Edit.CLEAR,"");
            case TOOLS -> controller.toolDetails("");
            case MEMORY -> controller.memoryCommand("status");
            case FAIL -> loop.notice(controller.dailyMode()?"DAILY: errors end the current task; no automatic retry. Use /status. Draft retained.":"Mock failures: /error or /break; draft retained");
            case TEXT -> loop.edit(UiEventLoop.Edit.TEXT,text);
            case UP -> loop.edit(UiEventLoop.Edit.UP,"");case DOWN -> loop.edit(UiEventLoop.Edit.DOWN,"");
            case LEFT -> loop.edit(UiEventLoop.Edit.LEFT,"");case RIGHT -> loop.edit(UiEventLoop.Edit.RIGHT,"");
            case HOME -> loop.edit(UiEventLoop.Edit.HOME,"");case END -> loop.edit(UiEventLoop.Edit.END,"");
            case BACKSPACE -> loop.edit(UiEventLoop.Edit.BACKSPACE,"");case DELETE -> loop.edit(UiEventLoop.Edit.DELETE,"");
            default -> { }
        }return true;
    }
    static void run(Terminal terminal,TuiSessionController controller)throws Exception{
        var loop=controller.loop();var viewport=new ConversationViewport();var running=new AtomicBoolean(true);
        try(var screen=new PrototypeScreen(terminal,true)){
            var inputMode=new java.util.concurrent.atomic.AtomicReference<>(WindowsVtInputMode.notAttempted());
            var previous=terminal.handle(Terminal.Signal.INT,signal->running.set(false));
            var hook=new Thread(()->{try{loop.shutdownBackend();}finally{try{screen.close();}finally{inputMode.get().restore();}}},"tui2-cleanup");Runtime.getRuntime().addShutdownHook(hook);
            try{
                screen.enter();var activation=controller.dailyMode()?WindowsVtInputMode.enable(terminal):WindowsVtInputMode.notAttempted();inputMode.set(activation);loop.notice(activation.status());
                var input=new BindingReader(terminal.reader());var keys=TuiPrototypeMain.keys(terminal);
                bindMouseKeys(keys,terminal);
                String copyKey=org.jline.keymap.KeyMap.key(terminal,Capability.key_f6);if(copyKey!=null)keys.bind(TuiPrototypeMain.Key.COPY,copyKey);
                keys.bind(TuiPrototypeMain.Key.NEWLINE,org.jline.keymap.KeyMap.ctrl('N'),org.jline.keymap.KeyMap.esc()+"\r",org.jline.keymap.KeyMap.esc()+"\n");
                keys.bind(TuiPrototypeMain.Key.CLEAR_INPUT,org.jline.keymap.KeyMap.ctrl('U'));
                keys.bind(TuiPrototypeMain.Key.CHAT_FOCUS,org.jline.keymap.KeyMap.ctrl('G'));
                String toolsKey=org.jline.keymap.KeyMap.key(terminal,Capability.key_f4);if(toolsKey!=null)keys.bind(TuiPrototypeMain.Key.TOOLS,toolsKey);
                long nextFrame=0;boolean dirty=true;var paste=new PasteBuffer();
                while(running.get()){
                    dirty|=controller.pump()>0;int next=input.peekCharacter(15);if(next==-1)break;
                    if(next>=0&&paste.active){paste.accept(input.readCharacter(),controller);dirty=true;}
                    else if(next>=0){
                        // Resolve ESC-prefixed protocols as complete bindings; non-blocking mode can
                        // return after only ESC and leave the rest of a mouse report as draft text.
                        var key=input.readBinding(keys,null,next==27);
                        if(key==TuiPrototypeMain.Key.PASTE_START){paste.start();dirty=true;}else if(key!=null){
                        if(Boolean.getBoolean("agent.tui.mouseDebug")&&key!=TuiPrototypeMain.Key.MOUSE)loop.notice("INPUT_EVENT "+key+" focus="+(loop.snapshot().sessionFocus()?"SESSIONS":loop.snapshot().chatFocus()?"HISTORY":"INPUT"));
                        if(key==TuiPrototypeMain.Key.MOUSE){if(TerminalMouseBinding.sgrPrefix().equals(input.getLastBinding()))sgrMouse(input,loop,viewport,controller);else mouse(terminal,input,loop,viewport,controller);}
                        else if(key==TuiPrototypeMain.Key.CONTROL_SEQUENCE)TuiPrototypeMain.discardControlSequenceTail(input);
                        else running.set(key(controller,key,input.getLastBinding()));dirty=true;}}
                    var size=terminal.getSize();var snapshot=loop.snapshot();
                    if(snapshot.width()!=Math.max(1,size.getColumns())||snapshot.height()!=Math.max(1,size.getRows())){loop.resize(size.getColumns(),size.getRows());dirty=true;}
                    if(dirty&&System.nanoTime()>=nextFrame){snapshot=loop.snapshot();screen.draw(frame(snapshot,viewport,controller.writable()),snapshot.width(),snapshot.height());dirty=false;nextFrame=System.nanoTime()+50_000_000L;}
                }
            }finally{
                try{controller.close();}finally{try{screen.close();}finally{
                    if(!inputMode.get().restore())System.err.println("DAILY_TERMINAL_MODE_RESTORE_FAILED");
                    terminal.handle(Terminal.Signal.INT,previous);
                    try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException shutdown){}}}
            }
        }
    }
    /** Bracketed paste is consumed as data until its explicit end marker, never as key bindings. */
    static final class PasteBuffer {
        private static final String END="\u001b[201~";private static final int MAX=16384;
        boolean active,overflow;private final StringBuilder text=new StringBuilder(),marker=new StringBuilder();
        void start(){active=true;overflow=false;text.setLength(0);marker.setLength(0);}
        void accept(int ch,TuiSessionController controller){
            if(ch<0)return;marker.appendCodePoint(ch);
            while(!END.startsWith(marker.toString())){if(!overflow){text.append(marker.charAt(0));if(text.codePointCount(0,text.length())>MAX){overflow=true;text.setLength(0);}}marker.deleteCharAt(0);}
            if(!marker.toString().equals(END))return;
            active=false;marker.setLength(0);var state=controller.loop().snapshot();
            if(overflow){controller.loop().notice("INPUT_LIMIT: paste refused; draft retained (16384 code points maximum)");return;}
            if(state.localApproval()!=null||controller.deleteConfirmationPending()||controller.memoryConsentPending()||controller.copyConfirmationPending()||state.sessionFocus()||state.chatFocus()){
                controller.loop().notice("PASTE_REFUSED: return to normal input first");return;
            }
            controller.loop().edit(UiEventLoop.Edit.TEXT,text.toString());
        }
    }
    static void bindMouseKeys(KeyMap<TuiPrototypeMain.Key> keys,Terminal terminal){
        String x10=TerminalMouseBinding.prefix(terminal);if(x10!=null)keys.bind(TuiPrototypeMain.Key.MOUSE,x10);
        keys.bind(TuiPrototypeMain.Key.MOUSE,TerminalMouseBinding.sgrPrefix());
    }
    /** Decode through the same BindingReader so buffered mouse bytes never reach the draft/nonce. */
    static void mouse(Terminal terminal,BindingReader input,UiEventLoop loop){
        mouse(terminal,input,loop,null);
    }
    static void mouse(Terminal terminal,BindingReader input,UiEventLoop loop,ConversationViewport viewport){
        mouse(terminal,input,loop,viewport,null);
    }
    static void mouse(Terminal terminal,BindingReader input,UiEventLoop loop,ConversationViewport viewport,TuiSessionController controller){
        MouseEvent event=terminal.readMouseEvent(()->{
            if(input.peekCharacter(200)<0)throw new IllegalStateException("INCOMPLETE_MOUSE_REPORT");
            return input.readCharacter();
        });
        dispatchMouseEvent(event,loop,viewport,controller);
    }
    static void sgrMouse(BindingReader input,UiEventLoop loop,ConversationViewport viewport,TuiSessionController controller){
        try{dispatchMouseEvent(TerminalMouseBinding.readSgr(input),loop,viewport,controller);}
        catch(IllegalArgumentException|IllegalStateException malformed){loop.notice("MOUSE_EVENT_IGNORED: "+malformed.getMessage());}
    }
    private static void dispatchMouseEvent(MouseEvent event,UiEventLoop loop,ConversationViewport viewport,TuiSessionController controller){
        if(Boolean.getBoolean("agent.tui.mouseDebug"))loop.notice("MOUSE "+event.getType()+" x="+event.getX()+" y="+event.getY()+" focus="+(loop.snapshot().sessionFocus()?"SESSIONS":loop.snapshot().chatFocus()?"HISTORY":"INPUT"));
        if(viewport!=null&&controller!=null&&event.getType()==MouseEvent.Type.Pressed&&event.getButton()==MouseEvent.Button.Button1){String key=viewport.copyKeyAt(loop.snapshot(),event.getX(),event.getY());if(key!=null){controller.copyReply(key);return;}}
        if(viewport!=null&&viewport.pointer(event,loop))return;
        if(event.getType()!=MouseEvent.Type.Wheel&&event.getType()!=MouseEvent.Type.Pressed)return;
        int step=Math.max(1,Math.min(20,Integer.getInteger("agent.tui.wheelLines",3)));
        routeMouse(event,loop,step);
        
    }
    static void routeMouse(MouseEvent event,UiEventLoop loop,int step){
        if(event.getType()==MouseEvent.Type.Pressed&&event.getButton()==MouseEvent.Button.Button1){
            var s=loop.snapshot();int inputRows=InputViewport.layout(s.current().input(),Math.max(1,s.width()-7),Math.min(3,Math.max(1,s.height()/6))).lines().size();int body=s.height()-4-inputRows;
            if(s.width()>=100&&event.getX()>=0&&event.getX()<28&&event.getY()>=2&&event.getY()<2+body){
                int selected=0;for(int i=0;i<s.sessions().size();i++)if(s.sessions().get(i).sessionId().equals(s.selectedSessionId()))selected=i;
                int page=Math.max(1,body/2),index=selected/page*page+(event.getY()-2)/2;
                if(index<s.sessions().size()){loop.focus(true);loop.moveSelection(index-s.sessions().indexOf(s.current()));}
            }
            return;
        }
        int direction=event.getButton()==MouseEvent.Button.WheelUp?1:event.getButton()==MouseEvent.Button.WheelDown?-1:0;
        if(event.getType()!=MouseEvent.Type.Wheel||direction==0)return;
        var state=loop.snapshot();int w=state.width(),h=state.height();
        if(event.getX()<0||event.getY()<0||event.getX()>=w||event.getY()>=h)return;
        int inputRows=InputViewport.layout(state.current().input(),Math.max(1,w-7),Math.min(3,Math.max(1,h/6))).lines().size();
        int body=h-4-inputRows;
        boolean bodyHit=event.getY()>=2&&event.getY()<2+body;
        boolean sessions=bodyHit&&(w>=100?event.getX()<28:state.sessionFocus());
        if(sessions)loop.moveSelection(-direction);
        else if(event.getY()>=2)loop.scroll(direction*step);
    }
    public static void main(String[] args)throws Exception{
        try{start(args);}catch(Exception | java.io.IOError failure){
            // Never log exception messages/causes: JSON parsing errors can contain private history.
            String category=failureCategory(failure);
            System.err.println("TUI2_FAILED category="+category+"; data retained; no automatic retry");
            if(Boolean.getBoolean("tui2.debug"))diagnose(failure,System.err);
            throw new java.io.IOException("TUI2_FAILED: "+category+"; no real API called");
        }
    }
    static String failureCategory(Throwable failure){
        if(failure instanceof RealAgentAdapter.StopPendingException)return "AGENT_STOP_PENDING";
        for(var location:failure.getStackTrace()){
            String owner=location.getClassName(),method=location.getMethodName();
            if(owner.equals(PrototypeScreen.class.getName()))return method.equals("draw")?"TUI_RENDER_ERROR":"TERMINAL_CLEANUP_ERROR";
            if(owner.equals(UiEventLoop.class.getName())&&(method.equals("reduce")||method.equals("pump")))return "AGENT_EVENT_ERROR";
            if(owner.equals(TuiSessionController.class.getName())||owner.equals("com.agent.CliSessionStore"))return "SESSION_OPERATION_OR_SAVE_ERROR";
        }
        return failure instanceof java.io.IOException?"SESSION_OR_TERMINAL_IO_ERROR":"JAVA_BUSINESS_ERROR";
    }
    static void diagnose(Throwable failure,java.io.PrintStream output){
        output.println("TUI2_DIAGNOSTIC type="+failure.getClass().getSimpleName()+" category="+failureCategory(failure));
        int count=0;for(var location:failure.getStackTrace()){
            if(count++==12)break;
            output.println("  at "+location.getClassName()+"."+location.getMethodName()+":"+location.getLineNumber());
        }
        // Cleanup exceptions must remain visible without disclosing their messages.
        for(var suppressed:failure.getSuppressed())output.println("TUI2_CLEANUP_FAILURE type="+suppressed.getClass().getSimpleName()+" category="+failureCategory(suppressed));
    }
    private static void start(String[] args)throws Exception{
        if(args.length<2||args.length>3||!List.of("--create","--workspace","--inspect").contains(args[0])
                ||(args.length==3&&!args[2].equals("--headless-demo")))throw new IllegalArgumentException("Use --create/--workspace/--inspect <path> [--headless-demo]");
        boolean headless=args.length==3,writable=!args[0].equals("--inspect");
        if(!headless&&System.console()==null){System.out.println("TUI2_NATIVE_NOT_AVAILABLE; no workspace opened");return;}
        Path workspace=args[0].equals("--create")?TuiSessionWorkspace.create(Path.of(args[1])):Path.of(args[1]);
        try(var adapter=new FakeAgentAdapter(r->r.text().startsWith("/fail")?FakeAgentAdapter.Scenario.TOOL_FAILURE
                :r.text().startsWith("/error")?FakeAgentAdapter.Scenario.ERROR:FakeAgentAdapter.Scenario.STREAM,70,300);
            var controller=new TuiSessionController(workspace,writable,adapter)){
            if(headless){
                if(args[0].equals("--create")){
                    controller.loop().edit(UiEventLoop.Edit.TEXT,"帮我分析一下这个 Java 项目的整体架构");
                    if(controller.submit()==null)throw new java.io.IOException("Headless submission was not saved; no Fake task started");
                    long deadline=System.nanoTime()+5_000_000_000L;
                    while(controller.loop().snapshot().current().turn().running()&&System.nanoTime()<deadline){controller.pump();Thread.sleep(5);}
                    controller.pump();
                    if(!controller.loop().snapshot().current().turn().status().equals("COMPLETED"))throw new java.io.IOException("Headless Fake turn did not complete");
                }
                var output=new java.io.PrintWriter(new java.io.OutputStreamWriter(System.out,java.nio.charset.StandardCharsets.UTF_8),true);
                frame(controller.loop().snapshot(),new ConversationViewport(),writable).lines().forEach(line->output.println(line.toString()));return;
            }
            try(var terminal=TerminalBuilder.builder().system(true).encoding(java.nio.charset.StandardCharsets.UTF_8)
                    .provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()){
                for(var cap:List.of(Capability.enter_ca_mode,Capability.exit_ca_mode,Capability.cursor_address,Capability.clear_screen))
                    if(terminal.getStringCapability(cap)==null)throw new java.io.IOException("Unsupported terminal");
                run(terminal,controller);
            }
        }
    }
}
