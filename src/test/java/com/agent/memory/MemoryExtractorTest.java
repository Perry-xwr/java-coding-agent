package com.agent.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractorTest {
    @Test void conditionHeadersAreNotIndependentMemories() {
        for (String text : List.of("长期沟通偏好：讨论复杂问题时", "长期偏好：讨论复杂问题时，请先给结论，再说明理由和步骤。只讨论，不修改文件。")) {
            var proposals = new DeterministicMemoryExtractor().extract(List.of(new MemoryExtractor.Source("CODE:2", text)));
            assertTrue(proposals.stream().noneMatch(p -> p.proposedContent().endsWith("时")));
        }
    }
    @Test void semanticParserRejectsIncompleteSiblingAndNormalizesCompleteCommunication() throws Exception {
        String full = "以后讨论复杂问题时，请先给结论，再说明理由和步骤";
        var sources = List.of(new MemoryExtractor.Source("CODE:2", full + "。只讨论，不修改文件。"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var proposals = List.of(
            new MemoryExtractor.Proposal(full, "PROJECT_CONSTRAINT", List.of("CODE:2"), full, .99, "PROPOSE"),
            new MemoryExtractor.Proposal("以后讨论复杂问题时", "PREFERENCE", List.of("CODE:2"), "以后讨论复杂问题时", .99, "PROPOSE"));
        var extractor = new LlmMemoryExtractor(m -> new com.agent.llm.LLMResponse(json.writeValueAsString(proposals), List.of()));
        var batch = extractor.extractBatch(sources);
        assertEquals(1, batch.rejected()); assertEquals(java.util.Map.of("INCOMPLETE_CANDIDATE",1),batch.rejectionReasons()); assertEquals(1, batch.proposals().size());
        assertEquals(full, batch.proposals().get(0).proposedContent());
        assertEquals("PREFERENCE", batch.proposals().get(0).category());
        var malformed=assertThrows(LlmMemoryExtractor.ExtractionFailure.class, () -> new LlmMemoryExtractor(m -> new com.agent.llm.LLMResponse("{broken", List.of())).extractBatch(sources));
        assertEquals("PARSER_ERROR",malformed.reasonCode());
    }
    @Test void semanticParserAssignsStableReasonsPerRejectedCandidate() throws Exception {
        String sourceText="长期沟通偏好：讨论复杂问题时，请先给结论，再说明理由和步骤。";
        var source=new MemoryExtractor.Source("CODE:2",sourceText);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.function.Function<java.util.List<java.util.Map<String,Object>>,LlmMemoryExtractor.Batch> parse=items->{
            try{return new LlmMemoryExtractor(m->new com.agent.llm.LLMResponse(mapper.writeValueAsString(items),List.of())).extractBatch(List.of(source));}
            catch(Exception e){throw new RuntimeException(e);}
        };
        var missing=new java.util.LinkedHashMap<>(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source));missing.remove("evidenceQuote");
        var wrongType=new java.util.LinkedHashMap<>(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source));wrongType.put("category","NOT_A_MEMORY_TYPE");
        var wrongSource=new java.util.LinkedHashMap<>(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source));wrongSource.put("sourceMessageRefs",List.of("CODE:999"));
        var wrongEvidence=new java.util.LinkedHashMap<>(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source));wrongEvidence.put("evidenceQuote","完全不在原文中的证据");
        var badQuality=new java.util.LinkedHashMap<>(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source));badQuality.put("confidence",2.0);
        var incomplete=semanticItem("讨论复杂问题时",source);
        var mixedA=parse.apply(List.of(semanticItem("讨论复杂问题时，请先给结论，再说明理由和步骤",source),missing,wrongType));
        var mixedB=parse.apply(List.of(wrongSource,wrongEvidence,incomplete));
        var mixedC=parse.apply(List.of(badQuality));
        var reasons=new java.util.TreeMap<String,Integer>();mixedA.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));mixedB.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
        mixedC.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
        int rejected=mixedA.rejected()+mixedB.rejected()+mixedC.rejected();
        assertEquals(6,rejected);assertEquals(1,mixedA.proposals().size()+mixedB.proposals().size()+mixedC.proposals().size());
        assertEquals(java.util.Map.of("INVALID_SCHEMA",1,"INVALID_TYPE",1,"SOURCE_REF_MISMATCH",1,
                "EVIDENCE_MISMATCH",1,"INCOMPLETE_CANDIDATE",1,"QUALITY_REJECTED",1),reasons);
        assertEquals(rejected,reasons.values().stream().mapToInt(Integer::intValue).sum());
    }
    private static java.util.Map<String,Object> semanticItem(String content,MemoryExtractor.Source source){
        return new java.util.LinkedHashMap<>(java.util.Map.of("proposedContent",content,"category","PREFERENCE",
                "sourceMessageRefs",List.of(source.messageRef()),"evidenceQuote",source.userText(),"confidence",.99,"proposedAction","PROPOSE"));
    }
    @Test void explicitUserConstraintIsGroundedWithoutApi() {
        var sources = List.of(new MemoryExtractor.Source("CODE:1", "这个项目使用 Java 17，后续新增功能尽量补充 JUnit 测试。"));
        var proposals = new DeterministicMemoryExtractor().extract(sources);
        assertEquals(2, proposals.size());
        proposals.forEach(p -> MemoryExtractor.validate(p, sources));
    }
    @Test void questionsAndCredentialStatementsAreExcluded() {
        var extractor = new DeterministicMemoryExtractor();
        assertTrue(extractor.extract(List.of(new MemoryExtractor.Source("1", "项目使用什么 Java？"))).isEmpty());
        assertTrue(extractor.extract(List.of(new MemoryExtractor.Source("1", "项目使用 API_KEY=fictional"))).isEmpty());
    }
    @Test void fabricatedSourceAndUnsupportedParaphraseAreRejected() {
        var source = List.of(new MemoryExtractor.Source("1", "项目使用 Java 17"));
        assertThrows(IllegalArgumentException.class, () -> MemoryExtractor.validate(new MemoryExtractor.Proposal(
                "项目使用 Java 21", "EXPLICIT_FACT", List.of("1"), "项目使用 Java 17", .9, "PROPOSE"), source));
        assertThrows(IllegalArgumentException.class, () -> MemoryExtractor.validate(new MemoryExtractor.Proposal(
                "项目使用 Java 17", "EXPLICIT_FACT", List.of("other"), "项目使用 Java 17", .9, "PROPOSE"), source));
    }
}
