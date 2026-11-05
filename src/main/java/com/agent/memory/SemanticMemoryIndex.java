package com.agent.memory;
import java.util.*;
/** Derived, process-local cache. All candidates come from a fresh authoritative library read. */
public final class SemanticMemoryIndex {
    public record Entry(int version,String memoryId,String contentHash,String provider,int dimensions,double[] vector){}
    public record Metrics(int calls,int hits,int failures,long latencyMillis,String usage){}
    private final Map<String,Entry> cache=new HashMap<>();
    private int calls,hits,failures,inputChars;private long millis;
    private String usage="UNKNOWN";
    public synchronized Metrics metrics(){return new Metrics(calls,hits,failures,millis,usage);}
    private double[] vector(String id,String text,EmbeddingProvider provider)throws java.io.IOException{
        String hash=SessionSummary.digest(text);Entry old=cache.get(id);
        if(old!=null&&old.contentHash().equals(hash)&&old.provider().equals(provider.identity())&&old.dimensions()==provider.dimensions()){
            hits++;return EmbeddingProvider.normalized(old.vector(),provider.dimensions());
        }
        if(calls>=64||text.length()>2048||inputChars+text.length()>32768)throw new java.io.IOException("Embedding budget exhausted");
        calls++;inputChars+=text.length();long start=System.nanoTime();
        try{
            usage="UNKNOWN";
            double[] result=EmbeddingProvider.normalized(provider.embed(text),provider.dimensions());
            usage=provider.reportedUsage();
            cache.put(id,new Entry(1,id,hash,provider.identity(),provider.dimensions(),result));return result;
        }finally{millis+=(System.nanoTime()-start)/1_000_000;}
    }
    public synchronized List<SharedMemoryHit> rank(List<LongTermMemory> authorized,String query,EmbeddingProvider provider,double threshold){
        if(provider==null)throw new IllegalStateException("Embedding disabled");
        try{
            Set<String> live=new HashSet<>();authorized.forEach(m->live.add(m.memoryId()));cache.keySet().removeIf(id->!id.startsWith("QUERY:")&&!live.contains(id));
            double[] q=vector("QUERY:"+SessionSummary.digest(query),query,provider);
            List<SharedMemoryHit> hits=new ArrayList<>();
            for(var memory:authorized){double[] v=vector(memory.memoryId(),memory.content(),provider);double score=0;for(int i=0;i<q.length;i++)score+=q[i]*v[i];score=Math.min(1,score);if(score>0&&score>=threshold)hits.add(new SharedMemoryHit(memory,score));}
            hits.sort(Comparator.comparingDouble(SharedMemoryHit::score).reversed().thenComparing(h->h.memory().memoryId()));
            return List.copyOf(hits.subList(0,Math.min(3,hits.size())));
        }catch(Exception failure){failures++;throw new IllegalStateException("Embedding unavailable; keyword fallback",failure);}
    }
}
