package com.agent;
import com.agent.llm.ProviderTokenUsage;
import com.agent.memory.SessionSummary;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Extraction cost metadata only; shared.json remains the sole fact library. Reserve before HTTP. */
final class SemanticMemoryLedger {
 record Attempt(String id,String sessionId,String sourceHash,String createdAt,String status,ProviderTokenUsage usage){}
 record State(int version,List<Attempt> attempts){State{if(version!=1||attempts==null||attempts.size()>256)throw new IllegalArgumentException();attempts=List.copyOf(attempts);}}
 record ReservationResult(String reservationId,String skipReason){boolean granted(){return reservationId!=null;}}
 private final Path file;private final CliSessionStore lock;private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
 SemanticMemoryLedger(Path workspace){file=workspace.resolve(".agent-cli/memory/semantic-extraction.json");lock=CliSessionStore.memoryDocuments(workspace,Files::write);}
 private State read()throws IOException{
  if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new State(1,List.of());
  if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>131072)throw new IOException("Unsafe semantic ledger");
  try{var state=json.readValue(Files.readAllBytes(file),State.class);if(state==null)throw new IOException("Null extraction ledger");for(var a:state.attempts()){UUID.fromString(a.id());UUID.fromString(a.sessionId());Instant.parse(a.createdAt());if(a.sourceHash()==null||!a.sourceHash().matches("[a-f0-9]{64}")||!Set.of("RESERVED","SUCCEEDED","FAILED","DISCARDED").contains(a.status())||a.usage()==null)throw new IllegalArgumentException();}return state;}catch(RuntimeException bad){throw new IOException("Invalid semantic ledger");}
 }
 private void write(State state)throws IOException{Path temp=Files.createTempFile(file.getParent(),"semantic-",".tmp");try{Files.write(temp,json.writeValueAsBytes(state));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
 String reserve(String session,String source)throws IOException{return reserveDetailed(session,source).reservationId();}
 ReservationResult reserveDetailed(String session,String source)throws IOException{return lock.withMemoryLock(()->{
  var state=read();var entries=state.attempts();String hash=SessionSummary.digest(AutomaticMemory.canonical(source).replaceAll("[。.,，；;]",""));Instant now=Instant.now();
  if(entries.stream().anyMatch(a->a.sourceHash().equals(hash)))return new ReservationResult(null,"DUPLICATE_SOURCE");
  if(entries.size()>=256)return new ReservationResult(null,"LEDGER_CAPACITY_REACHED");
  if(entries.stream().filter(a->a.sessionId().equals(session)).count()>=3)return new ReservationResult(null,"SESSION_BUDGET_EXHAUSTED");
  long cooldown=Math.max(0,Long.getLong("agent.memory.semantic.cooldownMillis",120000L));
  if(!entries.isEmpty()&&Duration.between(Instant.parse(entries.get(entries.size()-1).createdAt()),now).toMillis()<cooldown)return new ReservationResult(null,"COOLDOWN_ACTIVE");
  var next=new ArrayList<>(entries);String id=UUID.randomUUID().toString();next.add(new Attempt(id,session,hash,now.toString(),"RESERVED",ProviderTokenUsage.unknown()));write(new State(1,next));return new ReservationResult(id,null);
 });}
 void finish(String id,String status,ProviderTokenUsage usage)throws IOException{lock.withMemoryLock(()->{var state=read();var next=new ArrayList<Attempt>();boolean found=false;for(var a:state.attempts()){if(a.id().equals(id)){if(!a.status().equals("RESERVED"))throw new IOException("Extraction reservation consumed");next.add(new Attempt(a.id(),a.sessionId(),a.sourceHash(),a.createdAt(),status,usage));found=true;}else next.add(a);}if(!found)throw new IOException("Unknown extraction reservation");write(new State(1,next));return null;});}
 String status()throws IOException{return status(null);}
 String status(String sessionId)throws IOException{return lock.withMemoryLock(()->{var state=read();var entries=state.attempts();var complete=entries.stream().map(Attempt::usage).filter(u->u.usageAvailable()&&u.inputTokens()!=null&&u.outputTokens()!=null&&u.totalTokens()!=null).toList();
  long input=complete.stream().mapToLong(ProviderTokenUsage::inputTokens).sum(),output=complete.stream().mapToLong(ProviderTokenUsage::outputTokens).sum(),total=complete.stream().mapToLong(ProviderTokenUsage::totalTokens).sum();
  Instant now=Instant.now();String day=now.toString().substring(0,10);long daily=entries.stream().filter(a->a.createdAt().startsWith(day)).count();long perSession=sessionId==null?0:entries.stream().filter(a->a.sessionId().equals(sessionId)).count();long cooldown=Math.max(0,Long.getLong("agent.memory.semantic.cooldownMillis",120000L));long remaining=0;if(cooldown>0&&!entries.isEmpty())remaining=Math.max(0,cooldown-Duration.between(Instant.parse(entries.get(entries.size()-1).createdAt()),now).toMillis());
  return "semantic reserved requests="+entries.size()+" (including failed/discarded/in-flight); todayUsed="+daily+" (no daily cap); currentSessionUsed="+(sessionId==null?"UNKNOWN":perSession+"/3")+"; cooldownRemainingMs="+remaining+"; complete PROVIDER_REPORTED records="+complete.size()+", missing/partial Usage UNKNOWN="+(entries.size()-complete.size())+"; reported subtotals input="+input+", output="+output+", total="+total+" (not a total for UNKNOWN records); no retries; currency UNKNOWN";});}
}
