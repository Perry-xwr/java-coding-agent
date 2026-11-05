package com.agent.tui.runtime;

import org.jline.reader.impl.BufferImpl;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.agent.tui.runtime.UiEvent.*;

/** The sole state reducer. Keyboard commands and resize are owner-thread events, never worker mutations. */
public final class UiEventLoop implements AutoCloseable {
    public record SessionSeed(String id,String title,boolean memory,List<UiSnapshot.Chat> history) {
        public SessionSeed {history=List.copyOf(history);}
    }
    public enum Edit { TEXT,NEWLINE,UP,DOWN,LEFT,RIGHT,HOME,END,BACKSPACE,DELETE,CLEAR }
    private static final class TurnState {
        final String id;String status="STARTING",error="";long sequence;boolean archived;
        final StringBuilder text=new StringBuilder();
        final Map<String,UiSnapshot.Tool> tools=new LinkedHashMap<>();
        UiSnapshot.Approval approval;UsageUpdated usage=new UsageUpdated(null,null,false);
        Task lastEvent;
        Decision userDecision;
        TurnState(String id){this.id=id;}
        boolean running(){return status.equals("STARTING")||status.equals("RUNNING");}
    }
    private static final class SessionState {
        final String id;String title,profile="UNIFIED",updatedAt="";final List<UiSnapshot.Chat> history;final BufferImpl input=new BufferImpl();
        List<UiSnapshot.Chat> historyView;
        boolean memory,autoMemory;String retrievalMode="keyword";int scroll;long scrollDelta,scrollRevision;TurnState turn;
        SessionState(SessionSeed seed){id=seed.id();title=seed.title();memory=seed.memory();history=new ArrayList<>(seed.history());}
    }
    private final Thread owner=Thread.currentThread();
    private final AgentAdapter adapter;
    private final UiMailbox mailbox;
    private final LinkedHashMap<String,SessionState> sessions=new LinkedHashMap<>();
    private final Map<String,AgentAdapter.RunHandle> handles=new HashMap<>();
    private final AtomicReference<UiSnapshot> published=new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean backendClosed=new java.util.concurrent.atomic.AtomicBoolean();
    private String current,selected,notice="";private boolean focus,chatFocus,closed;private int width=110,height=26;private long ignored;
    private UiSnapshot.ApprovalView localApproval;
    public UiEventLoop(List<SessionSeed> seeds,AgentAdapter adapter,int queueCapacity) {
        if(adapter.mode()!=AgentAdapter.Mode.FAKE_ONLY&&adapter.mode()!=AgentAdapter.Mode.AGENT_CORE_OFFLINE
                &&adapter.mode()!=AgentAdapter.Mode.RESTRICTED_DEEPSEEK&&adapter.mode()!=AgentAdapter.Mode.DAILY)throw new IllegalArgumentException("Online adapter is not enabled");
        this.adapter=adapter;mailbox=new UiMailbox(queueCapacity,owner);
        for(var seed:seeds) {
            if(seed.id()==null||seed.id().isBlank()||sessions.putIfAbsent(seed.id(),new SessionState(seed))!=null)
                throw new IllegalArgumentException("Unique session IDs required");
        }
        if(sessions.isEmpty())throw new IllegalArgumentException("Sessions required");
        current=selected=sessions.keySet().iterator().next();publish();
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Only UI event loop owner may mutate state");}
    private void active(){owned();if(closed)throw new IllegalStateException("UI closed");}
    public UiSnapshot snapshot(){return published.get();}
    public void assertOwner(){active();}
    public void approvalView(UiSnapshot.ApprovalView approval){active();localApproval=approval;publish();}
    public void memoryMetadata(String id,boolean automatic,String retrieval){active();var session=sessions.get(id);session.autoMemory=automatic;session.retrievalMode=retrieval;publish();}
    public void localOutput(String text){active();var session=sessions.get(current);session.history.add(new UiSnapshot.Chat("System",text));session.historyView=null;publish();}
    public long ignoredEvents(){owned();return ignored;}
    public long backpressureCount(){return mailbox.backpressureCount();}
    public void notice(String message){active();notice=Objects.requireNonNull(message);publish();}
    public void upsertSession(SessionSeed seed,boolean replaceHistory){
        active();var session=sessions.get(seed.id());
        if(session==null)sessions.put(seed.id(),new SessionState(seed));
        else {
            session.title=seed.title();session.memory=seed.memory();
            if(replaceHistory){
                if(session.turn!=null&&session.turn.running())throw new IllegalStateException("Cannot restore a running turn");
                session.history.clear();session.history.addAll(seed.history());session.historyView=null;
            }
        }
        publish();
    }
    public void metadata(String id,String title,boolean memory,String profile,String updatedAt){
        active();var session=sessions.get(id);session.title=title;session.memory=memory;session.profile=profile;session.updatedAt=updatedAt;publish();
    }
    public void orderSessions(List<String> order){
        active();var sorted=new LinkedHashMap<String,SessionState>();
        for(String id:order)if(sessions.containsKey(id))sorted.put(id,sessions.get(id));
        sessions.forEach(sorted::putIfAbsent);sessions.clear();sessions.putAll(sorted);publish();
    }
    public void removeSession(String id){active();if(id.equals(current))throw new IllegalStateException("Current session cannot be removed");sessions.remove(id);if(id.equals(selected))selected=current;publish();}
    public void edit(Edit edit,String text) {
        active();var input=sessions.get(current).input;
        switch(edit) {
            case TEXT -> {
                String cleaned=text.replace("\r\n","\n").replace('\r','\n');
                if(input.length()+cleaned.codePointCount(0,cleaned.length())<=16384)
                    cleaned.codePoints().filter(cp->cp=='\n'||!Character.isISOControl(cp)).forEach(input::write);
                else notice="INPUT_LIMIT: insertion refused; draft retained (16384 code points maximum)";
            }
            case NEWLINE -> {if(input.length()<16384)input.write('\n');else notice="INPUT_LIMIT: draft retained";}
            case UP -> input.up();case DOWN -> input.down();
            case LEFT -> input.move(-1);case RIGHT -> input.move(1);case HOME -> input.cursor(0);
            case END -> input.cursor(input.length());case BACKSPACE -> input.backspace();case DELETE -> input.delete();case CLEAR -> input.clear();
        }
        publish();
    }
    public void focus(boolean sidebar){active();focus=sidebar;chatFocus=false;selected=current;publish();}
    public void chatFocus(boolean value){active();chatFocus=value;focus=false;publish();}
    public void follow(){active();var session=sessions.get(current);session.scroll=0;session.scrollDelta=0;session.scrollRevision++;publish();}
    public void moveSelection(int offset){
        active();var ids=new ArrayList<>(sessions.keySet());int index=Math.max(0,Math.min(ids.size()-1,ids.indexOf(selected)+offset));
        selected=ids.get(index);publish();
    }
    public void select(String id){active();if(!sessions.containsKey(id))throw new IllegalArgumentException("Unknown session");current=selected=id;focus=false;chatFocus=false;publish();}
    public void enterSelection(){select(selected);}
    public void memory(boolean enabled){active();sessions.get(current).memory=enabled;publish();}
    public void scroll(int lines){active();var session=sessions.get(current);session.scroll=(int)Math.max(0,Math.min(Integer.MAX_VALUE,(long)session.scroll+lines));session.scrollDelta+=lines;publish();}
    public void resize(int columns,int rows){active();width=Math.max(1,columns);height=Math.max(1,rows);publish();}
    public String submit(){
        active();var session=sessions.get(current);
        if(!adapter.available()||(session.turn!=null&&session.turn.running())){notice="BUSY: task already running/stopping; draft retained";publish();return null;}
        if(session.input.toString().isBlank())return null;
        String text=session.input.toString();session.input.clear();session.scroll=0;session.scrollDelta=0;session.scrollRevision++;
        var turn=new TurnState(UUID.randomUUID().toString());session.turn=turn;
        session.history.add(new UiSnapshot.Chat("You",text));session.historyView=null;notice="Message submitted; waiting for Agent events";publish();
        try {
            var request=new AgentAdapter.Request(session.id,turn.id,text,session.memory,"FAKE","scripted-tui1");
            handles.put(turn.id,adapter.start(request,mailbox));
        } catch(RuntimeException failed){fail(session,"ADAPTER_START_FAILED");publish();}
        return turn.id;
    }
    public boolean resolveSimulatedApproval(String sessionId,String turnId,String requestId,boolean approved){
        active();var session=sessions.get(sessionId);
        if(session==null||!current.equals(sessionId)||session.turn==null||!session.turn.running()
                ||!session.turn.id.equals(turnId)||session.turn.approval==null)return false;
        var approval=session.turn.approval;
        if(!approval.status().equals("PENDING")||approval.channel()!=ApprovalChannel.FAKE_TEST||!approval.requestId().equals(requestId))return false;
        var handle=handles.get(turnId);
        try {
            boolean accepted=handle!=null&&handle.resolveSimulatedApproval(requestId,approved);
            if(accepted)session.turn.userDecision=approved?Decision.SIMULATED_APPROVED:Decision.DENIED;
            return accepted;
        }catch(RuntimeException unavailable){fail(session,"APPROVAL_CHANNEL_UNAVAILABLE");publish();return false;}
    }
    public void cancelCurrent(){
        active();var session=sessions.get(current);if(session.turn==null||!session.turn.running())return;
        var handle=handles.remove(session.turn.id);if(handle!=null)handle.cancel();
        session.turn.status="CANCELLED";denyApproval(session.turn,"CANCELLED");unfinishedTools(session.turn,"CANCEL_REQUESTED");archive(session);publish();
        if(adapter.mode()==AgentAdapter.Mode.AGENT_CORE_OFFLINE||adapter.mode()==AgentAdapter.Mode.RESTRICTED_DEEPSEEK||adapter.mode()==AgentAdapter.Mode.DAILY)notice("CANCEL_REQUESTED: backend stop not confirmed; no new task until Provider returns");
    }
    public int pump(int limit){
        active();if(limit<1)throw new IllegalArgumentException("Positive event batch required");
        int count=0;for(;count<limit;count++){var event=mailbox.poll();if(event==null)break;reduce(event);}
        if(count>0)publish();return count;
    }
    private void reduce(Task event){
        var session=sessions.get(event.sessionId());var turn=session==null?null:session.turn;
        if(turn==null||!turn.id.equals(event.turnId())||!turn.running()){ignored++;return;}
        if(event.sequence()==turn.sequence){
            if(event.equals(turn.lastEvent))ignored++;else fail(session,"EVENT_SEQUENCE_CONFLICT");return;
        }
        if(event.sequence()<turn.sequence){fail(session,"EVENT_OUT_OF_ORDER");return;}
        if(event.sequence()!=turn.sequence+1){fail(session,"EVENT_SEQUENCE_GAP");return;}
        turn.sequence=event.sequence();turn.lastEvent=event;var signal=event.signal();
        if(signal instanceof CoreCompleted completed){
            if((adapter.mode()!=AgentAdapter.Mode.AGENT_CORE_OFFLINE&&adapter.mode()!=AgentAdapter.Mode.RESTRICTED_DEEPSEEK&&adapter.mode()!=AgentAdapter.Mode.DAILY)||turn.approval!=null&&turn.approval.status().equals("PENDING")){
                fail(session,"INVALID_CORE_COMPLETION");return;
            }
            turn.status=completed.completed()?"COMPLETED":"FAILED";
            turn.error=completed.completed()?"":completed.reason();
            if(!completed.completed()&&!completed.diagnostic().isBlank())
                notice="AGENT_COMPLETION_REJECTED: "+completed.diagnostic();
            unfinishedTools(turn,"UNKNOWN");archive(session);handles.remove(turn.id);
        }
        else if(signal instanceof Started){turn.status="RUNNING";if(notice.startsWith("Message submitted"))notice="";}
        else if(signal instanceof TextDelta delta)turn.text.append(delta.text());
        else if(signal instanceof ApprovalRequired required){
            if(required.requestId()==null||required.requestId().isBlank()||required.operation()==null||required.operation().isBlank()
                    ||required.channel()==null||turn.approval!=null){fail(session,"INVALID_APPROVAL_EVENT");return;}
            turn.approval=new UiSnapshot.Approval(required.requestId(),required.operation(),required.channel(),"PENDING");
        } else if(signal instanceof ApprovalResolved resolved){
            if(turn.approval==null||!turn.approval.status().equals("PENDING")||!turn.approval.requestId().equals(resolved.requestId())
                    ||resolved.decision()==null||(resolved.decision()==Decision.SIMULATED_APPROVED
                    &&(turn.approval.channel()!=ApprovalChannel.FAKE_TEST||turn.userDecision!=Decision.SIMULATED_APPROVED))){
                fail(session,"INVALID_APPROVAL_RESOLUTION");return;
            }
            var a=turn.approval;turn.approval=new UiSnapshot.Approval(a.requestId(),a.operation(),a.channel(),resolved.decision().name());
        } else if(signal instanceof ToolStarted started){
            if(turn.approval!=null&&(!turn.approval.status().equals("SIMULATED_APPROVED")||!turn.approval.operation().equals(started.name()))){fail(session,"TOOL_WITHOUT_APPROVAL");return;}
            if(started.toolId()==null||started.name()==null||turn.tools.containsKey(started.toolId())){fail(session,"INVALID_TOOL_START");return;}
            turn.tools.put(started.toolId(),new UiSnapshot.Tool(started.toolId(),started.name(),"RUNNING",""));
        } else if(signal instanceof ToolFinished finished){
            var tool=turn.tools.get(finished.toolId());if(tool==null||!tool.status().equals("RUNNING")){fail(session,"UNPAIRED_TOOL_RESULT");return;}
            turn.tools.put(tool.toolId(),new UiSnapshot.Tool(tool.toolId(),tool.name(),finished.success()?"OK":"FAIL",finished.summary(),finished.details()));
        } else if(signal instanceof UsageUpdated usage)turn.usage=usage;
        else if(signal instanceof AgentError error)fail(session,error.summary());
        else if(signal instanceof Cancelled){turn.status="CANCELLED";denyApproval(turn,"CANCELLED");unfinishedTools(turn,"CANCEL_REQUESTED");archive(session);handles.remove(turn.id);}
        else if(signal instanceof Completed completed){
            if(turn.approval!=null&&turn.approval.status().equals("PENDING")){fail(session,"COMPLETED_WITH_PENDING_APPROVAL");return;}
            if(completed.outcome()==Outcome.COMPLETED&&turn.approval!=null&&!turn.approval.status().equals("SIMULATED_APPROVED")){
                fail(session,"COMPLETED_WITH_DENIED_APPROVAL");return;
            }
            if(completed.outcome()==Outcome.COMPLETED&&turn.tools.values().stream().anyMatch(t->!t.status().equals("OK"))){fail(session,"COMPLETED_WITH_FAILED_TOOL");return;}
            turn.status=completed.outcome().name();unfinishedTools(turn,completed.outcome()==Outcome.CANCELLED?"CANCEL_REQUESTED":"UNKNOWN");archive(session);handles.remove(turn.id);
        }
    }
    private void denyApproval(TurnState turn,String status){
        if(turn.approval!=null&&turn.approval.status().equals("PENDING")){
            var a=turn.approval;turn.approval=new UiSnapshot.Approval(a.requestId(),a.operation(),a.channel(),status);
        }
    }
    private void archive(SessionState session){
        if(!session.turn.archived){
            if(!session.turn.text.isEmpty()){session.history.add(new UiSnapshot.Chat("Agent",session.turn.text.toString(),session.turn.status));session.historyView=null;}
            session.turn.archived=true;
        }
    }
    private void fail(SessionState session,String error){
        if(notice.startsWith("Message submitted"))notice="";
        session.turn.status="FAILED";session.turn.error=error;denyApproval(session.turn,"UNAVAILABLE");unfinishedTools(session.turn,"UNKNOWN");archive(session);
        var handle=handles.remove(session.turn.id);if(handle!=null)handle.cancel();
    }
    private void publish(){
        var views=new ArrayList<UiSnapshot.Session>();
        for(var session:sessions.values()){
            var t=session.turn;UiSnapshot.Turn turn=t==null?null:new UiSnapshot.Turn(t.id,t.status,t.text.toString(),t.sequence,
                    new ArrayList<>(t.tools.values()),t.approval,t.usage,t.error);
            if(session.historyView==null)session.historyView=List.copyOf(session.history);
            views.add(new UiSnapshot.Session(session.id,session.title,session.memory,adapter.providerLabel(),adapter.modelLabel(),session.historyView,
                    new UiSnapshot.Input(session.input.toString(),session.input.cursor()),session.scroll,turn,session.profile,session.updatedAt,session.autoMemory,session.retrievalMode,
                    session.profile.equals("CHAT")?"NO_WORKSPACE_TOOLS":session.profile.equals("READ")?"READ_ONLY":adapter.capabilities(),session.scrollRevision,session.scrollDelta));
        }
        published.set(new UiSnapshot(current,selected,focus,width,height,closed,views,notice,localApproval,chatFocus));
    }
    private void unfinishedTools(TurnState turn,String status){
        turn.tools.replaceAll((id,tool)->tool.status().equals("RUNNING")
                ?new UiSnapshot.Tool(id,tool.name(),status,"No terminal tool result received"):tool);
    }
    @Override public void close(){
        owned();if(closed)return;closed=true;
        // Close the queue BEFORE joining workers so a full queue cannot deadlock shutdown.
        mailbox.close();handles.values().forEach(AgentAdapter.RunHandle::cancel);handles.clear();
        for(var session:sessions.values())if(session.turn!=null&&session.turn.running()){
            session.turn.status="CANCELLED";denyApproval(session.turn,"CANCELLED");unfinishedTools(session.turn,"CANCEL_REQUESTED");archive(session);
        }
        try{shutdownBackend();}finally{publish();}
    }
    /** Resource-only emergency shutdown: no reducer/state mutation and no UI-thread join. */
    public void shutdownBackend(){
        mailbox.close();if(backendClosed.compareAndSet(false,true))adapter.close();
    }
}
