package com.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists only the active conversation. Agent instances/tools remain owned by CliSessions. */
public final class CliConversationManager {
    private final CliSessionStore store;
    private final SharedMemoryStore sharedStore;
    private final Path workspace;
    private CliConversation active;
    private final com.agent.memory.SemanticMemoryIndex vectorIndex=new com.agent.memory.SemanticMemoryIndex();
    private com.agent.memory.EmbeddingProvider embedding;
    public com.agent.memory.SemanticMemoryIndex.Metrics embeddingMetrics(){return vectorIndex.metrics();}
    public void embeddingProvider(com.agent.memory.EmbeddingProvider provider){embedding=provider;}
    public CrossSessionMemory crossSessionMemory(java.util.function.BooleanSupplier consent,String mode){
        if(embedding==null && !mode.equals("keyword"))embedding=com.agent.memory.HttpEmbeddingProvider.configured();
        return new com.agent.memory.LocalCrossSessionMemory(sharedStore,consent,workspace).retrieval(mode,embedding,vectorIndex);
    }

    public CliConversationManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        store = CliSessionStore.conversations(workspace);
        sharedStore = new SharedMemoryStore(workspace);
    }
    public CliConversation active() { return active; }
    public CrossSessionMemory crossSessionMemory() {
        return crossSessionMemory(() -> active != null && active.snapshot().crossSessionMemory().enabled(),
                active == null ? "keyword" : active.snapshot().crossSessionMemory().retrievalMode());
    }
    public CrossSessionMemory crossSessionMemory(java.util.function.BooleanSupplier consent) {
        return new com.agent.memory.LocalCrossSessionMemory(sharedStore, consent, workspace);
    }
    public List<CliConversation> list() throws IOException { return store.listConversations(); }
    public List<CliConversationHeader> headers() throws IOException { return store.conversationHeaders(); }
    public CliConversation load(String id) throws IOException { return store.loadConversation(id); }

    public CliConversation create(CliSessionSnapshot snapshot) throws IOException {
        return create(snapshot, UUID.randomUUID().toString());
    }

    public CliConversation create(CliSessionSnapshot snapshot, String id) throws IOException {
        String now = Instant.now().toString();
        CliConversation candidate = new CliConversation(1, id, "New session",
                now, now, snapshot);
        store.writeConversation(candidate, false);
        active = candidate;
        return candidate;
    }

    public void checkpoint(CliSessionSnapshot snapshot) throws IOException {
        if (active == null) { create(snapshot); return; }
        CliConversation candidate = new CliConversation(1, active.id(), active.title(), active.createdAt(),
                Instant.now().toString(), snapshot);
        store.writeConversationExpected(candidate, active);
        active = candidate;
    }

    public void activate(CliConversation conversation) { active = conversation; }
    public void detach() { active = null; }

    public void rename(String id, String title) throws IOException {
        CliConversation.validateTitle(title);
        CliConversation previous = store.loadConversation(id);
        CliConversation renamed = new CliConversation(1, previous.id(), title, previous.createdAt(),
                Instant.now().toString(), previous.snapshot());
        store.writeConversationExpected(renamed, previous);
        if (active != null && active.id().equals(id)) active = renamed;
    }
}
