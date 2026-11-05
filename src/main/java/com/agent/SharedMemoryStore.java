package com.agent;

import com.agent.memory.LongTermMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Workspace-scoped authorized library, stored separately from conversations. No history scan. */
public class SharedMemoryStore {
    public enum AgentProposalResult { SAVED, DUPLICATE, PENDING }
    private final CliSessionStore documents;
    public SharedMemoryStore(Path workspace) { documents = CliSessionStore.memoryDocuments(workspace, java.nio.file.Files::write); }
    SharedMemoryStore(Path workspace, CliSessionStore.TempFileWriter writer) { documents = CliSessionStore.memoryDocuments(workspace, writer); }
    public record Library(int version, List<LongTermMemory> entries) {
        public Library {
            if (version != 1 || entries == null || entries.size() > 1000) throw new IllegalArgumentException("Invalid shared memory library");
            entries = List.copyOf(entries);
            if (entries.stream().map(LongTermMemory::memoryId).distinct().count() != entries.size()) throw new IllegalArgumentException("Duplicate memory ids");
        }
    }
    public List<LongTermMemory> read() throws IOException {
        var document = documents.readMemoryDocument();
        if (document == null) return List.of();
        Library library = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(document, Library.class);
        if (library == null) throw new IOException("Invalid shared memory library");
        return library.entries();
    }
    public void append(LongTermMemory memory) throws IOException { documents.withMemoryLock(()->{appendUnlocked(memory);return null;}); }
    public boolean appendAutomaticIfSafe(LongTermMemory memory)throws IOException{
        if(!AutomaticMemory.lowRisk(memory.content())||!"EXPLICIT_WORKSPACE_AUTO_POLICY".equals(memory.sharingAuthorization()))throw new IOException("Not eligible for automatic saving");
        return documents.withMemoryLock(()->{
            var scoped=read().stream().filter(m->m.workspaceScope().equals(memory.workspaceScope())).toList();
            if(scoped.stream().anyMatch(m->AutomaticMemory.canonical(m.content()).equals(AutomaticMemory.canonical(memory.content()))))return false;
            var relation=MemoryQualityPolicy.relation(memory.content(),scoped);
            if(relation==com.agent.memory.ConflictDetector.Relation.DUPLICATE)return false;
            if(relation!=com.agent.memory.ConflictDetector.Relation.NEW&&relation!=com.agent.memory.ConflictDetector.Relation.RELATED)throw new IOException("Automatic memory requires review");
            appendUnlocked(memory);return true;
        });
    }
    /** Narrow Agent-managed writer: exact user evidence and consent are checked before this method. */
    public AgentProposalResult appendAgentProposalIfSafe(LongTermMemory memory,String source,String category)throws IOException{
        if(!"AGENT_MANAGED_USER_QUOTE".equals(memory.sourceType())
                ||!"EXPLICIT_WORKSPACE_AUTO_POLICY".equals(memory.sharingAuthorization())
                ||!MemoryQualityPolicy.agentManagedProposalAllowed(memory.content(),category,source))
            throw new IOException("Agent memory proposal is not eligible");
        return documents.withMemoryLock(()->{
            var scoped=read().stream().filter(m->m.workspaceScope().equals(memory.workspaceScope())).toList();
            var relation=MemoryQualityPolicy.relation(memory.content(),scoped);
            if(relation==com.agent.memory.ConflictDetector.Relation.DUPLICATE)return AgentProposalResult.DUPLICATE;
            if(relation!=com.agent.memory.ConflictDetector.Relation.NEW&&relation!=com.agent.memory.ConflictDetector.Relation.RELATED)
                return AgentProposalResult.PENDING;
            appendUnlocked(memory);return AgentProposalResult.SAVED;
        });
    }
    boolean applySemantic(LongTermMemory memory,String source,String candidateId)throws IOException{
        if(!AutomaticMemory.lowRisk(memory.content())||!source.contains(memory.content()))throw new IOException("Semantic proposal not eligible");
        return applySemanticChecked(memory,source,candidateId);
    }
    boolean applySemantic(LongTermMemory memory,String source,String candidateId,String category,String evidence)throws IOException{
        boolean deterministic=AutomaticMemory.lowRisk(memory.content())&&source.contains(memory.content());
        boolean semanticPreference="PREFERENCE".equals(category)&&MemoryQualityPolicy.semanticPreferenceAutoEligible(memory.content(),evidence,source);
        if(!deterministic&&!semanticPreference)throw new IOException("Semantic proposal not eligible");
        return applySemanticChecked(memory,source,candidateId);
    }
    private boolean applySemanticChecked(LongTermMemory memory,String source,String candidateId)throws IOException{
        if(!MemoryQualityPolicy.completeCandidate(memory.content()))throw new IOException("Incomplete semantic candidate");
        return documents.withMemoryLock(()->{
            var scoped=read().stream().filter(m->m.workspaceScope().equals(memory.workspaceScope())).toList();
            var relation=MemoryQualityPolicy.relation(memory.content(),scoped);
            if(relation==com.agent.memory.ConflictDetector.Relation.DUPLICATE)return false;
            var replacements=scoped.stream().filter(m->MemoryQualityPolicy.exactRevision(source,m,memory.content())).toList();
            if(replacements.size()==1&&relation==com.agent.memory.ConflictDetector.Relation.POSSIBLE_CONFLICT){
                var old=replacements.get(0);updateUnlocked(old.memoryId(),old.workspaceScope(),memory.content(),com.agent.memory.SessionSummary.digest(old.content()+old.updatedAt()),candidateId,
                    "Explicit user revision: "+memory.sourceSessionId()+" / "+memory.sourceMessageReference());return true;
            }
            if(relation!=com.agent.memory.ConflictDetector.Relation.NEW&&relation!=com.agent.memory.ConflictDetector.Relation.RELATED)throw new IOException("Semantic conflict requires review");
            appendUnlocked(memory);return true;
        });
    }
    private void appendUnlocked(LongTermMemory memory)throws IOException {
        List<LongTermMemory> entries = new ArrayList<>(read());
        rejectDuplicate(entries, memory.workspaceScope(), memory.content(), null);
        entries.add(memory);
        documents.writeMemoryDocument(new Library(1, entries));
    }
    public LongTermMemory update(String memoryId, String scope, String text) throws IOException {
        return updateChecked(memoryId,scope,text,null,null);
    }
    public LongTermMemory updateChecked(String memoryId,String scope,String text,String expectedVersion,String candidateId)throws IOException {
        return documents.withMemoryLock(()->updateUnlocked(memoryId,scope,text,expectedVersion,candidateId));
    }
    private LongTermMemory updateUnlocked(String memoryId,String scope,String text,String expectedVersion,String candidateId)throws IOException {
        return updateUnlocked(memoryId,scope,text,expectedVersion,candidateId,candidateId==null?"Explicit user update":"User approved candidate replacement");
    }
    private LongTermMemory updateUnlocked(String memoryId,String scope,String text,String expectedVersion,String candidateId,String reason)throws IOException {
        validateId(memoryId);
        LongTermMemory.validateContent(text);
        List<LongTermMemory> entries = new ArrayList<>(read());
        int index = exactIndex(entries, memoryId, scope);
        LongTermMemory old = entries.get(index);
        if(expectedVersion!=null && !expectedVersion.equals(com.agent.memory.SessionSummary.digest(old.content()+old.updatedAt())))throw new IOException("Stale review; review again");
        if (old.content().equals(text)) return old;
        rejectDuplicate(entries, scope, text, memoryId);
        java.time.Instant updated = java.time.Instant.now();
        java.time.Instant previous = java.time.Instant.parse(old.updatedAt());
        if (previous.equals(java.time.Instant.MAX)) throw new IOException("Invalid memory update timestamp");
        if (!updated.isAfter(previous)) updated = previous.plusNanos(1);
        var revisions=new ArrayList<>(old.revisions());
        revisions.add(new LongTermMemory.Revision(old.content(),reason,candidateId,updated.toString()));
        if(revisions.size()>20)revisions.remove(0);
        LongTermMemory replacement = new LongTermMemory(old.memoryId(), text, old.sourceSessionId(),
                old.createdAt(), updated.toString(), old.sourceType(), old.sharingAuthorization(),
                old.workspaceScope(), old.sourceMessageReference(), revisions);
        entries.set(index, replacement);
        documents.writeMemoryDocument(new Library(1, entries));
        return replacement;
    }
    public void forget(String memoryId,String scope)throws IOException {documents.withMemoryLock(()->{forgetUnlocked(memoryId,scope);return null;});}
    private void forgetUnlocked(String memoryId, String scope) throws IOException {
        validateId(memoryId);
        List<LongTermMemory> entries = new ArrayList<>(read());
        entries.remove(exactIndex(entries, memoryId, scope));
        documents.writeMemoryDocument(new Library(1, entries));
    }
    private static int exactIndex(List<LongTermMemory> entries, String id, String scope) {
        for (int i = 0; i < entries.size(); i++) {
            LongTermMemory memory = entries.get(i);
            if (memory.memoryId().equals(id) && memory.workspaceScope().equals(scope)) return i;
        }
        throw new NoSuchElementException("Memory id not found in this workspace");
    }
    public static void validateId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Expected a full memory UUID");
    }
    private static void rejectDuplicate(List<LongTermMemory> entries, String scope, String text, String exceptId) {
        entries.stream().filter(m -> m.workspaceScope().equals(scope) && m.content().equals(text)
                && !m.memoryId().equals(exceptId)).findFirst().ifPresent(m -> { throw new DuplicateMemoryException(m.memoryId()); });
    }
    public static final class DuplicateMemoryException extends IllegalArgumentException {
        private final String existingId;
        DuplicateMemoryException(String id) { super("Exact duplicate memory"); existingId = id; }
        public String existingId() { return existingId; }
    }
}
