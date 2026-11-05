package com.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stores user-requested CLI snapshots below the current workspace. */
public final class CliSessionStore {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LABELED_CREDENTIAL = Pattern.compile(
            "(?i)(?:authorization\\s*\\\\?[\\\"']?\\s*[:=]\\s*\\\\?[\\\"']?"
                    + "|(?:glm[_-])?api[_-]?key\\s*\\\\?[\\\"']?\\s*[:=]\\s*\\\\?[\\\"']?"
                    + "|(?:access[_-]?token|refresh[_-]?token|password)\\s*\\\\?[\\\"']?"
                    + "\\s*[:=]\\s*\\\\?[\\\"']?)"
                    + "(?:bearer\\s+)?[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final long MAX_SESSION_BYTES = 8L * 1024 * 1024;
    private final Path workspace;
    private final Path directory;
    private final TempFileWriter tempFileWriter;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CliSessionStore(Path workspace) {
        this(workspace, Files::write);
    }

    CliSessionStore(Path workspace, TempFileWriter tempFileWriter) {
        this(workspace, tempFileWriter, "sessions");
    }

    private CliSessionStore(Path workspace, TempFileWriter tempFileWriter, String subdirectory) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath().normalize();
        this.directory = this.workspace.resolve(".agent-cli").resolve(subdirectory).normalize();
        this.tempFileWriter = Objects.requireNonNull(tempFileWriter, "tempFileWriter must not be null");
    }

    static CliSessionStore conversations(Path workspace) {
        return new CliSessionStore(workspace, Files::write, "conversations");
    }

    static CliSessionStore memoryDocuments(Path workspace, TempFileWriter writer) {
        return new CliSessionStore(workspace, writer, "memory");
    }

