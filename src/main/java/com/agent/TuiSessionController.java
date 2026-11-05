package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.*;
import com.agent.tui.runtime.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** UI-owner M1 bridge: isolated execution, existing Memory services and native R1 request presentation. */
public final class TuiSessionController implements AutoCloseable {
    public static final String DRAFT="tui2-unmanaged";
    @FunctionalInterface interface Committer {void save(CliConversationManager manager,CliSessionSnapshot snapshot)throws IOException;}
    private final Path workspace;private final boolean writable;
    private final CliConversationManager manager;
    private final CliSessions validationProfiles;
    private final UiEventLoop loop;
    private final AgentAdapter adapter;
    private MemoryCommandService memoryCommands;
    private ClipboardSink clipboard=ClipboardSink.system();private String copySession;
    void clipboardForTesting(ClipboardSink sink){clipboard=Objects.requireNonNull(sink);}
    public boolean copyConfirmationPending(){return copySession!=null;}
    public boolean copy(boolean session){
        if(loop.snapshot().localApproval()!=null||keyboardDeleteConfirmation||consentPending){loop.notice("COPY_BLOCKED: finish the current confirmation/approval first");return false;}
        var current=loop.snapshot().current();
        if(!adapter.available()||!dirty.isEmpty()||current.turn()!=null&&!current.turn().status().equals("COMPLETED")){loop.notice("COPY_BLOCKED: reply unfinished/failed/stopping or not safely saved");return false;}
        if(!loaded.containsKey(current.sessionId())){loop.notice("NO_COMPLETED_REPLY: current session is empty");return false;}
        if(session){copySession=current.sessionId();loop.localOutput("复制当前 "+current.profile()+" 的用户原文及已完成回复，可能包含敏感输入。不会包含工具协议或其他会话。\nEnter 确认 / Esc 取消");loop.follow();loop.notice("复制对话：Enter 确认 / Esc 取消");return true;}
        return copyNow(current.sessionId(),false);
    }
    private boolean copyNow(String id,boolean session){
        try{clipboard.write(ConversationCopy.select(loaded.get(id).snapshot().histories().get(profile(loaded.get(id).snapshot())),session));loop.notice("已完整复制到系统剪贴板");return true;}
        catch(ClipboardSink.Failure unavailable){loop.notice("COPY_FAILED: "+(Set.of("HEADLESS","BUSY","DENIED","UNAVAILABLE").contains(unavailable.reason)?unavailable.reason:"UNAVAILABLE"));return false;}
        catch(IllegalArgumentException rejected){loop.notice("COPY_REFUSED: "+("NO_COMPLETED_REPLY".equals(rejected.getMessage())?"no completed reply":"no copy performed; maximum 4 MiB or unsupported clipboard data"));return false;}
        catch(Exception failure){loop.notice("COPY_FAILED: clipboard unavailable, busy, headless or denied; nothing reported as copied");return false;}
        catch(java.awt.AWTError|LinkageError unavailable){loop.notice("COPY_FAILED: desktop clipboard runtime unavailable");return false;}
    }
    public void resolveCopy(boolean confirm){String id=copySession;copySession=null;if(!confirm){loop.notice("复制已取消");return;}if(id==null||!id.equals(loop.snapshot().currentSessionId())||loop.snapshot().localApproval()!=null){loop.notice("COPY_CANCELLED: context changed");return;}copyNow(id,true);}
    private MemoryConsentStore consentStore;private MemoryConsentStore.Settings consent=MemoryConsentStore.Settings.off();private boolean consentPending,smartPending,semanticConsentRequest;
    private final java.util.concurrent.ExecutorService semanticWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"daily-memory-extractor");t.setDaemon(true);return t;});
    private record SemanticJob(String session,com.agent.memory.MemoryExtractor.Source source,MemoryConsentStore.Settings policy,String reservation,int ruleCandidates,int ruleEffective){}
    private record SemanticResult(List<com.agent.memory.MemoryExtractor.Proposal> proposals,ProviderTokenUsage usage,boolean success,
                                  Map<String,Integer> rejectionReasons,String failureCode){
        private SemanticResult { proposals=List.copyOf(proposals);rejectionReasons=rejectionReasons==null?Map.of():Map.copyOf(rejectionReasons); }
        int rejected(){return rejectionReasons.values().stream().mapToInt(Integer::intValue).sum();}
    }
    private SemanticJob semanticJob;private java.util.concurrent.Future<SemanticResult> semanticFuture;private boolean semanticClosing;
    public boolean semanticMemoryBusy(){return semanticFuture!=null;}
    public boolean memoryConsentPending(){return consentPending&&smartPending;}
    public void resolveMemoryConsent(boolean confirmed){memoryCommand(confirmed?"consent confirm":"consent cancel");}
    public void enableMemoryConsent(MemoryConsentStore store){consentStore=store;reloadConsent();showConsentState();}
    private boolean reloadConsent(){if(consentStore==null)return true;try{consent=consentStore.read();return true;}catch(IOException|RuntimeException failure){consent=MemoryConsentStore.Settings.off();loop.notice("MEMORY_CONSENT_UNAVAILABLE: OFF; settings not overwritten");return false;}}
    private void showConsentState(){var s=loop.snapshot().current();loop.metadata(s.sessionId(),s.title(),s.memory()&&consent.enabled(),s.profile(),s.updatedAt());loop.memoryMetadata(s.sessionId(),s.memory()&&consent.enabled()&&consent.autoConsent(),s.retrievalMode());}
    private CliSessionSnapshot consentSnapshot(CliSessionSnapshot s){if(consentStore==null||consent.enabled())return s;return new CliSessionSnapshot(s.version(),s.savedAt(),s.activeMode(),s.lastAutoRoutedMode(),s.histories(),s.workingContext(),s.summary(),s.crossSessionMemory().withEnabled(false),s.extraction());}
    private boolean consentCommand(String action){
        if(!adapter.available()||!dirty.isEmpty()||loop.snapshot().localApproval()!=null){loop.notice("CONSENT_CHANGE_BLOCKED: wait for task/save/approval");return false;}
        if(action.equals("semantic status")){try{var d=new MemoryRunDiagnostics(workspace).latest();String recent;
            if(d==null)recent="LAST_TURN_STATUS=NO_RECORD AUTO_MEMORY_STATUS=NO_RECORD MEMORY_ENABLED="+consent.enabled()+" AUTO_MEMORY_ENABLED="+(consent.enabled()&&consent.autoConsent())+" SEMANTIC_ENABLED="+consent.semanticEnabled()+" RULE_CANDIDATES=0 SEMANTIC_STATUS=NO_RECORD SEMANTIC_SKIP_REASON=NO_RECORD EFFECTIVE_SAVES=0 PENDING=0 REJECTED=0";
            else{String automatic=autoMemoryStatus(d);String semanticSkip="SKIPPED".equals(d.status())?d.reason():"NONE";recent="LAST_TURN_STATUS="+automatic+" AUTO_MEMORY_STATUS="+automatic+" MEMORY_ENABLED="+consent.enabled()+" AUTO_MEMORY_ENABLED="+(consent.enabled()&&consent.autoConsent())+" SEMANTIC_ENABLED="+consent.semanticEnabled()+" RULE_CANDIDATES="+d.ruleCandidates()+" SEMANTIC_STATUS="+d.status()+" SEMANTIC_SKIP_REASON="+semanticSkip+" SKIP_REASON="+d.reason()+" EFFECTIVE_SAVES="+d.effective()+" PENDING="+d.pending()+" REJECTED="+d.rejected()+" REJECTION_REASONS="+formatRejectionReasons(d.rejectionReasons())+" FAILURE_CODE="+d.failureCode()+" ERROR="+d.errorCategory()+" AT="+d.at();}
            String ledger=consent.enabled()?new SemanticMemoryLedger(workspace).status(loop.snapshot().currentSessionId()):"Memory OFF; extraction disabled; shared metadata not read";loop.localOutput(recent+"\n"+ledger);
        }catch(IOException unavailable){loop.notice("SEMANTIC_DIAGNOSTICS_UNAVAILABLE: no calls allowed");}return true;}
        if(action.equals("semantic on")){if(!consent.enabled()||!consent.autoConsent()){loop.notice("Enable smart Memory first; semantic assistance has separate API consent");return false;}semanticConsentRequest=true;smartPending=true;consentPending=true;loop.localOutput("开启按需 DeepSeek 语义记忆提取：可能产生额外 API 费用，仅发送当前成功轮次的用户原文，不发送其他会话或工具内容。无每日次数上限；每会话最多3次、默认间隔120秒，无自动重试；置信度不代表事实准确率。可随时 /memory semantic off。\nEnter 明确同意费用 / Esc 取消");loop.focus(false);loop.follow();return true;}
        if(action.equals("smart on")){semanticConsentRequest=false;smartPending=true;consentPending=true;loop.localOutput("开启当前工作区智能记忆：同时允许使用已授权记忆及从以后成功轮次的用户原文自动提取、保存低风险明确约定。冲突/不确定内容仍待审核；不会保存所有聊天、工具或旧历史，不调用额外模型。可随时关闭，不删除已有数据。\nWorkspace: "+workspace+"\nEnter 确认 / Esc 取消");loop.focus(false);loop.follow();return true;}
        if(action.equals("global on")){semanticConsentRequest=false;smartPending=false;consentPending=true;loop.localOutput("Workspace: "+workspace+"\n允许使用已有长期记忆，不等于开启自动保存。可随时关闭，关闭不删除数据。\n/memory consent confirm 确认 /memory consent cancel 取消");loop.follow();return true;}
        if(action.equals("consent cancel")){consentPending=false;semanticConsentRequest=false;loop.notice("Memory consent cancelled; no setting changed");return true;}
        if(action.equals("consent status")){loop.localOutput("Workspace Memory permission="+consent.enabled()+"; automatic explicit-user memory="+consent.autoConsent()+"; semantic assistance="+consent.semanticEnabled()+" (rules first; conflicts require review); personal cross-workspace scope unavailable");return true;}
        try{
            boolean enable=consent.enabled(),auto=consent.autoConsent(),semantic=consent.semanticEnabled();
            if(action.equals("consent confirm")){if(!consentPending){loop.notice("No pending consent request");return false;}enable=true;auto=smartPending;semantic=semanticConsentRequest;consentPending=false;smartPending=false;semanticConsentRequest=false;}
            else if(action.equals("global off")){enable=false;auto=false;consentPending=false;}
            else if(action.equals("consent auto on"))auto=true;
            else if(action.equals("consent auto off"))auto=false;
            else if(action.equals("semantic off"))semantic=false;
            else {loop.notice("/memory consent status|confirm|cancel|auto on|auto off");return false;}
            consent=consentStore.update(consent,enable,auto,semantic&&enable&&auto);showConsentState();
            if(action.equals("consent confirm")&&!loop.snapshot().currentSessionId().equals(DRAFT)){
                String id=loop.snapshot().currentSessionId();var result=memoryService().execute(id,"global on");acceptMemoryResult(id,result);
                if(!result.persisted()){loop.notice("Permission saved, but current session enable failed; inspect before retry");return false;}
            }
            loop.localOutput("Memory permission saved: "+consent.enabled()+"; automatic explicit-user memory="+consent.autoConsent()+"; semantic assistance="+consent.semanticEnabled());return true;
        }catch(IOException|RuntimeException failure){reloadConsent();showConsentState();loop.notice("MEMORY_CONSENT_SAVE_FAILED: no success assumed; retry only after inspecting settings");return false;}
    }
    private String deleteId,deleteHash;
    private boolean keyboardDeleteConfirmation;
    public boolean deleteConfirmationPending(){return keyboardDeleteConfirmation;}
    public void requestSelectedDelete(){
        keyboardDeleteConfirmation=false;
        if(sessionCommand("delete "+loop.snapshot().selectedSessionId())&&deleteId!=null){keyboardDeleteConfirmation=true;loop.focus(false);loop.follow();loop.notice("删除所选会话？Enter 确认 / Esc 取消；目标已固定，详见确认信息");}
    }
    public void resolveSelectedDelete(boolean confirm){
        String id=deleteId;keyboardDeleteConfirmation=false;
        sessionCommand(confirm&&id!=null?"confirm-delete "+id:"cancel");loop.focus(true);
    }
    public boolean sessionCommand(String action){
        if(!dailyMode()){loop.notice("Session trash is Daily-only");return false;}
        if(!adapter.available()||!dirty.isEmpty()||loop.snapshot().localApproval()!=null||loop.snapshot().sessions().stream().anyMatch(s->s.turn()!=null&&s.turn().running())){deleteId=null;loop.notice("SESSION_OPERATION_BLOCKED: running task, approval or unsaved data");return false;}
        try{
            var trash=new SessionTrash(workspace);String[] parts=action.trim().split("\\s+");
            if(action.equals("cancel")){deleteId=null;deleteHash=null;loop.notice("Deletion cancelled; no file moved");return true;}
            if(action.equals("trash")){loop.localOutput("Current workspace trash (Memory unchanged):\n"+String.join("\n",trash.list()));return true;}
            if(parts[0].equals("delete")){
                String id=parts.length==2?parts[1]:loop.snapshot().selectedSessionId();
                if(id.equals(loop.snapshot().currentSessionId())||id.startsWith("foreign:")||id.equals(DRAFT)){deleteId=null;loop.notice("DELETE_REFUSED: 当前活动会话或其他工作区会话不可删除；请先切换到另一个会话");return false;}
                var target=manager.load(id);deleteId=id;deleteHash=trash.fingerprint(id);
                loop.localOutput("DELETE CONFIRMATION: "+SessionUiText.safe(target.title(),120)+" | "+id+" | "+workspace+"\n/session confirm-delete "+id+"\n/session cancel — shared Memory is not deleted. Close other CLI instances first.");return true;
            }
            if(parts.length==2&&parts[0].equals("confirm-delete")){
                if(deleteId==null||!deleteId.equals(parts[1])||deleteId.equals(loop.snapshot().currentSessionId()))throw new IOException("No matching pending deletion");
                String id=deleteId;deleteId=null;trash.delete(id,deleteHash);loaded.remove(id);loop.removeSession(id);refreshHeaders();loop.localOutput("Session moved to trash; shared Memory unchanged");return true;
            }
            if(parts.length==2&&parts[0].equals("restore")){trash.restore(parts[1]);refreshHeaders();loop.localOutput("Session restored; history and Memory settings preserved");return true;}
            loop.notice("/session delete [id] | confirm-delete <id> | cancel | trash | restore <id>");return false;
        }catch(IOException|RuntimeException failure){deleteId=null;loop.notice("SESSION_TRASH_FAILED: no success assumed; preserve files and close other writers");return false;}
    }
    private GlobalWorkspaceRegistry globalRegistry;
    public void enableGlobalRegistry(GlobalWorkspaceRegistry registry)throws IOException{
        if(!dailyMode())throw new IOException("Daily catalog only");
        registry.register(workspace);globalRegistry=registry;refreshHeaders();
    }
    private final TuiApprovalGate approvals=new TuiApprovalGate();
    private final Map<String,com.agent.agent.TaskChangeReview> changes=new HashMap<>();
    private final Set<String> extractAfterSave=new HashSet<>();
    private final Committer committer;
    private final Map<String,CliConversation> loaded=new HashMap<>();
    private final Map<String,CliSessionSnapshot> dirty=new LinkedHashMap<>();
    private final Set<String> automaticTitles=new HashSet<>(),endedTurns=new HashSet<>(),submittedTurns=new HashSet<>();
    public TuiSessionController(Path workspace,boolean writable,AgentAdapter adapter)throws IOException {
        this(workspace,writable,adapter,CliConversationManager::checkpoint);
    }
    TuiSessionController(Path path,boolean writable,AgentAdapter adapter,Committer committer)throws IOException {
        this.workspace=writable?adapter.writableWorkspace(path):TuiSessionWorkspace.readable(path);
        this.writable=writable;this.adapter=adapter;this.committer=committer;manager=new CliConversationManager(this.workspace);
        // Construct existing system prompts/validators only. These Agents are NEVER run.
        LLMClient denied=messages->{throw new IOException("No production Agent/Provider in TUI2");};
        validationProfiles=CliAgentFactory.createProfiles(denied,this.workspace,AgentEventListener.NO_OP);
        var headers=manager.headers();var seeds=new ArrayList<UiEventLoop.SessionSeed>();
        for(var h:headers)seeds.add(new UiEventLoop.SessionSeed(h.id(),SessionUiText.safe(h.title(),120),false,List.of()));
        if(seeds.isEmpty())seeds.add(new UiEventLoop.SessionSeed(DRAFT,"New session",false,List.of()));
        loop=new UiEventLoop(seeds,adapter,64);
        if(!headers.isEmpty()&&!select(headers.get(0).id()))loop.notice("SESSION_LOAD_FAILED; current state retained");
        refreshHeaders();
    }
    public UiEventLoop loop(){return loop;}
    public boolean restrictedValidation(){return adapter.restrictedValidation();}
    public boolean dailyMode(){return adapter.dailyMode();}
    public boolean fixedValidationPrompts(){return adapter.fixedValidationPrompts();}
    public void validationStatus(){
        var s=loop.snapshot().current();loop.localOutput(adapter.validationStatus()+(adapter.dailyMode()?"\nSession="+s.sessionId()+" | "+s.title()+" | profile="+s.profile()+" | Global Memory "+(s.memory()?"ON":"OFF")+" | auto "+(s.autoMemory()?"ON":"OFF")+" | "+s.retrievalMode()+" | unsavedSnapshots="+dirty.size():""));
    }
    public boolean writable(){return writable;}
    public Path workspace(){return workspace;}
    private boolean writesAllowed(){
        if(!writable){loop.notice("READ_ONLY_WORKSPACE: Fake writes and submissions disabled");return false;}
        try{adapter.writableWorkspace(workspace);return true;}
        catch(IOException unsafe){loop.notice("ISOLATION_MARKER_INVALID: writes refused");return false;}
    }
    private CliMode profile(CliSessionSnapshot snapshot){return snapshot.activeMode()==CliMode.AUTO
            ?(snapshot.lastAutoRoutedMode()==null?CliMode.CODE:snapshot.lastAutoRoutedMode()):snapshot.activeMode();}
    private String profileLabel(CliSessionSnapshot snapshot){return snapshot.activeMode()==CliMode.AUTO?"AUTO -> "+profile(snapshot)
            :snapshot.activeMode()==CliMode.CODE?"UNIFIED":snapshot.activeMode().name();}
    private void validate(CliSessionSnapshot snapshot){
        if(adapter.restrictedValidation())FinalApiTuiAdapter.validate(snapshot);
        validationProfiles.validateHistories(snapshot.histories());new CliWorkingContext().validateSnapshot(snapshot.workingContext());
    }
    public static String redactForDisplay(String text){
        if(SessionUiText.safe(text,1).startsWith("[potentially sensitive"))return "[potentially sensitive text hidden]";
        return text.replace("\r\n","\n").replace('\r','\n').replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]"," ");
    }
    private String replyKey(String id,CliMode mode,ConversationCopy.Reply reply){return id+":"+mode+":"+reply.userIndex()+":"+com.agent.memory.SessionSummary.digest(reply.text());}
    public boolean copyReply(String key){
        if(loop.snapshot().localApproval()!=null||keyboardDeleteConfirmation||consentPending||copyConfirmationPending()){loop.notice("COPY_BLOCKED: confirmation/approval pending");return false;}
        String id=loop.snapshot().currentSessionId();var conversation=loaded.get(id);if(conversation==null)return false;
        var mode=profile(conversation.snapshot());
        for(var reply:ConversationCopy.replies(conversation.snapshot().histories().get(mode)))if(replyKey(id,mode,reply).equals(key)){
            try{clipboard.write(ConversationCopy.bounded(reply.text()));loop.notice("已复制完整回答");return true;}
            catch(Exception|java.awt.AWTError|LinkageError failure){loop.notice("COPY_FAILED: clipboard unavailable or reply exceeds 4 MiB; no success assumed");return false;}
        }
        loop.notice("COPY_CANCELLED: reply changed or session switched");return false;
    }
    private List<UiSnapshot.Chat> history(CliConversation conversation){
        var snapshot=conversation.snapshot();var keys=new HashMap<Integer,String>();
        for(var reply:ConversationCopy.replies(snapshot.histories().get(profile(snapshot))))if(!redactForDisplay(reply.text()).startsWith("[potentially sensitive"))keys.put(reply.finalIndex(),replyKey(conversation.id(),profile(snapshot),reply));
        var messages=new ArrayList<UiSnapshot.Chat>();
        int userStart=0;
        int messageIndex=-1;
        for(var message:snapshot.histories().get(profile(snapshot))){
            messageIndex++;
            if(message.role().equals("user"))userStart=messages.size();
            if(message.role().equals("system")&&message.content().startsWith(RealAgentAdapter.FAILED_TURN_MARKER)){
                for(int i=userStart;i<messages.size();i++){
                    var chat=messages.get(i);if(chat.role().equals("Agent"))messages.set(i,new UiSnapshot.Chat(chat.role(),chat.text(),"FAILED"));
                }
            }
            if(message.role().equals("user"))messages.add(new UiSnapshot.Chat("You",redactForDisplay(message.content())));
            else if(message.role().equals("assistant")){
                if(!message.content().isBlank())messages.add(new UiSnapshot.Chat("Agent",redactForDisplay(message.content()),"",keys.getOrDefault(messageIndex,"")));
                if(!message.toolCalls().isEmpty())messages.add(new UiSnapshot.Chat("Tool","["+message.toolCalls().size()+" historical tool call(s); arguments folded]"));
            }else if(message.role().equals("tool"))messages.add(new UiSnapshot.Chat("Tool","[historical tool result folded]"));
        }
        return List.copyOf(messages);
    }
    private void show(CliConversation conversation,boolean replace){
        var snapshot=conversation.snapshot();
        loop.upsertSession(new UiEventLoop.SessionSeed(conversation.id(),SessionUiText.safe(conversation.title(),120),
                snapshot.crossSessionMemory().enabled(),replace?history(conversation):List.of()),replace);
        loop.metadata(conversation.id(),SessionUiText.safe(conversation.title(),120),snapshot.crossSessionMemory().enabled()&&(consentStore==null||consent.enabled()),
                profileLabel(snapshot),SessionUiText.localTime(conversation.updatedAt()));
        loop.memoryMetadata(conversation.id(),consentStore==null?snapshot.extraction().autoEnabled():snapshot.crossSessionMemory().enabled()&&consent.enabled()&&consent.autoConsent(),snapshot.crossSessionMemory().retrievalMode());
    }
    private boolean running(String id){return loop.snapshot().sessions().stream().filter(s->s.sessionId().equals(id))
            .anyMatch(s->s.turn()!=null&&s.turn().running());}
    public boolean select(String id){
        if(consentStore!=null)reloadConsent();
        if(id!=null&&id.startsWith("foreign:")){loop.notice("跨工作区切换将在 UX-2B 实现；当前工作区保持不变");return false;}
        invalidateApproval("SESSION_SWITCHED");
        if(DRAFT.equals(id)){
            if(loop.snapshot().sessions().stream().noneMatch(s->s.sessionId().equals(DRAFT))){loop.notice("SESSION_SWITCH_FAILED");return false;}
            if(!flush(loop.snapshot().currentSessionId()))return false;loop.select(id);return true;
        }
        try{
            boolean first=!loaded.containsKey(id);CliConversation candidate=loaded.get(id);
            if(candidate==null){candidate=manager.load(id);validate(candidate.snapshot());}
            if(!flush(loop.snapshot().currentSessionId()))return false;
            candidate=loaded.getOrDefault(id,candidate);
            // These validation profiles are not the background Agent; restore reuses M1 checks only.
            validationProfiles.restoreHistories(candidate.snapshot().histories());
            new CliWorkingContext().restore(candidate.snapshot().workingContext());
            loaded.put(id,candidate);manager.activate(candidate);show(candidate,first&&!running(id));loop.select(id);loop.notice(consentStore!=null&&consent.enabled()&&!candidate.snapshot().crossSessionMemory().enabled()?"本会话保留 Memory OFF；新会话继承授权，开启本会话仍需明确操作":"");return true;
        }catch(IOException|IllegalArgumentException failure){loop.notice("SESSION_SWITCH_FAILED: current history retained");return false;}
    }
    private CliSessionSnapshot empty(){
        var histories=new EnumMap<CliMode,List<Message>>(CliMode.class);
        validationProfiles.histories().forEach((mode,messages)->histories.put(mode,List.of(messages.get(0))));
        return new CliSessionSnapshot(1,Instant.now().toString(),adapter.restrictedValidation()?CliMode.READ:CliMode.CODE,null,histories,new CliWorkingContext().snapshot());
    }
    public boolean createSession(){
        invalidateApproval("NEW_SESSION");
        if(!writesAllowed()||!flush(loop.snapshot().currentSessionId()))return false;
        try{
            reloadConsent();var initial=empty();if(consentStore!=null&&consent.enabled())initial=new CliSessionSnapshot(initial.version(),initial.savedAt(),initial.activeMode(),initial.lastAutoRoutedMode(),initial.histories(),initial.workingContext(),initial.summary(),initial.crossSessionMemory().withEnabled(true),initial.extraction());
            var created=manager.create(initial);loaded.put(created.id(),created);automaticTitles.add(created.id());
            show(created,true);loop.select(created.id());
            if(loop.snapshot().sessions().stream().anyMatch(s->s.sessionId().equals(DRAFT)))loop.removeSession(DRAFT);
            refreshHeaders();return true;
        }catch(IOException|IllegalArgumentException failure){loop.notice("NEW_SESSION_FAILED: previous state retained");return false;}
    }
    public boolean rename(String title){
        if(!writesAllowed()||loop.snapshot().currentSessionId().equals(DRAFT))return false;
        String id=loop.snapshot().currentSessionId();
        if(!flush(id))return false;
        try{
            manager.rename(id,title);automaticTitles.remove(id);var updated=manager.load(id);loaded.put(id,updated);
            show(updated,false);refreshHeaders();loop.notice("");return true;
        }catch(IOException|IllegalArgumentException failure){loop.notice("RENAME_FAILED: previous title retained");return false;}
    }
    public boolean dailyProfile(CliMode mode){
        if(!adapter.dailyMode()||mode==CliMode.AUTO||!adapter.available()||loop.snapshot().sessions().stream().anyMatch(s->s.turn()!=null&&s.turn().running())){loop.notice("PROFILE_CHANGE_UNAVAILABLE: wait or cancel first");return false;}
        String id=loop.snapshot().currentSessionId();if(id.equals(DRAFT)&&!createSession())return false;
        id=loop.snapshot().currentSessionId();if(!flush(id))return false;
        var s=loaded.get(id).snapshot();dirty.put(id,new CliSessionSnapshot(s.version(),Instant.now().toString(),mode,null,s.histories(),s.workingContext(),s.summary(),s.crossSessionMemory(),s.extraction()));
        if(!flush(id))return false;show(loaded.get(id),true);return true;
    }
    private CliSessionSnapshot append(CliSessionSnapshot original,Message message){
        var histories=new EnumMap<CliMode,List<Message>>(original.histories());var messages=new ArrayList<>(histories.get(profile(original)));
        messages.add(message);histories.put(profile(original),List.copyOf(messages));
        return new CliSessionSnapshot(original.version(),Instant.now().toString(),original.activeMode(),original.lastAutoRoutedMode(),
                histories,original.workingContext(),original.summary(),original.crossSessionMemory(),original.extraction());
    }
    private void save(String id,CliSessionSnapshot snapshot)throws IOException{
        if(!writable)throw new IOException("Read-only session store");
        adapter.writableWorkspace(workspace);validate(snapshot);
        var previous=manager.active();
        try{
            manager.activate(loaded.get(id));committer.save(manager,snapshot);loaded.put(id,manager.active());dirty.remove(id);
            show(manager.active(),false);
        }finally{
            if(previous!=null)manager.activate(loaded.getOrDefault(previous.id(),previous));
        }
    }
    public boolean flush(String id){
        if(!dirty.containsKey(id))return true;
        try{save(id,dirty.get(id));refreshHeaders();loop.notice("");if(extractAfterSave.remove(id))automaticMemory(id);return true;}
        catch(IOException|IllegalArgumentException failure){loop.notice("SESSION_SAVE_FAILED: unsaved reply retained; retry /save before switching");return false;}
    }
    private MemoryCommandService memoryService()throws IOException{
        if(memoryCommands==null)memoryCommands=adapter.dailyMode()?MemoryCommandService.forDaily(workspace):new MemoryCommandService(workspace);return memoryCommands;
    }
    private void acceptMemoryResult(String id,MemoryCommandService.Result result){
        loaded.put(id,result.conversation());
        if(loop.snapshot().currentSessionId().equals(id))manager.activate(result.conversation());
        show(result.conversation(),false);refreshHeaders();
        if(loop.snapshot().currentSessionId().equals(id)&&!result.output().isBlank())loop.localOutput(redactForDisplay(result.output()));
    }
    public boolean memoryCommand(String action){
        loop.assertOwner();
        if(consentStore!=null){
            if(action.equals("auto off"))action="consent auto off";
            else if(action.equals("auto on"))action="smart on";
            else if(action.equals("auto status"))action="consent status";
        }
        if(consentStore!=null){reloadConsent();if(action.startsWith("consent ")||action.startsWith("semantic ")||action.equals("smart on")||action.equals("global on")||action.equals("global off"))return consentCommand(action);if(!consent.enabled()&&(action.equals("global status")||action.equals("status"))){showConsentState();return consentCommand("consent status");}if(!consent.enabled()){if(!Set.of("show","compact").contains(action)){loop.notice("Workspace Memory permission OFF; enable /memory global on first");return false;}String localId=loop.snapshot().currentSessionId();if(!localId.equals(DRAFT)&&loaded.get(localId).snapshot().crossSessionMemory().enabled()){try{var off=memoryService().execute(localId,"global off");acceptMemoryResult(localId,off);if(!off.persisted())return false;}catch(IOException failure){loop.notice("SESSION_MEMORY_PRIVACY_UPDATE_FAILED");return false;}}}}
        if(adapter.restrictedValidation()){
            loop.localOutput("FINAL-API: Global Memory OFF; auto OFF; LLM extraction/conflict/Embedding disabled. Memory commands locked.");return false;
        }
        if(!writesAllowed())return false;
        String id=loop.snapshot().currentSessionId();
        if(id.equals(DRAFT)){loop.notice("No managed session; use /new or submit a task. Global Memory remains OFF.");return false;}
        if(!MemoryCommandService.query(action)&&(!adapter.available()||loop.snapshot().sessions().stream().anyMatch(s->s.turn()!=null&&s.turn().running()))){
            loop.notice("MEMORY_CHANGE_BLOCKED_WHILE_RUNNING: cancel/wait, then retry this command; input retained");return false;
        }
        if(!flush(id))return false;
        try{
            var result=memoryService().execute(id,action);acceptMemoryResult(id,result);
            loop.notice(result.persisted()?"":"Memory operation not committed; see local output");return true;
        }catch(IOException|IllegalArgumentException failure){loop.notice("MEMORY_OPERATION_FAILED: no success assumed; persisted session retained");return false;}
    }
    private void automaticMemory(String id){
        if(consentStore!=null){reloadConsent();if(!consent.enabled()){recordMemoryDiagnostic(id,"SKIPPED","MEMORY_OFF",0,0,0,0,0,null);return;}}
        // Restricted FINAL-API runs are deliberately read-only and Memory-OFF; do not write diagnostics there.
        if(adapter.restrictedValidation())return;
        var conversation=loaded.get(id);
        if(consentStore!=null&&!consent.autoConsent()){
            recordMemoryDiagnostic(id,"SKIPPED","AUTO_CONSENT_OFF",0,0,0,0,0,null);return;
        }
        if(adapter instanceof SessionAgentAdapter managed&&managed.agentManagedMemory()){
            if(conversation==null){recordMemoryDiagnostic(id,"SKIPPED","SESSION_NOT_LOADED",0,0,0,0,0,null);return;}
            var source=AutomaticMemory.source(conversation.snapshot());
            if(source==null){recordMemoryDiagnostic(id,"SKIPPED","NO_NEW_USER_SOURCE",0,0,0,0,0,null);return;}
            var matching=conversation.snapshot().extraction().candidates().stream()
                    .filter(candidate->candidate.sourceSessionId().equals(id)&&candidate.sourceMessageRefs().contains(source.messageRef())).toList();
            int effective=(int)matching.stream().filter(candidate->candidate.reviewStatus().equals("APPROVED")).count();
            int pending=(int)matching.stream().filter(candidate->candidate.reviewStatus().equals("PENDING")).count();
            int rejected=(int)matching.stream().filter(candidate->candidate.reviewStatus().equals("REJECTED")).count();
            String reason=matching.isEmpty()?agentManagedNoProposalReason(id):"AGENT_MANAGED_PROPOSAL_PROCESSED";
            recordMemoryDiagnostic(id,matching.isEmpty()?"SKIPPED":"COMPLETED",reason,0,0,effective,pending,rejected,null);
            if(loop.snapshot().currentSessionId().equals(id)&&effective>0)loop.notice("已保存 "+effective+" 条授权长期记忆");
            else if(loop.snapshot().currentSessionId().equals(id)&&pending>0)loop.notice(pending+" 条记忆因冲突进入待审核；未覆盖现有记忆");
            return;
        }
        if(consentStore!=null){
            if(!consent.enabled()){recordMemoryDiagnostic(id,"SKIPPED","MEMORY_OFF",0,0,0,0,0,null);return;}
            if(!consent.autoConsent()){recordMemoryDiagnostic(id,"SKIPPED","AUTO_CONSENT_OFF",0,0,0,0,0,null);return;}
            if(conversation==null){recordMemoryDiagnostic(id,"SKIPPED","SESSION_NOT_LOADED",0,0,0,0,0,null);return;}
            if(!conversation.snapshot().crossSessionMemory().enabled()){recordMemoryDiagnostic(id,"SKIPPED","SESSION_GLOBAL_OFF",0,0,0,0,0,null);return;}
            try{
                var policy=consent;var original=conversation.snapshot();var reserved=AutomaticMemory.reserve(original);if(reserved==original){recordMemoryDiagnostic(id,"SKIPPED","NO_NEW_USER_SOURCE",0,0,0,0,0,null);return;}
                save(id,reserved);if(!reloadConsent()||!consent.equals(policy)){recordMemoryDiagnostic(id,"SKIPPED","CONSENT_CHANGED_AFTER_RESERVE",0,0,0,0,0,null);return;}
                int before=reserved.extraction().candidates().size();int pendingBefore=countCandidates(reserved.extraction(),"PENDING");int sharedBefore=new SharedMemoryStore(workspace).read().size();
                save(id,AutomaticMemory.extract(workspace,id,reserved,policy));
                var state=loaded.get(id).snapshot().extraction();int rules=Math.max(0,state.candidates().size()-before);int effective=Math.max(0,new SharedMemoryStore(workspace).read().size()-sharedBefore);
                String skip=scheduleSemantic(id,policy,rules,effective);
                if(skip!=null)recordMemoryDiagnostic(id,"SKIPPED",skip,rules,0,effective,countCandidates(state,"PENDING"),countCandidates(state,"REJECTED"),null);
                int newlyPending=Math.max(0,countCandidates(state,"PENDING")-pendingBefore);
                if(loop.snapshot().currentSessionId().equals(id)){if(effective>0)loop.notice("已记住 "+effective+" 条长期约定；可用 /memory list 管理");else if(newlyPending>0)loop.notice(newlyPending+" 条记忆尚不确定，暂未保存；用 /memory pending 查看");}
            }catch(Exception failure){recordMemoryDiagnostic(id,"FAILED","AUTOMATIC_MEMORY_ERROR",0,0,0,0,0,memoryFailureLocation(failure));if(loop.snapshot().currentSessionId().equals(id))loop.notice("AUTO_MEMORY_SKIPPED: task saved; inspect semantic status; no retry");}
            return;
        }
        if(conversation==null||!conversation.snapshot().crossSessionMemory().enabled()||!conversation.snapshot().extraction().autoEnabled()){recordMemoryDiagnostic(id,"SKIPPED","LEGACY_AUTO_OFF",0,0,0,0,0,null);return;}
        try{acceptMemoryResult(id,memoryService().afterCompletedTurn(id));
            if(loop.snapshot().currentSessionId().equals(id))loop.notice("Auto Memory: inspect /memory pending; shared save still requires explicit approval");}
        catch(IOException|IllegalArgumentException failure){if(loop.snapshot().currentSessionId().equals(id))loop.notice("AUTO_MEMORY_FAILED: task result remains saved; no automatic retry");}
    }
    private String agentManagedNoProposalReason(String sessionId){
        var session=loop.snapshot().sessions().stream().filter(s->s.sessionId().equals(sessionId)).findFirst().orElse(null);
        var turn=session==null?null:session.turn();
        if(turn==null)return "AGENT_MANAGED_NO_TOOL_EVENT";
        boolean proposed=turn.tools().stream().anyMatch(tool->tool.name().equals("memory_propose"));
        if(!proposed)return "AGENT_MANAGED_NO_MODEL_PROPOSAL";
        if(turn.tools().stream().anyMatch(tool->tool.name().equals("memory_propose")&&tool.status().equals("FAIL")))
            return "AGENT_MANAGED_JAVA_REJECTED_PROPOSAL";
        if(turn.tools().stream().anyMatch(tool->tool.name().equals("memory_propose")&&tool.summary().equals("REJECTED_POLICY")))
            return "AGENT_MANAGED_JAVA_REJECTED_PROPOSAL";
        return "AGENT_MANAGED_NO_NEW_ENTRY";
    }
    private static int countCandidates(com.agent.memory.ExtractionState state,String status){return (int)state.candidates().stream().filter(c->c.reviewStatus().equals(status)).count();}
    private static String autoMemoryStatus(MemoryRunDiagnostics.Entry d){
        if(!"NONE".equals(d.errorCategory())||"FAILED".equals(d.status()))return "FAILED";
        if("SCHEDULED".equals(d.status())||"RUNNING".equals(d.status()))return d.status();
        if("COMPLETED".equals(d.status())||d.ruleCandidates()>0||d.effective()>0||d.pending()>0||d.rejected()>0)return "COMPLETED";
        if("SKIPPED".equals(d.status())&&!Set.of("MEMORY_OFF","AUTO_CONSENT_OFF","SESSION_NOT_LOADED","SESSION_GLOBAL_OFF","NO_NEW_USER_SOURCE","CONSENT_CHANGED_AFTER_RESERVE","LEGACY_AUTO_OFF").contains(d.reason()))return "COMPLETED";
        return d.status();
    }
    private void recordMemoryDiagnostic(String id,String status,String reason,int rules,int semantic,int effective,int pending,int rejected,String error){
        try{new MemoryRunDiagnostics(workspace).record(new MemoryRunDiagnostics.Entry(id,Instant.now().toString(),status,reason,rules,semantic,effective,pending,rejected,error==null?"NONE":error));}
        catch(IOException failure){loop.notice("MEMORY_DIAGNOSTIC_WRITE_FAILED: "+reason);}
    }
    private void recordMemoryDiagnostic(String id,String status,String reason,int rules,int semantic,int effective,int pending,
                                        Map<String,Integer> rejectionReasons,String error){
        int rejected=rejectionReasons==null?0:rejectionReasons.values().stream().mapToInt(Integer::intValue).sum();
        try{new MemoryRunDiagnostics(workspace).record(new MemoryRunDiagnostics.Entry(id,Instant.now().toString(),status,reason,rules,semantic,effective,pending,rejected,error==null?"NONE":error,rejectionReasons));}
        catch(IOException|IllegalArgumentException failure){loop.notice("MEMORY_DIAGNOSTIC_WRITE_FAILED: "+reason);}
    }
    private void recordSemanticFailure(String id,String reason,int rules,int effective,String failureCode){
        try{new MemoryRunDiagnostics(workspace).record(new MemoryRunDiagnostics.Entry(id,Instant.now().toString(),"FAILED",reason,rules,0,effective,0,0,"SEMANTIC_FAILURE",Map.of(),failureCode));}
        catch(IOException|IllegalArgumentException failure){loop.notice("MEMORY_DIAGNOSTIC_WRITE_FAILED: "+reason);}
    }
    private static String formatRejectionReasons(Map<String,Integer> reasons){
        if(reasons==null||reasons.isEmpty())return "NONE";
        return new TreeMap<>(reasons).entrySet().stream().map(e->e.getKey()+":"+e.getValue()).collect(java.util.stream.Collectors.joining(","));
    }
    static String semanticFailureCode(Throwable failure){
        if(failure instanceof com.agent.memory.LlmMemoryExtractor.ExtractionFailure typed)return typed.reasonCode();
        if(failure instanceof IOException)return "PROVIDER_ERROR";
        return "UNEXPECTED_ERROR";
    }
    /** Store a safe code location, never exception text which can contain user data. */
    private static String memoryFailureLocation(Throwable failure){
        String type=failure.getClass().getSimpleName();
        for(StackTraceElement frame:failure.getStackTrace())if(frame.getClassName().startsWith("com.agent.")){
            String owner=frame.getClassName().substring("com.agent.".length());
            return (type+"@"+owner+"."+frame.getMethodName()+":"+frame.getLineNumber()).substring(0,Math.min(180,(type+"@"+owner+"."+frame.getMethodName()+":"+frame.getLineNumber()).length()));
        }
        return type+"@SOURCE_LOCATION_UNAVAILABLE";
    }
    public String submit(){
        if(consentStore!=null){reloadConsent();showConsentState();}
        invalidateApproval("NEW_TASK");
        if(adapter instanceof SessionAgentAdapter)pump();
        if(!writesAllowed())return null;
        if(!adapter.available()){loop.notice("AGENT_BUSY_OR_STOPPING: browsing available; wait for backend to stop");return null;}
        if(!adapter.canSubmit()){loop.notice(adapter.validationStatus());return null;}
        var selected=loop.snapshot().current();String text=selected.input().text();
        if(adapter.restrictedValidation()&&text.stripLeading().startsWith("/")){
            loop.notice("FINAL_API_COMMAND_DENIED: use /help, /status, /tools or Ctrl-Q; no Provider/configuration commands");return null;
        }
        String rejection=adapter.submissionRejection(text);if(rejection!=null){loop.notice(rejection);return null;}
        if(text.isBlank()||(selected.turn()!=null&&selected.turn().running()))return null;
        if(selected.sessionId().equals(DRAFT)){
            if(!createSession())return null;loop.edit(UiEventLoop.Edit.TEXT,text);loop.removeSession(DRAFT);
        }
        String id=loop.snapshot().currentSessionId();if(!flush(id))return null;
        if(!loaded.containsKey(id)){loop.notice("SESSION_NOT_LOADED: submission refused");return null;}
        try{
            if(automaticTitles.remove(id)){
                manager.rename(id,SessionUiText.title(text));loaded.put(id,manager.load(id));show(loaded.get(id),false);
            }
            save(id,append(loaded.get(id).snapshot(),Message.user(text)));refreshHeaders();loop.notice("");
            if(adapter instanceof SessionAgentAdapter core)core.prepare(id,consentSnapshot(loaded.get(id).snapshot()));
            String turn=loop.submit();if(turn!=null)submittedTurns.add(turn);return turn;
        }catch(IOException|IllegalArgumentException failure){loop.notice("USER_MESSAGE_SAVE_FAILED: input retained; Fake not started");return null;}
    }
    public int pump(){
        int count=loop.pump(64);
        if(finishSemantic())count++;
        var nativeChannel=adapter instanceof SessionAgentAdapter core?core.nativeApprovals():null;
        if(nativeChannel!=null){
            var request=nativeChannel.poll();
            if(request!=null){
                var source=loop.snapshot().sessions().stream().filter(s->s.sessionId().equals(request.sessionId())).findFirst().orElse(null);
                if(source==null||source.turn()==null||!source.turn().running()||!source.turn().turnId().equals(request.turnId())||!loop.snapshot().currentSessionId().equals(request.sessionId()))nativeChannel.denyPending("REQUEST_NO_LONGER_VISIBLE");
                else if(redactForDisplay(com.agent.execution.secure.TuiMavenApprovalChannel.details(request.prompt())).startsWith("[potentially sensitive")){
                    nativeChannel.denyPending("APPROVAL_DETAILS_NOT_SAFELY_DISPLAYABLE");loop.notice("R1 refused: approval details cannot be safely displayed; use a fresh non-sensitive workspace");
                }else{
                    loop.approvalView(new UiSnapshot.ApprovalView(request.requestId(),request.sessionId(),request.turnId(),request.toolName(),request.prompt().riskNotice(),true));
                    loop.localOutput("R1 MAVEN APPROVAL\nSession="+request.sessionId()+" Turn="+request.turnId()+"\nRequest="+request.requestId()+"\n"
                            +com.agent.execution.secure.TuiMavenApprovalChannel.details(request.prompt())+"\nUse /approval confirm "+request.requestId()+" APPROVE "+request.prompt().challenge()+" or /approval deny "+request.requestId());
                }count++;
            }
            if(loop.snapshot().localApproval()!=null&&loop.snapshot().localApproval().nativeRequest()&&nativeChannel.pending()==null){loop.approvalView(null);count++;}
        }
        if(approvals.expire()){loop.approvalView(null);loop.notice("APPROVAL_TIMED_OUT: denied; no execution");count++;}
        if(adapter instanceof SessionAgentAdapter&&adapter.available()&&loop.snapshot().notice().startsWith("CANCEL_REQUESTED:"))
            loop.notice("BACKEND_STOPPED: cancelled partial output was not saved as a completed reply");
        for(var session:loop.snapshot().sessions()){
            var turn=session.turn();if(turn==null||turn.running()||!endedTurns.add(turn.turnId()))continue;
            if(!writable||!submittedTurns.remove(turn.turnId())){loop.notice("UNTRACKED_OR_READ_ONLY_REPLY_NOT_SAVED");continue;}
            if(adapter instanceof SessionAgentAdapter core){
                var result=core.takeResult(turn.turnId());
                boolean authoritative=result!=null&&(
                        turn.status().equals("COMPLETED")&&result.run().trajectory().completed()
                        ||turn.status().equals("FAILED")&&turn.error().equals(result.run().trajectory().terminationReason().name()));
                if(authoritative){
                    changes.put(session.sessionId(),result.run().changeReview());
                    // Entire original Agent history, including paired calls/results; never synthesize an assistant message.
                    dirty.put(session.sessionId(),result.snapshot());
                    if(turn.status().equals("COMPLETED"))extractAfterSave.add(session.sessionId());
                    if(flush(session.sessionId())&&turn.status().equals("COMPLETED"))show(loaded.get(session.sessionId()),true);
                    if(!result.run().trajectory().completed()){
                        var diagnostic=result.run().trajectory().steps().stream().map(com.agent.agent.AgentStep::errorMessage)
                                .filter(java.util.Objects::nonNull).filter(s->!s.isBlank()).reduce((a,b)->b).orElse(result.run().trajectory().terminationReason().name());
                        loop.notice("AGENT_COMPLETION_REJECTED: "+SessionUiText.safe(diagnostic,600));
                    }
                }
                continue;
            }
            // Partial/failed/cancelled output remains visible in memory but is never saved as a valid reply.
            if(!turn.status().equals("COMPLETED")||turn.streamingText().isBlank())continue;
            if(!turn.streamingText().startsWith("[FAKE]")){loop.notice("UNVERIFIED_FAKE_REPLY_NOT_SAVED");continue;}
            var candidate=append(loaded.get(session.sessionId()).snapshot(),Message.assistant(turn.streamingText()));
            extractAfterSave.add(session.sessionId());
            dirty.put(session.sessionId(),candidate);flush(session.sessionId());
        }
        collectStopped(false);
        return count;
    }
    public void refreshHeaders(){
        try{var headers=manager.headers();var order=new ArrayList<String>();
        for(var header:headers){
            order.add(header.id());
            if(loop.snapshot().sessions().stream().noneMatch(s->s.sessionId().equals(header.id())))
                loop.upsertSession(new UiEventLoop.SessionSeed(header.id(),SessionUiText.safe(header.title(),120),false,List.of()),false);
            var view=loop.snapshot().sessions().stream().filter(s->s.sessionId().equals(header.id())).findFirst().orElseThrow();
            loop.metadata(header.id(),SessionUiText.safe(header.title(),120),view.memory(),loaded.containsKey(header.id())?view.profile():"NOT_LOADED",SessionUiText.localTime(header.updatedAt()));
        }
        loop.orderSessions(order);
        if(globalRegistry!=null)refreshGlobal();
        }catch(IOException|IllegalArgumentException failure){loop.notice("CATALOG_REFRESH_FAILED: existing sessions retained");}
    }
    public int unsavedCount(){return dirty.size();}
    /** Fixed workspace UI: registry is retained, never used as the sidebar content source. */
    private void refreshGlobal(){
        for(var stale:List.copyOf(loop.snapshot().sessions()))
            if(stale.sessionId().startsWith("foreign:"))loop.removeSession(stale.sessionId());
    }
    public boolean savePending(){
        for(String id:List.copyOf(dirty.keySet()))flush(id);
        if(dirty.isEmpty()){loop.notice("");return true;}return false;
    }
    public boolean prepareExit(){
        semanticClosing=true;
        invalidateApproval("EXIT");
        pump();if(savePending()){if(semanticFuture!=null)semanticFuture.cancel(true);semanticWorker.shutdownNow();return true;}
        semanticClosing=false;
        loop.notice("EXIT_BLOCKED_UNSAVED_REPLIES: retry /save after resolving storage failure");return false;
    }
    @Override public void close()throws IOException{
        semanticClosing=true;if(semanticFuture!=null)semanticFuture.cancel(true);semanticWorker.shutdownNow();
        if(loop.snapshot().closed())return;
        invalidateApproval("CLOSED");
        try{
            pump();for(String id:List.copyOf(dirty.keySet()))flush(id);
            if(!dirty.isEmpty())throw new IOException("UNSAVED_FAKE_REPLIES: "+dirty.size()+"; original histories retained");
        }finally{try{loop.close();}finally{collectStopped(true);}}
    }
    private String scheduleSemantic(String id,MemoryConsentStore.Settings policy,int rules,int effective)throws IOException{
        if(semanticClosing)return "CONTROLLER_CLOSING";
        if(semanticFuture!=null)return "SEMANTIC_WORKER_BUSY";
        if(!policy.semanticEnabled())return "SEMANTIC_CONSENT_OFF";
        if(!(adapter instanceof SessionAgentAdapter core)||!adapter.dailyMode())return "SEMANTIC_ADAPTER_UNAVAILABLE";
        var source=AutomaticMemory.source(loaded.get(id).snapshot());if(source==null)return "NO_USER_SOURCE";
        if(MemoryQualityPolicy.wholeMessageProhibited(source.userText()))return "WHOLE_MESSAGE_MEMORY_DISABLED";
        if(!MemoryQualityPolicy.safeSource(source.userText()))return "SOURCE_NOT_DURABLE_OR_UNSAFE";
        if(!MemoryQualityPolicy.durable(source.userText()))return "NO_SEMANTIC_CANDIDATE";
        if(!MemoryQualityPolicy.semanticNeeded(source))return "RULES_COVERED_OR_NOT_NEEDED";
        var authorized=new SharedMemoryStore(workspace).read().stream().filter(m->m.workspaceScope().equals(workspace.toAbsolutePath().normalize().toString())).toList();
        // The conflict detector accepts one-line memory content. A user turn may
        // legitimately contain several paragraphs; normalize line boundaries for
        // this comparison only, while retaining the original source for extraction,
        // evidence validation, and persistence.
        String conflictProbe=source.userText().replace('\r',' ').replace('\n',' ').replaceFirst("[。.;]+$","");
        if(MemoryQualityPolicy.relation(conflictProbe,authorized)==com.agent.memory.ConflictDetector.Relation.DUPLICATE)return "SOURCE_DUPLICATE";
        var extractor=core.semanticMemoryExtractor();if(extractor==null)return "EXTRACTOR_UNAVAILABLE";
        var reservation=new SemanticMemoryLedger(workspace).reserveDetailed(id,source.userText());if(!reservation.granted())return reservation.skipReason();
        semanticJob=new SemanticJob(id,source,policy,reservation.reservationId(),rules,effective);
        recordMemoryDiagnostic(id,"SCHEDULED","SEMANTIC_REQUEST_RESERVED",rules,0,effective,0,0,null);
        semanticFuture=semanticWorker.submit(()->{recordMemoryDiagnostic(id,"RUNNING","SEMANTIC_REQUEST_RUNNING",rules,0,effective,0,0,null);try{var batch=extractor.extractBatch(List.of(source));return new SemanticResult(batch.proposals(),extractor.usage(),true,batch.rejectionReasons(),"NONE");}catch(Exception failure){
            return new SemanticResult(List.of(),extractor.usage(),false,Map.of(),semanticFailureCode(failure));
        }});
        return null;
    }    private boolean finishSemantic(){
        if(semanticClosing||semanticFuture==null||!semanticFuture.isDone()||!adapter.available()||!dirty.isEmpty()||loop.snapshot().localApproval()!=null)return false;
        var job=semanticJob;var future=semanticFuture;semanticFuture=null;semanticJob=null;
        try{
            var result=future.get();boolean permitted=reloadConsent()&&consent.equals(job.policy())&&consent.semanticEnabled();
            var current=manager.load(job.session());permitted&=current.snapshot().crossSessionMemory().enabled();
            int effective=0,pending=0;String status,reason;
            if(permitted&&result.success()){
                loaded.put(job.session(),current);int before=new SharedMemoryStore(workspace).read().size();var updated=AutomaticMemory.semantic(workspace,job.session(),current.snapshot(),consent,job.source(),result.proposals());save(job.session(),updated);effective=Math.max(0,new SharedMemoryStore(workspace).read().size()-before);pending=countCandidates(updated.extraction(),"PENDING");status="COMPLETED";reason="SEMANTIC_RESULT_VALIDATED";
            }else if(!permitted){status="SKIPPED";reason="CONSENT_OR_SESSION_CHANGED";}else{status="FAILED";reason="SEMANTIC_"+result.failureCode();}
            new SemanticMemoryLedger(workspace).finish(job.reservation(),!permitted?"DISCARDED":result.success()?"SUCCEEDED":"FAILED",result.usage());
            if(result.success())recordMemoryDiagnostic(job.session(),status,reason,job.ruleCandidates(),result.proposals().size(),job.ruleEffective()+effective,pending,result.rejectionReasons(),null);
            else recordSemanticFailure(job.session(),reason,job.ruleCandidates(),job.ruleEffective(),result.failureCode());
            if(loop.snapshot().currentSessionId().equals(job.session()))loop.notice(!permitted?"Semantic Memory discarded: consent/session changed":result.success()?"Semantic Memory evaluated; saved eligible quotes, uncertain items pending":"Semantic Memory failed safely; chat remains completed; no retry");
        }catch(Exception failure){if(job!=null)recordMemoryDiagnostic(job.session(),"FAILED","SEMANTIC_COMMIT_OR_LEDGER_ERROR",0,0,0,0,0,failure.getClass().getSimpleName());loop.notice("SEMANTIC_MEMORY_NOT_COMMITTED: chat retained; inspect ledger/pending; no retry");}return true;
    }    private void invalidateApproval(String reason){
        loop.assertOwner();approvals.invalidate(reason);
        if(adapter instanceof SessionAgentAdapter core&&core.nativeApprovals()!=null)core.nativeApprovals().denyPending(reason);
        loop.approvalView(null);
    }
    public void cancel(){invalidateApproval("CANCELLED");loop.cancelCurrent();}
    public List<TuiApprovalGate.Audit> approvalAudit(){return approvals.audit();}
    public boolean approvalCommand(String action){
        if(adapter.restrictedValidation()){loop.notice("FINAL_API_APPROVAL_DISABLED: no file edits, Maven or process execution");return false;}
        loop.assertOwner();
        if(adapter instanceof SessionAgentAdapter core&&core.nativeApprovals()!=null&&!action.equals("demo")){
            var channel=core.nativeApprovals();var request=channel.pending();
            if(request==null){loop.notice("No pending native R1 request; old approvals cannot be reused");return false;}
            String[] parts=action.split(" ",3);
            if(parts.length<2||!List.of("confirm","deny").contains(parts[0])){loop.notice("Use /approval confirm <request-id> APPROVE <nonce> or /approval deny <id>");return false;}
            var current=loop.snapshot().current();String turn=current.turn()==null?"":current.turn().turnId();
            boolean approved=channel.confirm(parts[1],current.sessionId(),turn,parts[0].equals("confirm")&&parts.length==3?parts[2]:"DENY");
            loop.approvalView(null);loop.localOutput(approved?"R1 confirmation accepted for this request only; runtime must still revalidate and start the process.":"R1 request denied/invalid/expired; no approval granted.");return true;
        }
        if(action.equals("demo")){
            if(!adapter.available()||loop.snapshot().sessions().stream().anyMatch(s->s.turn()!=null&&s.turn().running())){
                loop.notice("Approval demo requires an idle Agent; production requests remain unavailable");return false;
            }
            String session=loop.snapshot().currentSessionId();
            if(session.equals(DRAFT)){loop.notice("Use /new first; approval demo creates no execution permission");return false;}
            var prompt=new com.agent.execution.secure.ExecutionApprovalPrompt(com.agent.memory.SessionSummary.digest(session+"offline-denial-demo"),"DEMO_ONLY",
                    com.agent.execution.secure.ExecutionRequest.Action.MAVEN_TEST,workspace.toString(),"all tests","HOST (no sandbox)",
                    "NOT CONNECTED: no execution","Host tests could execute project code as current user. Bridge disabled; denial-only demo.");
            var request=approvals.request(session,UUID.randomUUID().toString(),prompt,java.time.Duration.ofSeconds(30));
            loop.approvalView(new UiSnapshot.ApprovalView(request.requestId(),session,request.turnId(),"OFFLINE DEMO: Maven test (will not run)",prompt.riskNotice()));
            loop.localOutput("APPROVAL DEMO ONLY\nrequestId="+request.requestId()+"\nworkspace="+workspace+"\n"+prompt.riskNotice()+"\nUse /approval deny "+request.requestId()+" or Esc. Allow is unavailable.");return true;
        }
        if(action.startsWith("deny ")){
            var request=approvals.pending();if(request==null){loop.notice("No pending local approval");return false;}
            var decision=approvals.resolve(action.substring(5).trim(),loop.snapshot().currentSessionId(),request.turnId(),false);
            loop.approvalView(null);loop.localOutput("Approval="+decision.status()+"; no execution; decision bound to exact request");return true;
        }
        loop.notice("/approval demo | /approval deny <request-id>; production approval remains unavailable");return false;
    }
    public boolean diffCommand(){loop.assertOwner();var review=changes.get(loop.snapshot().currentSessionId());
        if(review==null){loop.notice("No task change review in this process");return false;}
        loop.localOutput("Task changes: "+review.changedFiles().size()+" file(s); complete="+review.diffComplete()+"\n"+review.changedFiles().stream()
                .map(f->f.path()+" | "+f.type()+" | "+f.attribution()+" | "+f.status()+"\n"+Objects.toString(f.diff(),"[diff unavailable]")).collect(java.util.stream.Collectors.joining("\n")));return true;
    }
    public boolean toolDetails(String id){
        loop.assertOwner();var turn=loop.snapshot().current().turn();
        if(turn==null||turn.tools().isEmpty()){loop.notice("No runtime tool details; restored raw tool messages remain folded");return false;}
        var tools=turn.tools().stream().filter(t->id.isBlank()||t.toolId().equals(id)).toList();
        if(tools.isEmpty()){loop.notice("Tool id not found in this session/turn; use /tools");return false;}
        String text=tools.stream().map(t->t.toolId()+" | "+t.name()+" | "+t.status()+" | "+t.summary()
                +(id.isBlank()?"":"\n"+(t.details().isBlank()?"No additional safe details available.":t.details()))).collect(java.util.stream.Collectors.joining("\n"));
        if(text.length()>4096)text=text.substring(0,4096)+"\n[Tool list truncated; use /tools <exact-tool-id>]";
        loop.localOutput(redactForDisplay(text+(id.isBlank()?"\nUse /tools <exact-tool-id> for bounded diagnostics; raw output is not expanded.":"")));return true;
    }
    private void collectStopped(boolean closing){
        if(!(adapter instanceof SessionAgentAdapter core))return;
        for(var stopped:core.drainStopped()){
            changes.put(stopped.sessionId(),stopped.changes());
            boolean same=loop.snapshot().sessions().stream().anyMatch(s->s.sessionId().equals(stopped.sessionId())&&s.turn()!=null&&s.turn().turnId().equals(stopped.turnId()));
            if(!same||!loaded.containsKey(stopped.sessionId()))continue;
            dirty.put(stopped.sessionId(),stopped.snapshot());
            if(!closing){flush(stopped.sessionId());if(loop.snapshot().currentSessionId().equals(stopped.sessionId()))loop.notice("Cancelled: completed file changes remain; /diff shows actual changes. Partial output is not a final answer.");}
            else{var previous=manager.active();try{
                adapter.writableWorkspace(workspace);validate(stopped.snapshot());manager.activate(loaded.get(stopped.sessionId()));committer.save(manager,stopped.snapshot());loaded.put(stopped.sessionId(),manager.active());dirty.remove(stopped.sessionId());
            }catch(IOException|IllegalArgumentException failure){throw new java.io.UncheckedIOException(new IOException("Stopped task history could not be saved; source files retained"));}
            finally{if(previous!=null)manager.activate(loaded.getOrDefault(previous.id(),previous));}}
        }
    }
}
