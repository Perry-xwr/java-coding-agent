package com.agent;
import com.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TechnicalAnchorRetrievalTest {
 @TempDir Path workspace;
 @Test void exactIdentifiersSurviveVerboseQuestionsWithoutPrefixMatching()throws Exception{
  var memory=new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->true,workspace);
  String session=UUID.randomUUID().toString();var expected=memory.rememberAuthorized(session,"组件使用 GraphBuilder");
  memory.rememberAuthorized(session,"另一个组件使用 GraphBuilderExtra");
  var results=memory.retrieveAuthorized(session,"为了整理完整的课程报告，请详细说明 GraphBuilder 的使用约定和相关背景，以及其他应该注意的设计原则");
  assertEquals(List.of(expected.memoryId()),results.stream().map(h->h.memory().memoryId()).toList());
  assertTrue(memory.retrieveAuthorized(session,"如何种植兰花和正确浇水").isEmpty());
 }

 @Test void relevantMemorySurvivesVerboseNaturalLanguageQueryWithoutAnEmbeddingProvider()throws Exception{
  var memory=new LocalCrossSessionMemory(new SharedMemoryStore(workspace),()->true,workspace);
  String session=UUID.randomUUID().toString();
  var expected=memory.rememberAuthorized(session,"性能优化之前先通过实际基准测试定位瓶颈");
  memory.rememberAuthorized(session,"学习新技术时先给最小可运行示例");
  var results=memory.retrieveAuthorized(session,"请结合我们这个工程目前的整体研发流程和维护约定，回顾此前已经确认的做法，并说明性能优化之前通过实际测试定位瓶颈这一长期要求具体要先做什么？");
  assertEquals(expected.memoryId(),results.get(0).memory().memoryId());
 }
}
