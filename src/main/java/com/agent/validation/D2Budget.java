package com.agent.validation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
/** One-shot experiment ledger. Never auto-reinitializes a used or damaged run. */
public final class D2Budget {
 public record State(int version,int calls,int attempts,int exchanges,String status){}
 private final Path root;private final boolean authorized;private final ObjectMapper json=new ObjectMapper();
 public D2Budget(Path root,boolean authorized)throws IOException{
  this.root=root.toAbsolutePath().normalize();this.authorized=authorized;
  Path cursor=this.root;while(cursor!=null){if(Files.isSymbolicLink(cursor))throw new IOException("Unsafe D2 path");cursor=cursor.getParent();}
  Files.createDirectories(this.root);
  locked(()->{
   Path marker=this.root.resolve("owner.marker"),file=this.root.resolve("budget.json");
   if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
    if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unowned ledger");
    Files.writeString(marker,"D2_V1",StandardOpenOption.CREATE_NEW);write(new State(1,0,0,0,"PREPARED"));
   }else read();return null;
  });
 }
 private interface Action<T>{T run()throws IOException;}
 private <T>T locked(Action<T> action)throws IOException{
  Path lock=root.resolve("budget.lock");if(Files.isSymbolicLink(lock))throw new IOException("Unsafe ledger lock");
  try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
   try(var held=channel.tryLock()){if(held==null)throw new IOException("D2 busy");return action.run();}
   catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("D2 busy");}
  }
 }
 private State read()throws IOException{
  Path file=root.resolve("budget.json");if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Invalid D2 ledger");
  State s=json.readValue(Files.readAllBytes(file),State.class);
  if(s.version()!=1||s.calls()<0||s.attempts()!=s.calls()||s.calls()>3||s.exchanges()<0||s.exchanges()>s.attempts()||!java.util.Set.of("PREPARED","RUNNING","COMPLETED","FAILED").contains(s.status()))throw new IOException("Invalid D2 state");return s;
 }
 private void write(State s)throws IOException{
  Path tmp=Files.createTempFile(root,".budget-",".tmp");try{Files.write(tmp,json.writeValueAsBytes(s));Files.move(tmp,root.resolve("budget.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
 }
 public State state()throws IOException{return locked(this::read);}
 public void assertFresh()throws IOException{var s=state();if(!s.status().equals("PREPARED")||s.calls()!=0)throw new IOException("Used/failed D2 run: no restart or replay permitted");}
 public void reserve()throws IOException{
  if(!authorized)throw new IOException("REAL_API_NOT_AUTHORIZED");
  locked(()->{var s=read();if((!s.status().equals("PREPARED")&&!s.status().equals("RUNNING"))||s.calls()>=3)throw new IOException("D2_BUDGET_EXCEEDED_OR_STOPPED");write(new State(1,s.calls()+1,s.attempts()+1,s.exchanges(),"RUNNING"));return null;});
 }
 public void exchange()throws IOException{locked(()->{var s=read();if(!s.status().equals("RUNNING")||s.exchanges()>=s.attempts())throw new IOException("Unexpected HTTP follow-up blocked");write(new State(1,s.calls(),s.attempts(),s.exchanges()+1,s.status()));return null;});}
 public void stop(boolean success)throws IOException{locked(()->{var s=read();write(new State(1,s.calls(),s.attempts(),s.exchanges(),success?"COMPLETED":"FAILED"));return null;});}
}
