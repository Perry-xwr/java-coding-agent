package com.agent.memory;

import java.io.IOException;

/** Explicit offline policy: never consult environment-configured HTTP embedding services. */
public final class OfflineEmbeddingProvider implements EmbeddingProvider {
    public String identity(){return "offline-unavailable";}
    public int dimensions(){return 3;}
    public double[] embed(String text)throws IOException{throw new IOException("Embedding disabled in offline TUI; use keyword fallback");}
}
