package com.agent.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Set;

/** Dedicated FINAL-API ledger, never a D2 ledger or a resettable per-session budget. */
public final class FinalApiBudget {
    public static final int LIMIT=4;
    public record State(int version,int attempts,String status){}
    public record Preview(Path root,State state,String identity){
        public boolean newLedger(){return state==null;}
        public String display(){return newLedger()?"NEW_LEDGER; expected initial usage 0/4 (not yet created)":"EXISTING_LEDGER; API attempts used: "+state.attempts()+"/4; status="+state.status();}
        public void requireAvailable()throws IOException{
            if(state!=null&&(state.attempts()>=LIMIT||!Set.of("PREPARED","RUNNING").contains(state.status())))throw new IOException("FINAL_API_BUDGET_STOPPED");
        }
    }
    public static final class ConfirmationChanged extends IOException {public ConfirmationChanged(){super("FINAL_API_LEDGER_CHANGED_SINCE_CONFIRMATION");}}
    private Preview firstSendExpectation;
    private final Path root;private final boolean authorized;private final ObjectMapper json=new ObjectMapper();
    public FinalApiBudget(Path directory,boolean authorized)throws IOException{this(directory,authorized,false);}
    private FinalApiBudget(Path directory,boolean authorized,boolean requireNew)throws IOException{
        root=directory.toAbsolutePath().normalize();this.authorized=authorized;
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("UNSAFE_LEDGER");
        if(requireNew){Files.createDirectories(root.getParent());Files.createDirectory(root);}else Files.createDirectories(root);
        locked(()->{Path owner=root.resolve("final-api.owner"),file=root.resolve("attempts.json");
            if(requireNew&&Files.exists(owner,LinkOption.NOFOLLOW_LINKS))throw new ConfirmationChanged();
            if(!Files.exists(owner,LinkOption.NOFOLLOW_LINKS)){
                if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("UNOWNED_LEDGER");
                Files.writeString(owner,"FINAL_API_V1",StandardOpenOption.CREATE_NEW);write(new State(1,0,"PREPARED"));
            }else{
                if(Files.isSymbolicLink(owner)||!Files.readString(owner).equals("FINAL_API_V1"))throw new IOException("INVALID_OWNER");read();
            }return null;});
    }
    private interface Action<T>{T run()throws IOException;}
    private synchronized <T>T locked(Action<T> action)throws IOException{
        try(var channel=FileChannel.open(root.resolve("attempts.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            try(var lock=channel.tryLock()){if(lock==null)throw new IOException("LEDGER_BUSY");return action.run();}
            catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("LEDGER_BUSY");}
        }
    }
    private State read()throws IOException{return readState(root);}
    private static State readState(Path root)throws IOException{
        Path file=root.resolve("attempts.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("INVALID_LEDGER");
        State s=new ObjectMapper().readValue(Files.readAllBytes(file),State.class);
        if(s==null||s.version()!=1||s.attempts()<0||s.attempts()>LIMIT||s.status()==null||!Set.of("PREPARED","RUNNING","FAILED","COMPLETED").contains(s.status()))throw new IOException("INVALID_LEDGER");return s;
    }
    /** No mkdir, owner creation, lock creation or state writes before native confirmation. */
    public static Preview inspect(Path path)throws IOException{
        Path root=path.toAbsolutePath().normalize();
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("UNSAFE_LEDGER");
        java.nio.file.attribute.BasicFileAttributes rootAttributes;
        try{rootAttributes=Files.readAttributes(root,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException missing){return new Preview(root,null,"ABSENT");}
        if(!rootAttributes.isDirectory()||rootAttributes.isSymbolicLink())throw new IOException("INVALID_LEDGER_ROOT");
        Path lock=root.resolve("attempts.lock");
        if(!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS))throw new IOException("INVALID_LEDGER_LOCK");
        try(var channel=FileChannel.open(lock,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            try(var held=channel.tryLock(0,Long.MAX_VALUE,true)){
                if(held==null)throw new IOException("LEDGER_BUSY");return inspectLocked(root);
            }catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("LEDGER_BUSY");}
        }
    }
    private static Preview inspectLocked(Path root)throws IOException{
        Path owner=root.resolve("final-api.owner");
        if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.size(owner)>64||!Files.readString(owner).equals("FINAL_API_V1"))throw new IOException("INVALID_OWNER");
        State state=readState(root);
        StringBuilder identity=new StringBuilder(root.toString());
        for(Path file:java.util.List.of(owner,root.resolve("attempts.json"))){
            var attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            identity.append('|').append(attributes.fileKey()).append('|').append(attributes.lastModifiedTime()).append('|')
                    .append(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
        return new Preview(root,state,identity.toString());
    }
    /** Compare after user confirmation; initialization still uses the original locked atomic constructor. */
    public static FinalApiBudget openConfirmed(Preview confirmed,boolean authorized)throws IOException{
        if(!authorized)throw new IOException("FINAL_API_NOT_AUTHORIZED");
        confirmed.requireAvailable();if(!confirmed.equals(inspect(confirmed.root())))throw new ConfirmationChanged();
        FinalApiBudget budget=new FinalApiBudget(confirmed.root(),true,confirmed.newLedger());
        Preview current=inspect(confirmed.root());
        if(confirmed.newLedger()){
            if(!current.state().equals(new State(1,0,"PREPARED")))throw new ConfirmationChanged();
        }else if(!confirmed.equals(current))throw new ConfirmationChanged();
        budget.firstSendExpectation=current;return budget;
    }
    private void write(State s)throws IOException{
        Path temp=Files.createTempFile(root,"attempt-",".tmp");
        try{Files.write(temp,json.writeValueAsBytes(s));Files.move(temp,root.resolve("attempts.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temp);}
    }
    public State state()throws IOException{return locked(this::read);}
    public boolean authorized(){return authorized;}
    public void reserve()throws IOException{
        if(!authorized)throw new IOException("FINAL_API_NOT_AUTHORIZED");
        if(Thread.currentThread().isInterrupted())throw new IOException("FINAL_API_CANCELLED");
        locked(()->{var s=read();if(firstSendExpectation!=null&&!firstSendExpectation.equals(inspectLocked(root)))throw new ConfirmationChanged();
            if(s.attempts()>=LIMIT||!Set.of("PREPARED","RUNNING").contains(s.status()))throw new IOException("FINAL_API_BUDGET_EXHAUSTED_OR_STOPPED");
            write(new State(1,s.attempts()+1,"RUNNING"));firstSendExpectation=null;return null;});
    }
    public void finish(boolean success)throws IOException{locked(()->{var s=read();write(new State(1,s.attempts(),success?"COMPLETED":"FAILED"));return null;});}
}
