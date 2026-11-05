package com.agent.memory;
import java.io.IOException;
public interface EmbeddingProvider {
    String identity();
    int dimensions();
    double[] embed(String text) throws IOException;
    default String reportedUsage(){return "UNKNOWN";}
    static double[] normalized(double[] vector,int dimensions) {
        if(vector==null || dimensions<1 || dimensions>4096 || vector.length!=dimensions) throw new IllegalArgumentException("Invalid embedding dimensions");
        double norm=0;
        for(double x:vector){if(!Double.isFinite(x))throw new IllegalArgumentException("Non-finite embedding");norm=Math.hypot(norm,x);}
        if(!Double.isFinite(norm)||norm==0)throw new IllegalArgumentException("Zero/invalid embedding");
        double[] result=vector.clone();for(int i=0;i<result.length;i++)result[i]/=norm;return result;
    }
}
