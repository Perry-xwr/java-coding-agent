package com.agent.memory;
import java.io.IOException;
import java.util.Map;
/** Explicit test fixture vectors, not a semantic model or production fallback. */
public final class FakeEmbeddingProvider implements EmbeddingProvider {
    private final Map<String,double[]> vectors;
    private final int dimensions;
    public FakeEmbeddingProvider(Map<String,double[]> vectors,int dimensions){this.vectors=Map.copyOf(vectors);this.dimensions=dimensions;}
    public String identity(){return "fake:test-v1";}
    public int dimensions(){return dimensions;}
    public double[] embed(String text)throws IOException{if(!vectors.containsKey(text))throw new IOException("Fake vector missing");return vectors.get(text).clone();}
}
