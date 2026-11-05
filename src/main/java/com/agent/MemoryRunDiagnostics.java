package com.agent;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded per-workspace extraction diagnostics. Contains no source text or model output. */
final class MemoryRunDiagnostics {
    record Entry(String sessionId,String at,String status,String reason,int ruleCandidates,int semanticCandidates,
                 int effective,int pending,int rejected,String errorCategory,Map<String,Integer> rejectionReasons,String failureCode) {
        Entry {
            failureCode = failureCode == null || failureCode.isBlank() ? "NONE" : failureCode;
            rejectionReasons = rejectionReasons == null || rejectionReasons.isEmpty()
                    ? (rejected == 0 ? Map.of() : Map.of("UNCLASSIFIED", rejected))
                    : Collections.unmodifiableMap(new TreeMap<>(rejectionReasons));
            if (rejectionReasons.values().stream().anyMatch(n -> n == null || n < 1)
                    || rejectionReasons.values().stream().mapToInt(Integer::intValue).sum() != rejected)
                throw new IllegalArgumentException("diagnostic rejection counts do not match");
        }
        Entry(String sessionId,String at,String status,String reason,int ruleCandidates,int semanticCandidates,
              int effective,int pending,int rejected,String errorCategory) {
            this(sessionId,at,status,reason,ruleCandidates,semanticCandidates,effective,pending,rejected,errorCategory,Map.of(),"NONE");
        }
        Entry(String sessionId,String at,String status,String reason,int ruleCandidates,int semanticCandidates,
              int effective,int pending,int rejected,String errorCategory,Map<String,Integer> rejectionReasons) {
            this(sessionId,at,status,reason,ruleCandidates,semanticCandidates,effective,pending,rejected,errorCategory,rejectionReasons,"NONE");
        }
    }
    record State(int version,List<Entry> entries) { State { if(version!=1||entries==null||entries.size()>40)throw new IllegalArgumentException();entries=List.copyOf(entries); } }
    private final Path file; private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    MemoryRunDiagnostics(Path workspace){file=workspace.resolve(".agent-cli/memory/automatic-memory-diagnostics.json");}
    private static final Object LOCK=new Object();
    void record(Entry entry)throws IOException{synchronized(LOCK){
        Path file=this.file;ObjectMapper json=this.json;
        Files.createDirectories(file.getParent()); if(Files.isSymbolicLink(file))throw new IOException("Unsafe diagnostic path");
        List<Entry> entries=new ArrayList<>();
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)throw new IOException("Invalid diagnostic file");
            try{State state=json.readValue(Files.readAllBytes(file),State.class);entries.addAll(state.entries());}catch(RuntimeException bad){throw new IOException("Invalid diagnostic file");}
        }
        entries.add(entry);if(entries.size()>40)entries=entries.subList(entries.size()-40,entries.size());
        Path temp=Files.createTempFile(file.getParent(),"memory-diagnostic-",".tmp");
        try{Files.write(temp,json.writeValueAsBytes(new State(1,entries)));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }}
    Entry latest()throws IOException{synchronized(LOCK){
        Path file=this.file;ObjectMapper json=this.json;
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)throw new IOException("Invalid diagnostic file");
        try{State s=json.readValue(Files.readAllBytes(file),State.class);return s.entries().isEmpty()?null:s.entries().get(s.entries().size()-1);}catch(RuntimeException bad){throw new IOException("Invalid diagnostic file");}
    }}
}
