package com.agent.memory;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** An exact user-authorized statement, not a verified fact inferred by a model. */
public record LongTermMemory(String memoryId, String content, String sourceSessionId, String createdAt,
                              String updatedAt, String sourceType, String sharingAuthorization,
                              String workspaceScope, String sourceMessageReference, java.util.List<Revision> revisions) {
    public LongTermMemory(String id,String content,String source,String created,String updated,String type,String authorization,String scope,String reference){this(id,content,source,created,updated,type,authorization,scope,reference,java.util.List.of());}
    public record Revision(String oldContent,String reason,String candidateId,String changedAt){public Revision{validateContent(oldContent); if(reason==null||reason.length()>256)throw new IllegalArgumentException(); java.time.Instant.parse(changedAt); if(candidateId!=null)UUID.fromString(candidateId);}}
    public static final int MAX_CONTENT_CHARS = 1024;
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(api[_ -]?key|authorization|bearer\\s+|password|passwd|access[_ -]?token|refresh[_ -]?token|"
                    + "client[_ -]?secret|secret[_ -]?(key|access)|private[_ -]?key|BEGIN.{0,20}PRIVATE KEY|"
                    + "AKIA[A-Z0-9]{12,}|sk-[A-Za-z0-9]{12,}|https?://[^\\s/]+:[^\\s/]+@)");
    public LongTermMemory {
        revisions=revisions==null?java.util.List.of():java.util.List.copyOf(revisions); if(revisions.size()>20)throw new IllegalArgumentException("Revision limit");
        requireId(memoryId); requireId(sourceSessionId); validateContent(content);
        boolean manual="USER_MEMORY_COMMAND".equals(sourceType)&&"EXPLICIT_USER".equals(sharingAuthorization)&&sourceMessageReference==null;
        boolean automatic=java.util.Set.of("RULE_VERIFIED_USER_QUOTE","SEMANTIC_EXTRACTED_USER_QUOTE","AGENT_MANAGED_USER_QUOTE").contains(sourceType)&&"EXPLICIT_WORKSPACE_AUTO_POLICY".equals(sharingAuthorization)&&sourceMessageReference!=null&&sourceMessageReference.matches("(CODE|READ|CHAT):[0-9]+:[a-f0-9]{64}:policy=[0-9]+");
        if (!(manual||automatic)
                || workspaceScope == null || workspaceScope.isBlank()
                || Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
            throw new IllegalArgumentException("Memory lacks a supported explicit authorization/source/scope");
        }
    }
    public static void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_CHARS
                || content.codePoints().anyMatch(Character::isISOControl) || CREDENTIAL.matcher(content).find()) {
            throw new IllegalArgumentException("Memory must be nonblank, single-line, at most 1024 characters and contain no credential-like fields");
        }
    }
    private static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid memory source/id");
    }
}
