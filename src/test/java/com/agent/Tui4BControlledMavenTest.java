package com.agent;

import com.agent.llm.*;
import com.agent.tui.runtime.*;
import com.agent.tui.prototype.Tui4BFixture;
import com.agent.execution.secure.*;
import com.agent.tool.execution.DefaultProcessRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: executes ONLY the fixed trusted fixture generated here, never an existing user project. */
@EnabledIfSystemProperty(named="tui4b.controlledMaven",matches="true")
class Tui4BControlledMavenTest {
    @Test @Timeout(100) void originalR1ToolNativeChannelAndActualOfflineMaven()throws Exception{
        Path experiment=Path.of("benchmark-runs","tui4b-controlled-maven-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectory(experiment);
        Path w=TuiSessionWorkspace.create(experiment.resolve("workspace"));Tui4BFixture.create(w);var model=new AtomicInteger();
        LLMClient provider=messages->model.incrementAndGet()==1?new LLMResponse("",List.of(new ToolCall("controlled-maven","run_maven_test","{}")))
                :new LLMResponse("受控测试请求已处理，以真实工具证据为准。",List.of());
        try(var channel=TestingTuiChannels.create();var adapter=new RealAgentAdapter(w,provider,true,channel,new DefaultProcessRunner(),Path.of(".m2/repository"));var c=new TuiSessionController(w,true,adapter)){
            c.loop().edit(UiEventLoop.Edit.TEXT,"请运行 Maven 测试并说明结果，不要修改文件。");assertNotNull(c.submit());
            long until=System.nanoTime()+4_000_000_000L;while(c.loop().snapshot().localApproval()==null&&System.nanoTime()<until){c.pump();Thread.sleep(5);}assertNotNull(c.loop().snapshot().localApproval());
            var request=channel.pending();assertTrue(c.approvalCommand("confirm "+request.requestId()+" APPROVE "+request.prompt().challenge()));
            until=System.nanoTime()+80_000_000_000L;while((c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<until){c.pump();Thread.sleep(15);}c.pump();
            var snapshot=new CliConversationManager(w).list().get(0).snapshot();var results=snapshot.histories().get(CliMode.CODE).stream().filter(m->m.role().equals("tool")).map(Message::content).toList();
            Files.createDirectory(experiment.resolve("evidence"));Files.writeString(experiment.resolve("evidence/tool-result.txt"),String.join("\n",results));
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(experiment.resolve("evidence/approval-audit.json").toFile(),channel.audit());
            System.out.println("TUI4B_CONTROLLED_FIXTURE="+experiment);
            assertTrue(results.stream().anyMatch(text->text.contains("testStatus=TEST_PASSED")&&text.contains("testsRun=1")&&text.contains("executed=true")&&text.contains("approvalStatus=APPROVED")),"See isolated fixture evidence/tool-result.txt for actual failure category");
            assertEquals(1,channel.audit().stream().filter(a->a.status().equals("START_PERMITTED")).count());
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());
            Path xml=w.resolve("target/surefire-reports/TEST-fixture.SmokeTest.xml");assertTrue(Files.exists(xml));String report=Files.readString(xml);assertTrue(report.contains("tests=\"1\""));assertTrue(report.contains("failures=\"0\""));
        }
    }
}
