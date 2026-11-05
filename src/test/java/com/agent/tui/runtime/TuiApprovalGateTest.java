package com.agent.tui.runtime;

import com.agent.execution.secure.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TuiApprovalGateTest {
    ExecutionApprovalPrompt prompt(){return new ExecutionApprovalPrompt("a".repeat(64),"demo",ExecutionRequest.Action.MAVEN_TEST,"isolated","all","HOST","NOT CONNECTED","No sandbox; project code may run as current user");}
    String id(){return UUID.randomUUID().toString();}
    @Test void exactBindingDenialAndEvenAllowCannotLaunchBackend(){
        var gate=new TuiApprovalGate();var starts=new AtomicInteger();String session=id(),turn=id();
        var request=gate.request(session,turn,prompt(),Duration.ofSeconds(10));
        var decision=gate.resolve(request.requestId(),session,turn,true);if(decision.status()==ApprovalDecision.Status.APPROVED)starts.incrementAndGet();
        assertEquals(ApprovalDecision.Status.UNAVAILABLE,decision.status());assertEquals(0,starts.get());
        request=gate.request(session,turn,prompt(),Duration.ofSeconds(10));decision=gate.resolve(request.requestId(),session,turn,false);
        assertEquals(ApprovalDecision.Status.DENIED,decision.status());assertEquals(prompt().requestDigest(),decision.requestDigest());assertEquals("USER_DENIED",gate.audit().get(1).reason());
    }
    @Test void wrongRequestSessionOrTurnIsDeniedAndConsumed(){
        var gate=new TuiApprovalGate();for(int field=0;field<3;field++){
            String session=id(),turn=id();var request=gate.request(session,turn,prompt(),Duration.ofSeconds(1));
            assertEquals(ApprovalDecision.Status.DENIED,gate.resolve(field==0?id():request.requestId(),field==1?id():session,field==2?id():turn,true).status());assertNull(gate.pending());
        }
    }
    @Test void timeoutSwitchCancelCloseAndNoResponseFailClosed(){
        var clock=new AtomicLong();var gate=new TuiApprovalGate(clock::get);
        gate.request(id(),id(),prompt(),Duration.ofNanos(10));clock.set(11);assertTrue(gate.expire());assertEquals("TIMED_OUT",gate.audit().get(0).reason());
        for(String reason:List.of("SESSION_SWITCHED","CANCELLED","CLOSED","EVENT_ERROR","QUEUE_UNAVAILABLE")){
            gate.request(id(),id(),prompt(),Duration.ofSeconds(1));gate.invalidate(reason);assertNull(gate.pending());assertEquals(ApprovalDecision.Status.DENIED,gate.audit().get(gate.audit().size()-1).decision());
        }
        assertTrue(gate.audit().stream().noneMatch(a->a.decision()==ApprovalDecision.Status.APPROVED));
    }
    @Test void workerCannotCreateOrResolveNativeApproval(){
        var gate=new TuiApprovalGate();var failure=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{gate.request(id(),id(),prompt(),Duration.ofSeconds(1));}catch(Throwable rejected){failure.set(rejected);}});
        thread.start();try{thread.join();}catch(InterruptedException e){throw new AssertionError(e);}assertInstanceOf(IllegalStateException.class,failure.get());assertNull(gate.pending());
    }
}
