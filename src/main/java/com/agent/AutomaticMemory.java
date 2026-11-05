package com.agent;
import com.agent.memory.*;
import java.util.*;
/** Successful-turn explicit user quotes only. No LLM, tool output or history retrieval input. */
final class AutomaticMemory {
 static String canonical(String text){return text.replaceFirst("^(以后|今后|后续|从现在起)","").replaceFirst("^(这个|本|该)项目","项目").replaceFirst("^项目统一使用","项目使用").replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
 static boolean lowRisk(String text){return MemoryQualityPolicy.lowRisk(text)||text.matches("(?i)(?:(?:以后|今后|后续|从现在起)\\s*)?(?:(?:这个|本|该)?项目(?:统一)?使用\\s*(?:Java|JDK)\\s*\\d+|(?:这个|本|该)?项目(?:统一)?使用\\s*Maven|我偏好(?:中文|英文|简洁|详细)(?:回答|回复)?|后续新增功能(?:尽量|必须)?补充\\s*JUnit\\s*测试)");}
 static MemoryExtractor.Source source(CliSessionSnapshot s){var mode=profile(s);var h=s.histories().get(mode);int index=h.size()-1;while(index>=1&&!"user".equals(h.get(index).role()))index--;return index<1?null:new MemoryExtractor.Source(mode+":"+index,h.get(index).content());}
 static CliSessionSnapshot semantic(java.nio.file.Path workspace,String id,CliSessionSnapshot s,MemoryConsentStore.Settings policy,MemoryExtractor.Source source,List<MemoryExtractor.Proposal> proposals){
  var ref=source.messageRef().split(":");var mode=CliMode.valueOf(ref[0]);int index=Integer.parseInt(ref[1]);
  if(!MemoryQualityPolicy.safeSource(source.userText())||!SessionSummary.sourceMatches(s.histories().get(mode),index,SessionSummary.digest(source.userText())))throw new IllegalArgumentException("Semantic source changed");
  var candidates=new ArrayList<>(s.extraction().candidates());var store=new SharedMemoryStore(workspace);
  for(var p:proposals){
   // Keep a second deterministic boundary at persistence time. The extractor
   // already validates each model item, but only exact spans in an eligible
   // user-authored segment may be considered for shared storage.
   try{MemoryExtractor.validate(p,List.of(source));}catch(IllegalArgumentException invalid){continue;}
   if(!MemoryQualityPolicy.evidenceAllowed(p.evidenceQuote(),source.userText()))continue;
   if(!MemoryQualityPolicy.completeCandidate(p.proposedContent()))continue;
   if(candidates.size()>=100)break;
   if(candidates.stream().anyMatch(c->canonical(c.candidateContent()).equals(canonical(p.proposedContent()))))continue;
   String candidateId=UUID.randomUUID().toString(),status="PENDING";
   boolean grounded=MemoryQualityPolicy.groundedDurableCandidate(p.proposedContent(),p.evidenceQuote(),source.userText());
   boolean semanticPreference="PREFERENCE".equals(p.category())&&MemoryQualityPolicy.semanticPreferenceAutoEligible(p.proposedContent(),p.evidenceQuote(),source.userText());
   if(p.confidence()>=0.95&&grounded&&(lowRisk(p.proposedContent())||semanticPreference))try{String now=java.time.Instant.now().toString();store.applySemantic(new LongTermMemory(UUID.randomUUID().toString(),p.proposedContent(),id,now,now,"SEMANTIC_EXTRACTED_USER_QUOTE","EXPLICIT_WORKSPACE_AUTO_POLICY",workspace.toAbsolutePath().normalize().toString(),source.messageRef()+":"+SessionSummary.digest(source.userText())+":policy="+policy.revision()),source.userText(),candidateId,p.category(),p.evidenceQuote());status="APPROVED";}catch(java.io.IOException review){/* pending, never overwrite uncertain facts */}
   candidates.add(new ExtractionState.Candidate(candidateId,p.proposedContent(),p.category(),id,p.sourceMessageRefs(),p.evidenceQuote(),source.userText(),java.time.Instant.now().toString(),status));
  }
  return snapshot(s,new ExtractionState(s.extraction().autoEnabled(),s.extraction().llmEnabled(),s.extraction().attempts(),s.extraction().cursors(),candidates,s.extraction().usage()));
 }
 static CliSessionSnapshot reserve(CliSessionSnapshot s){var mode=profile(s);var h=s.histories().get(mode);int index=h.size()-1;while(index>=1&&!"user".equals(h.get(index).role()))index--;if(index<1||index<s.extraction().cursors().getOrDefault(mode,1))return s;var next=s.extraction().reserve(mode,index+1,false);return snapshot(s,next);}
 static CliMode profile(CliSessionSnapshot s){return s.activeMode()==CliMode.AUTO?Objects.requireNonNullElse(s.lastAutoRoutedMode(),CliMode.CODE):s.activeMode();}
 static CliSessionSnapshot extract(java.nio.file.Path workspace,String id,CliSessionSnapshot s,MemoryConsentStore.Settings policy)throws Exception{
  var mode=profile(s);var h=s.histories().get(mode);int index=h.size()-1;while(index>=1&&!"user".equals(h.get(index).role()))index--;if(index<1)return s;
  var source=new MemoryExtractor.Source(mode+":"+index,h.get(index).content());
  if(!MemoryQualityPolicy.safeSource(source.userText()))return s;
  var candidates=new ArrayList<>(s.extraction().candidates());var store=new SharedMemoryStore(workspace);
  for(var proposal:new DeterministicMemoryExtractor().extract(List.of(source))){
   MemoryExtractor.validate(proposal,List.of(source));
   if(candidates.stream().anyMatch(c->c.candidateContent().equals(proposal.proposedContent())))continue;
   if(candidates.size()>=100)break;
   String status="PENDING";
   if(lowRisk(proposal.proposedContent()))try{
    String now=java.time.Instant.now().toString();store.appendAutomaticIfSafe(new LongTermMemory(UUID.randomUUID().toString(),proposal.proposedContent(),id,now,now,"RULE_VERIFIED_USER_QUOTE","EXPLICIT_WORKSPACE_AUTO_POLICY",workspace.toAbsolutePath().normalize().toString(),source.messageRef()+":"+SessionSummary.digest(source.userText())+":policy="+policy.revision()));status="APPROVED";
   }catch(java.io.IOException review){/* Includes store failures: never claim saved or overwrite. */}
   candidates.add(new ExtractionState.Candidate(UUID.randomUUID().toString(),proposal.proposedContent(),proposal.category(),id,proposal.sourceMessageRefs(),proposal.evidenceQuote(),source.userText(),java.time.Instant.now().toString(),status));
  }
  return snapshot(s,new ExtractionState(s.extraction().autoEnabled(),s.extraction().llmEnabled(),s.extraction().attempts(),s.extraction().cursors(),candidates,s.extraction().usage()));
 }
 static CliSessionSnapshot snapshot(CliSessionSnapshot s,ExtractionState extraction){return new CliSessionSnapshot(s.version(),s.savedAt(),s.activeMode(),s.lastAutoRoutedMode(),s.histories(),s.workingContext(),s.summary(),s.crossSessionMemory(),extraction);}
}
