package com.agent;
import com.agent.llm.DailyDeepSeekClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class DefaultCodeIntentTest {
 @TempDir Path root;
 @ParameterizedTest @ValueSource(strings={"以后这个项目统一使用 Java 17。","记住我们后续优先用 Maven。","解释一下这段代码。","帮我分析这个项目的架构。","刚才讨论的方案有哪些优缺点？","今天电脑风扇声音比较大只是临时情况，不需要记住。","我希望以后讨论新功能时先比较方案，这次只讨论，不需要修改文件。","性能优化前应该先做什么？请参考项目约定，但不要运行工具或修改文件。","再记一条长期习惯：做性能优化前，先通过实际测量定位瓶颈，不要凭感觉优化。"})
 void nonMutationCompletesInDefaultCode(String input)throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));var helper=new DailyRuntimeTest();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->helper.answer(r,"已了解，以下仅作交流与说明。"));var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   helper.send(c,a,input);assertEquals("UNIFIED",c.loop().snapshot().current().profile());assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(1,client.attempts());assertTrue(c.loop().snapshot().current().turn().tools().isEmpty());
  }
 }
 @ParameterizedTest @ValueSource(strings={"把 pom.xml 的 Java 版本改成 17。","帮我修复这个编译错误。","实现一个新的 Memory 提取器。","修改代码并运行测试。","记住，帮我优化这个项目的性能并修改代码。","记住：以后更改 pom.xml 的 Java 版本为 17。"})
 void textualSuccessCannotReplaceRequestedMutation(String input)throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));var helper=new DailyRuntimeTest();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->helper.answer(r,"我已经完成修改。"));var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   helper.send(c,a,input);assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertFalse(Files.exists(w.resolve("pom.xml")));assertTrue(client.attempts()<=com.agent.agent.Agent.MAX_ITERATIONS);
  }
 }
 @Test void failedEditAndAmbiguityRemainSafe()throws Exception{
  Path w=Files.createDirectory(root.resolve("project"));var helper=new DailyRuntimeTest();var calls=new AtomicInteger();
  try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{return calls.incrementAndGet()==1?helper.reply(r,helper.call("apply_context_patch","{\"path\":\"missing.txt\",\"expectedContent\":\"old\",\"edits\":[{\"search\":\"old\",\"replace\":\"new\"}]}")):helper.answer(r,"修改已经成功。");}catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
   helper.send(c,a,"修改 missing.txt 的代码。");assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertFalse(Files.exists(w.resolve("missing.txt")));
   helper.send(c,a,"帮我处理一下");assertEquals("FAILED",c.loop().snapshot().current().turn().status());assertTrue(c.loop().snapshot().current().turn().tools().isEmpty());
  }
 }
}