    com.fasterxml.jackson.databind.JsonNode readMemoryDocument() throws IOException {
        ensureDirectorySafe(true); // validates without creating anything on a read
        Path target = resolve("shared");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)
                || Files.size(target) < 1 || Files.size(target) > MAX_SESSION_BYTES) throw new IOException("Shared memory file is unsafe");
        return mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
    }

    void writeMemoryDocument(Object document) throws IOException {
        prepareDirectory();
        Path target = resolve("shared");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) throw new IOException("Shared memory target is unsafe");
        byte[] bytes = mapper.writeValueAsBytes(document);
        // Content was separately validated; metadata sharingAuthorization is not an Authorization header.
        if (bytes.length > MAX_SESSION_BYTES) throw new IOException("Shared memory exceeds size limit");
        Path temporary = Files.createTempFile(directory, ".shared-", ".tmp");
        try {
            tempFileWriter.write(temporary, bytes);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    void writeConversation(CliConversation conversation, boolean replace) throws IOException {
        withMemoryLock(()->{writeConversationLocked(conversation,replace);return null;});
    }
    void writeConversationExpected(CliConversation conversation,CliConversation expected)throws IOException{
        withMemoryLock(()->{
            if(!mapper.valueToTree(loadConversation(expected.id())).equals(mapper.valueToTree(expected)))throw new IOException("Stale conversation snapshot");
            writeConversationLocked(conversation,true);return null;
        });
    }
    private void writeConversationLocked(CliConversation conversation, boolean replace) throws IOException {
        validateDocument(conversation.snapshot());
        Path target = resolve(conversation.id());
        prepareDirectory();
        if (replace) {
            // Reject links/non-regular files rather than replacing an unexpected target.
            loadConversation(conversation.id());
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        byte[] bytes = mapper.writeValueAsBytes(conversation);
        if (bytes.length > MAX_SESSION_BYTES
                || LABELED_CREDENTIAL.matcher(new String(bytes, StandardCharsets.UTF_8)).find()) {
            throw new IOException("Conversation is too large or contains credential-like text; not persisted");
        }
        Path temporary = Files.createTempFile(directory, ".conversation-", ".tmp");
        try {
            tempFileWriter.write(temporary, bytes);
            if (replace) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    CliConversation loadConversation(String id) throws IOException {
        // Managed ids are exact UUIDs; titles never participate in paths.
        try {
            if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IOException("Invalid conversation id"); }
        Path target = resolve(id);
        ensureDirectorySafe(false);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)
                || Files.size(target) < 1 || Files.size(target) > MAX_SESSION_BYTES) {
            throw new IOException("Conversation is missing or unsafe");
        }
        com.fasterxml.jackson.databind.JsonNode document = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
        if (document == null || !document.isObject()) throw new IOException("Invalid conversation document");
        normalizeOptionalSummary(document.get("snapshot"));
        CliConversation conversation = mapper.treeToValue(document, CliConversation.class);
        if (!id.equals(conversation.id())) throw new IOException("Conversation id does not match its filename");
        validateDocument(conversation.snapshot());
        String summaryScope = conversation.snapshot().summary().sessionId();
        if (!summaryScope.isEmpty() && !id.equals(summaryScope)) {
            conversation = new CliConversation(conversation.version(), id, conversation.title(), conversation.createdAt(),
                    conversation.updatedAt(), conversation.snapshot().withSummary(com.agent.memory.SessionSummary.empty()));
        }
        return conversation;
    }

    java.util.List<CliConversation> listConversations() throws IOException {
        ensureDirectorySafe(true);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        java.util.List<CliConversation> conversations = new java.util.ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String filename = file.getFileName().toString();
                conversations.add(loadConversation(filename.substring(0, filename.length() - 5)));
            }
        }
        conversations.sort(java.util.Comparator.comparing((CliConversation c) -> java.time.Instant.parse(c.updatedAt())).reversed()
                .thenComparing(CliConversation::id));
        return java.util.List.copyOf(conversations);
    }

    /** Streaming catalog read: skips snapshot payloads instead of materializing other sessions. */
    java.util.List<CliConversationHeader> conversationHeaders() throws IOException {
        ensureDirectorySafe(true);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        var result=new java.util.ArrayList<CliConversationHeader>();
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()) {
                String name=file.getFileName().toString(), expected=name.substring(0,name.length()-5);
                try {if(!java.util.UUID.fromString(expected).toString().equals(expected))throw new IllegalArgumentException();}
                catch(RuntimeException invalid){throw new IOException("Invalid conversation filename");}
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        ||Files.size(file)<1||Files.size(file)>MAX_SESSION_BYTES)throw new IOException("Unsafe conversation header");
                String id=null,title=null,time=null;int version=0;
                try(var parser=mapper.getFactory().createParser(file.toFile())) {
                    if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IOException("Invalid header");
                    while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                        if(parser.currentToken()==null)throw new IOException("Incomplete conversation header");
                        String field=parser.currentName();parser.nextToken();
                        if("id".equals(field))id=parser.getValueAsString();
                        else if("title".equals(field))title=parser.getValueAsString();
                        else if("updatedAt".equals(field))time=parser.getValueAsString();
                        else if("version".equals(field))version=parser.getValueAsInt();
                        else parser.skipChildren();
                    }
                }
                try {
                    if(version!=1||!expected.equals(id))throw new IllegalArgumentException();
                    CliConversation.validateTitle(title);java.time.Instant.parse(time);
                }catch(RuntimeException invalid){throw new IOException("Invalid conversation header");}
                result.add(new CliConversationHeader(id,title,time));
            }
        }
        result.sort(java.util.Comparator.comparing((CliConversationHeader h)->java.time.Instant.parse(h.updatedAt())).reversed().thenComparing(CliConversationHeader::id));
        return java.util.List.copyOf(result);
    }

    public void save(String name, CliSessionSnapshot snapshot) throws IOException {
        Path target = resolve(name);
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.version() != CliSessionSnapshot.CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported session version");
        }
        prepareDirectory();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        byte[] bytes = mapper.writeValueAsBytes(snapshot);
        if (bytes.length > MAX_SESSION_BYTES) {
            throw new IOException("Session exceeds the maximum supported size");
        }
        if (LABELED_CREDENTIAL.matcher(new String(bytes, StandardCharsets.UTF_8)).find()) {
            throw new IOException("Session contains credential-like text; remove it before saving");
        }
        Path temporary = Files.createTempFile(directory, ".session-", ".tmp");
        try {
            tempFileWriter.write(temporary, bytes);
            // Same-directory rename publishes only the fully written temporary document and does not replace.
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public CliSessionSnapshot load(String name) throws IOException {
        Path target = resolve(name);
        ensureDirectorySafe(false);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Saved session does not exist or is not a regular file");
        }
        long size = Files.size(target);
        if (size < 1 || size > MAX_SESSION_BYTES) {
            throw new IOException("Saved session has an invalid size");
        }
        com.fasterxml.jackson.databind.JsonNode document = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
        if (document == null || !document.isObject()) throw new IOException("Invalid session document");
        normalizeOptionalSummary(document);
        CliSessionSnapshot snapshot = mapper.treeToValue(document, CliSessionSnapshot.class);
        validateDocument(snapshot);
        return snapshot;
    }

    /** Only the optional derived summary may be discarded; invalid raw histories still fail closed. */
    private void normalizeOptionalSummary(com.fasterxml.jackson.databind.JsonNode snapshot) {
        if (!(snapshot instanceof com.fasterxml.jackson.databind.node.ObjectNode object) || !object.has("summary")) return;
        try { mapper.treeToValue(object.get("summary"), com.agent.memory.SessionSummary.class); }
        catch (IOException | IllegalArgumentException invalidSummary) { object.remove("summary"); }
    }

    public static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Session name must be 1-64 ASCII letters, digits, underscores, or hyphens");
        }
    }

    private Path resolve(String name) {
        validateName(name);
        Path target = directory.resolve(name + ".json").normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("Session path escapes the controlled directory");
        }
        return target;
    }

    private void prepareDirectory() throws IOException {
        ensureDirectorySafe(true);
        Files.createDirectories(directory);
        ensureDirectorySafe(true);
    }

    private void ensureDirectorySafe(boolean allowCreate) throws IOException {
        Path absoluteWorkspace = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteWorkspace)) {
            throw new IOException("Workspace directory is unavailable");
        }
        Path cursor = absoluteWorkspace;
        for (Path component : absoluteWorkspace.relativize(directory)) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IOException("Session directory must not contain symbolic links");
            }
            if (!allowCreate && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.NoSuchFileException(cursor.toString());
            }
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path realWorkspace = absoluteWorkspace.toRealPath();
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(realWorkspace)) {
                throw new IOException("Session directory escapes the workspace");
            }
        }
    }

    private static void validateDocument(CliSessionSnapshot snapshot) throws IOException {
        if (snapshot.version() != CliSessionSnapshot.CURRENT_VERSION) {
            throw new IOException("Unsupported saved session version: " + snapshot.version());
        }
        if (snapshot.lastAutoRoutedMode() == CliMode.AUTO) {
            throw new IOException("Saved AUTO continuation mode is invalid");
        }
        try {
            java.time.Instant.parse(snapshot.savedAt());
        } catch (RuntimeException exception) {
            throw new IOException("Saved session timestamp is invalid", exception);
        }
        if (!snapshot.histories().keySet().equals(java.util.Set.of(CliMode.CHAT, CliMode.READ, CliMode.CODE))) {
            throw new IOException("Saved session is missing one or more CLI profile histories");
        }
    }

    @FunctionalInterface
    interface MemoryOperation<T> { T run() throws IOException; }
    <T> T withMemoryLock(MemoryOperation<T> action)throws IOException {
        prepareDirectory();
        Path lock=directory.resolve(".write.lock");
        if(Files.isSymbolicLink(lock))throw new IOException("Unsafe memory lock");
        try(var channel=java.nio.channels.FileChannel.open(lock,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            try(var held=channel.tryLock()){
                if(held==null)throw new IOException("Memory library busy");
                return action.run();
            }catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("Memory library busy");}
        }
    }
    @FunctionalInterface
    interface TempFileWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }
}
