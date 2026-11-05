package com.agent;

import com.agent.llm.*;
import com.agent.execution.secure.TuiMavenApprovalChannel;
import com.agent.tool.execution.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Daily application adapter: existing Core/Session/Memory with project access, no experiment limits. */
public final class DailyAgentAdapter implements SessionAgentAdapter {
    private final DailyWorkspace lease;private final DailyDeepSeekClient client;private final RealAgentAdapter core;
    private final DailyProviderDiagnosticStore diagnostics;
    private final Map<String,Long> taskStart=new ConcurrentHashMap<>();private volatile String currentTurn,taskSession="UNKNOWN",last="IDLE",usage="UNKNOWN",diagnosticWriteStatus="OK";private volatile DailyProviderDiagnosticStore.Record previousDiagnostic;private long lastAttempts;
    public DailyAgentAdapter(DailyWorkspace lease,DailyDeepSeekClient client,TuiMavenApprovalChannel approvals)throws IOException{
        this(lease,client,approvals,new DefaultProcessRunner());
    }
    DailyAgentAdapter(DailyWorkspace lease,DailyDeepSeekClient client,TuiMavenApprovalChannel approvals,ProcessRunner runner)throws IOException{
        this.lease=lease;this.client=client;this.diagnostics=new DailyProviderDiagnosticStore(lease.root());
        var consent=MemoryConsentStore.forWorkspace(lease.root());
        core=new RealAgentAdapter(lease.root(),client,true,approvals,runner,lease.root().resolve(".m2/repository"),lease::verify,true,consent);
        try{previousDiagnostic=diagnostics.read();if(previousDiagnostic!=null){last="PREVIOUS_"+previousDiagnostic.taskStatus();usage=previousDiagnostic.usage();}}catch(IOException|RuntimeException ignored){diagnosticWriteStatus="UNAVAILABLE";}
    }
    public Mode mode(){return Mode.DAILY;}public boolean dailyMode(){return true;}
    public boolean agentManagedMemory(){return Boolean.parseBoolean(System.getProperty("agent.memory.agentManaged","true"));}
    public String providerLabel(){return "DeepSeek";}public String modelLabel(){return "deepseek-flash "+(client.offline()?"[MOCK HTTP / DAILY]":"[DAILY]");}
    public String capabilities(){return core.capabilities();}public boolean available(){return core.available();}
    public String submissionRejection(String text){return client.containsCredential(text)?"CREDENTIAL_INPUT_REJECTED: not sent or stored":null;}
    public Path writableWorkspace(Path path)throws IOException{return lease.verify(path);}
    public TuiMavenApprovalChannel nativeApprovals(){return core.nativeApprovals();}
    public List<Stopped> drainStopped(){return core.drainStopped();}
    public void prepare(String id,CliSessionSnapshot snapshot){core.prepare(id,snapshot);}
    public com.agent.memory.LlmMemoryExtractor semanticMemoryExtractor(){return new com.agent.memory.LlmMemoryExtractor(client::extractMemory);}
    public RunHandle start(Request request,EventSink events){
        currentTurn=request.turnId();taskSession=request.sessionId();taskStart.put(currentTurn,client.chatAttempts());last="RUNNING";usage="UNKNOWN (in progress)";
        persistDiagnostic(request.sessionId(),request.turnId(),"RUNNING",0,null,"IN_FLIGHT","NONE",usage);
        var handle=core.start(request,events);
        return ()->{last="CANCEL_REQUESTED";handle.cancel();client.cancelActive();Long start=taskStart.remove(request.turnId());lastAttempts=start==null?0:client.chatAttempts()-start;currentTurn=null;};
    }
    public Result takeResult(String turn){
        var result=core.takeResult(turn);if(result!=null){
            Long start=taskStart.remove(turn);lastAttempts=start==null?0:client.chatAttempts()-start;currentTurn=null;
            last=result.run().trajectory().completed()?"COMPLETED":"FAILED: "+result.run().trajectory().terminationReason();
            var u=result.run().usageSummary();usage=u.usageComplete()?"PROVIDER_REPORTED input="+u.inputTokens()+", output="+u.outputTokens()+", total="+u.totalTokens():"UNKNOWN (Provider usage incomplete)";
            persistDiagnostic(taskSession,turn,last,lastAttempts,client.httpStatus(),client.lastStatus(),client.failureCategory(),usage);
        }return result;
    }
    private void persistDiagnostic(String session,String turn,String status,long count,Integer http,String provider,String failure,String tokenUsage){
        try{var record=DailyProviderDiagnosticStore.record(session,turn,status,count,http,provider,failure,tokenUsage);diagnostics.write(record);previousDiagnostic=record;diagnosticWriteStatus="OK";}
        catch(IOException|RuntimeException unavailable){diagnosticWriteStatus="UNAVAILABLE";}
    }
    public String validationStatus(){
        Long start=currentTurn==null?null:taskStart.get(currentTurn);long requests=start==null?lastAttempts:client.chatAttempts()-start;
        return "DAILY | "+providerLabel()+" / "+modelLabel()+"\nWorkspace="+lease.root()+"\nBackend task session="+taskSession+"; task="+last+"; task HTTP attempts="+requests
                +"; process HTTP attempts="+client.attempts()+"; semantic extraction attempts="+client.extractionAttempts()+"; exchanges="+client.exchanges()+"\nLast HTTP (chat or extraction)="+Objects.toString(client.httpStatus(),"UNKNOWN")+" / "+client.lastStatus()+"\n"+usage
                +"\nPersisted diagnostic: .agent-cli/diagnostics/daily-provider-last.json; write="+diagnosticWriteStatus+persistedSummary()
                +"\nHistorical session HTTP total=UNKNOWN (not stored in M1); currency=UNKNOWN\nNo API request quota. Core step/repair/context protections retained. Do not run another CLI on this workspace simultaneously.";
    }
    private String persistedSummary(){var d=previousDiagnostic;return d==null?"; last=NO_RECORD":"; last="+d.taskStatus()+" http="+d.httpStatus()+" provider="+d.providerStatus()+" failure="+d.failureCategory()+" attempts="+d.httpAttempts();}
    public void close(){try{client.cancelActive();core.close();}finally{client.close();}}
}
