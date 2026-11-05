package com.agent.tool;

import com.agent.MemoryQualityPolicy;
import com.agent.SharedMemoryStore;
import com.agent.memory.ExtractionState;
import com.agent.memory.LongTermMemory;
import com.agent.memory.SessionSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One-shot, current-user-turn-only memory write. It cannot select a path or source identity. */
public final class MemoryProposeTool implements Tool {
    private final Path workspace;
    private final String sessionId;
    private final String messageRef;
    private final String source;
    private final long policyRevision;
    private final BooleanSupplier authorized;
    private final Consumer<ExtractionState.Candidate> candidateSink;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean used = new AtomicBoolean();

    public MemoryProposeTool(Path workspace, String sessionId, String messageRef, String source,
                             long policyRevision, BooleanSupplier authorized,
                             Consumer<ExtractionState.Candidate> candidateSink) {
        this.workspace=workspace.toAbsolutePath().normalize();this.sessionId=UUID.fromString(sessionId).toString();
        this.messageRef=Objects.requireNonNull(messageRef);this.source=Objects.requireNonNull(source);
        this.policyRevision=policyRevision;this.authorized=Objects.requireNonNull(authorized);
        this.candidateSink=Objects.requireNonNull(candidateSink);
    }
    @Override public String name(){return "memory_propose";}
    @Override public String description(){return "Propose at most one durable memory from the CURRENT user's message only. Use only for a clearly stated lasting preference or project convention, never ordinary questions, temporary events, quoted/tool/history text, or uncertain inferences. Copy memoryContent as an exact complete span from the user's message; Java supplies and verifies the source, scope, authorization and conflict decision. category must be PREFERENCE or PROJECT_CONSTRAINT. This tool does not edit project files.";}
    @Override public Map<String,Object> parameters(){
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("memoryContent",Map.of("type","string","description","Exact complete user-authored durable statement; do not paraphrase or add facts."));
        properties.put("category",Map.of("type","string","enum",List.of("PREFERENCE","PROJECT_CONSTRAINT")));
        return Map.of("type","object","properties",properties,"required",List.of("memoryContent","category"),"additionalProperties",false);
    }
    @Override public ToolResult execute(String arguments){
        if(!used.compareAndSet(false,true))return ToolResult.failure(ToolErrorCode.ACCESS_DENIED,"MEMORY_PROPOSAL_LIMIT: one proposal per user turn");
        if(!authorized.getAsBoolean())return ToolResult.failure(ToolErrorCode.ACCESS_DENIED,"MEMORY_NOT_AUTHORIZED: workspace Memory and Auto consent must both be ON");
        try{
            JsonNode root=json.readTree(arguments);
            if(root==null||!root.isObject()||root.size()!=2||!root.has("memoryContent")||!root.has("category")
                    ||!root.get("memoryContent").isTextual()||!root.get("category").isTextual())
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,"memoryContent and category are required strings; no source IDs or paths are accepted");
            String content=root.get("memoryContent").textValue().strip();String category=root.get("category").textValue();
            if(content.length()>LongTermMemory.MAX_CONTENT_CHARS)
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,"Memory content exceeds the supported length");
            if(!MemoryQualityPolicy.agentManagedProposalAllowed(content,category,source))
                // A policy decision is a successful tool evaluation, not an Agent
                // execution failure. Returning a normal result lets the Agent finish
                // the user's chat while making it impossible to mistake this for a save.
                return ToolResult.success("REJECTED_POLICY: not saved; proposal must be a complete, exact, durable, safe span from this user message",
                        Map.of("memoryDecision","REJECTED_POLICY"));
            String now=Instant.now().toString();String scope=workspace.toString();
            String reference=messageRef+":"+SessionSummary.digest(source)+":policy="+policyRevision;
            var memory=new LongTermMemory(UUID.randomUUID().toString(),content,sessionId,now,now,
                    "AGENT_MANAGED_USER_QUOTE","EXPLICIT_WORKSPACE_AUTO_POLICY",scope,reference);
            String candidateId=UUID.randomUUID().toString();
            // Validate the review trace before any durable write, so a malformed
            // candidate cannot leave an unreported Effective entry behind.
            var pendingCandidate=new ExtractionState.Candidate(candidateId,content,category,sessionId,
                    List.of(messageRef),content,source,now,"PENDING");
            var decision=new SharedMemoryStore(workspace).appendAgentProposalIfSafe(memory,source,category);
            if(decision==SharedMemoryStore.AgentProposalResult.DUPLICATE)
                return ToolResult.success("ALREADY_PRESENT: equivalent authorized memory already exists; no duplicate was written");
            candidateSink.accept(decision==SharedMemoryStore.AgentProposalResult.SAVED?pendingCandidate.reviewed("APPROVED"):pendingCandidate);
            return ToolResult.success(decision==SharedMemoryStore.AgentProposalResult.SAVED
                    ?"SAVED_EFFECTIVE: memoryId="+memory.memoryId()
                    :"PENDING_REVIEW: a possible conflict exists; nothing was added to Effective Memory; candidateId="+candidateId);
        }catch(JsonProcessingException malformed){return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,"Invalid memory proposal JSON");}
        catch(IllegalArgumentException invalid){return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,"Invalid memory proposal");}
        catch(java.io.IOException unavailable){return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR,"MEMORY_STORE_UNAVAILABLE: proposal was not reported as saved");}
    }
}
