package com.agent.execution.secure;

import com.agent.tool.*;
import com.agent.tool.execution.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TuiMavenApprovalChannelTest {
    @TempDir Path root;
    String id(){return UUID.randomUUID().toString();}
    TuiMavenApprovalChannel.View waitPrompt(TuiMavenApprovalChannel channel)throws Exception{
        long end=System.nanoTime()+2_000_000_000L;TuiMavenApprovalChannel.View v;
        while((v=channel.poll())==null&&System.nanoTime()<end)Thread.sleep(3);assertNotNull(v);return v;
    }
    ExecutionApprovalPrompt prompt(List<String> command){return new ExecutionApprovalPrompt("a".repeat(64),"1234ABCD",ExecutionRequest.Action.MAVEN_TEST,
            "Windows host workspace="+root+"; command="+command,"all tests","Host","timeout=120s; max output=102400 bytes","Project code as current user; no sandbox");}
    @Test void exactNonceOneTimeGrantAndChangedCommandCannotBeReused()throws Exception{
        var calls=new AtomicInteger();ProcessRunner fake=(a,b,c,d)->{calls.incrementAndGet();return new ProcessExecutionResult(0,false,"",false,1);};
        var command=List.of("mvn.cmd","test");
        try(var channel=TestingTuiChannels.create();var turn=channel.bind(id(),id(),root)){
            var executor=Executors.newSingleThreadExecutor();try{
                var answer=executor.submit(()->turn.request(prompt(command)));var view=waitPrompt(channel);
                assertFalse(channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"Y"));assertEquals(ApprovalDecision.Status.DENIED,answer.get().status());
                answer=executor.submit(()->turn.request(prompt(command)));view=waitPrompt(channel);
                assertTrue(channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"APPROVE "+view.prompt().challenge()));assertEquals(ApprovalDecision.Status.APPROVED,answer.get().status());
                assertFalse(channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"APPROVE "+view.prompt().challenge()));
                var runner=turn.guard(fake);executor.submit(()->runner.run(command,root,Duration.ofSeconds(120),102400)).get();
                assertInstanceOf(java.io.IOException.class,assertThrows(ExecutionException.class,()->executor.submit(()->runner.run(command,root,Duration.ofSeconds(120),102400)).get()).getCause());assertEquals(1,calls.get());
                answer=executor.submit(()->turn.request(prompt(command)));view=waitPrompt(channel);channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"APPROVE "+view.prompt().challenge());answer.get();
                assertInstanceOf(java.io.IOException.class,assertThrows(ExecutionException.class,()->executor.submit(()->runner.run(List.of("mvn.cmd","clean"),root,Duration.ofSeconds(120),102400)).get()).getCause());assertEquals(1,calls.get());
            }finally{executor.shutdownNow();}
        }
    }
    @Test void originalMavenToolChecksExactApprovalAndWorkspaceAgain()throws Exception{
        Files.writeString(root.resolve("pom.xml"),"<project/>");var starts=new AtomicInteger();
        ProcessRunner fake=(a,b,c,d)->{starts.incrementAndGet();return new ProcessExecutionResult(0,false,"Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",false,1);};
        try(var channel=TestingTuiChannels.create();var turn=channel.bind(id(),id(),root)){
            var registry=ToolRegistry.withTuiCodingTools(root,fake,root.resolve(".m2/repository"),turn);
            var executor=Executors.newSingleThreadExecutor();try{
                var result=executor.submit(()->registry.execute("run_maven_test","{}"));var view=waitPrompt(channel);
                assertTrue(view.prompt().riskNotice().contains("user"));channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"APPROVE "+view.prompt().challenge());
                assertTrue(result.get().success());assertEquals(1,starts.get());
                result=executor.submit(()->registry.execute("run_maven_test","{}"));view=waitPrompt(channel);
                synchronized(channel){channel.confirm(view.requestId(),view.sessionId(),view.turnId(),"APPROVE "+view.prompt().challenge());Files.writeString(root.resolve("changed.txt"),"external change after confirmation");}
                assertEquals(ToolErrorCode.APPROVAL_DENIED,result.get().errorCode());assertEquals(1,starts.get());
            }finally{executor.shutdownNow();}
        }
    }
    @Test void timeoutSwitchCancelCloseWrongBindingAndWorkerForgeryNeverGrant()throws Exception{
        var clock=new AtomicLong();var channel=new TuiMavenApprovalChannel(Duration.ofSeconds(1),clock::get);
        var executor=Executors.newSingleThreadExecutor();try(var turn=channel.bind(id(),id(),root)){
            var answer=executor.submit(()->turn.request(prompt(List.of("mvn.cmd","test"))));var view=waitPrompt(channel);
            var v=view;assertThrows(ExecutionException.class,()->executor.submit(()->channel.confirm(v.requestId(),v.sessionId(),v.turnId(),"APPROVE 1234ABCD")).get());
            assertFalse(channel.confirm(view.requestId(),id(),view.turnId(),"APPROVE 1234ABCD"));assertEquals(ApprovalDecision.Status.DENIED,answer.get().status());
            for(String reason:List.of("SESSION_SWITCHED","CANCELLED","EXIT","EVENT_ERROR")){
                answer=executor.submit(()->turn.request(prompt(List.of("mvn.cmd","test"))));waitPrompt(channel);channel.denyPending(reason);assertEquals(ApprovalDecision.Status.DENIED,answer.get().status());
            }
            answer=executor.submit(()->turn.request(prompt(List.of("mvn.cmd","test"))));waitPrompt(channel);clock.addAndGet(2_000_000_000L);assertNull(channel.pending());assertEquals(ApprovalDecision.Status.DENIED,answer.get().status());
            answer=executor.submit(()->turn.request(prompt(List.of("mvn.cmd","test"))));waitPrompt(channel);channel.close();assertEquals(ApprovalDecision.Status.DENIED,answer.get().status());
            assertTrue(channel.audit().stream().noneMatch(a->a.status().equals("APPROVED")));
        }finally{channel.close();executor.shutdownNow();}
    }
    @Test void nativeAvailabilityAndConcurrentQueueFailClosed()throws Exception{
        if(System.console()==null)assertThrows(IllegalStateException.class,()->TuiMavenApprovalChannel.openNative(null));
        try(var channel=TestingTuiChannels.create();var a=channel.bind(id(),id(),root);var b=channel.bind(id(),id(),root)){
            var executor=Executors.newFixedThreadPool(2);try{
                var first=executor.submit(()->a.request(prompt(List.of("mvn.cmd","test"))));waitPrompt(channel);
                assertEquals(ApprovalDecision.Status.UNAVAILABLE,executor.submit(()->b.request(prompt(List.of("mvn.cmd","test")))).get().status());
                channel.denyPending("QUEUE_UNAVAILABLE");assertEquals(ApprovalDecision.Status.DENIED,first.get().status());
            }finally{executor.shutdownNow();}
        }
    }
}
