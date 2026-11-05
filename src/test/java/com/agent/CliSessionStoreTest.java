package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSessionStoreTest {
    @TempDir
    Path workspace;

    @Test
    void savesAndRestoresAllHistoriesAndToolAssociationsWithoutPersistingProviderConfig() throws Exception {
        CliSessions sessions = sessions();
        List<Message> chatHistory = List.of(
                sessions.chat().history().get(0),
                Message.user("hello"),
                Message.assistant("Calling a tool", List.of(new ToolCall("call-1", "read_file",
                        "{\"path\":\"README.md\"}"))),
                Message.tool("call-1", "README content"),
                Message.assistant("Done")
        );
        Map<CliMode, List<Message>> histories = Map.of(
                CliMode.CHAT, chatHistory,
                CliMode.READ, sessions.read().history(),
                CliMode.CODE, sessions.code().history()
        );
        CliWorkingContext.Snapshot context = new CliWorkingContext.Snapshot(
                WorkingMemoryMode.STRUCTURED_MEMORY,
                "read README.md",
                List.of("README.md"),
                List.of("README.md"),
                "README.md",
                List.of("README.md", "src/App.java"),
                List.of(new CliWorkingContext.VerifiedFileFact(
                        CliWorkingContext.VerifiedFileFactType.FILE_READ, "README.md")),
                List.of(new CliWorkingContext.ToolFailure("read_file", "missing.txt",
                        com.agent.tool.ToolErrorCode.FILE_NOT_FOUND)),
                new CliWorkingContext.LastMutation("README.md", "apply_patch"));
        CliSessionSnapshot snapshot = new CliSessionSnapshot(1, "2026-10-16T00:00:00Z", CliMode.CODE,
                CliMode.READ, histories, context);
        CliSessionStore store = new CliSessionStore(workspace);

        store.save("classroom", snapshot);
        CliSessionSnapshot loaded = new CliSessionStore(workspace).load("classroom");
        CliSessions freshSessions = sessions();
        freshSessions.validateHistories(loaded.histories());
        freshSessions.restoreHistories(loaded.histories());

        assertEquals(snapshot, loaded);
        assertEquals("call-1", freshSessions.chat().history().get(2).toolCalls().get(0).get("id"));
        assertEquals("call-1", freshSessions.chat().history().get(3).toolCallId());
        String json = Files.readString(workspace.resolve(".agent-cli/sessions/classroom.json"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("Authorization"));
        assertFalse(json.contains("GLM_API_KEY"));
    }

    @Test
    void sessionSnapshotStoresOriginalHistoryRatherThanASelectedRequestCopy() throws Exception {
        CliSessions sessions = sessions();
        Message firstCall = Message.assistant("", List.of(new ToolCall("find-old", "find_files",
                "{\"pattern\":\"*.py\"}")));
        Message firstResult = Message.tool("find-old", "{\"files\":[\"a.py\"]}");
        Message secondCall = Message.assistant("Searching again", List.of(new ToolCall("find-new", "find_files",
                "{\"pattern\":\"*.py\"}")));
        Message secondResult = Message.tool("find-new", "{\"files\":[\"a.py\"]}");
        List<Message> rawChat = List.of(sessions.chat().history().get(0), Message.user("find files"),
                firstCall, firstResult, Message.assistant("Found them."), Message.user("find again"),
                secondCall, secondResult);
        CliSessionSnapshot rawSnapshot = new CliSessionSnapshot(CliSessionSnapshot.CURRENT_VERSION,
                "2026-10-16T00:00:00Z", CliMode.CHAT, null,
                Map.of(CliMode.CHAT, rawChat, CliMode.READ, sessions.read().history(),
                        CliMode.CODE, sessions.code().history()), new CliWorkingContext().snapshot());

        CliSessionStore store = new CliSessionStore(workspace);
        store.save("raw-history", rawSnapshot);
        Path snapshotPath = workspace.resolve(".agent-cli/sessions/raw-history.json");
        String legacyShape = Files.readString(snapshotPath)
                .replace("\"content\":\"\",\"tool_calls\":", "\"tool_calls\":");
        Files.writeString(snapshotPath, legacyShape);
        CliSessionSnapshot loaded = store.load("raw-history");
        CliSessions restored = sessions();
        restored.restoreHistories(loaded.histories());

        assertEquals(rawChat, restored.chat().history());
        assertEquals(2, restored.chat().history().stream().flatMap(message -> message.toolCalls().stream())
                .count());
        assertEquals(2, restored.chat().history().stream().filter(message -> "tool".equals(message.role())).count());
    }

    @Test
    void refusesToOverwriteAnExistingSession() throws Exception {
        CliSessionStore store = new CliSessionStore(workspace);
        CliSessionSnapshot snapshot = snapshot(sessions());
        store.save("same", snapshot);
        byte[] original = Files.readAllBytes(workspace.resolve(".agent-cli/sessions/same.json"));

        assertThrows(FileAlreadyExistsException.class, () -> store.save("same", snapshot));
        assertTrue(java.util.Arrays.equals(original,
                Files.readAllBytes(workspace.resolve(".agent-cli/sessions/same.json"))));
        try (var files = Files.list(workspace.resolve(".agent-cli/sessions"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsBlankTraversalAbsoluteAndUnsupportedSessionNames() {
        for (String name : List.of("", "../escape", "folder/name", "folder\\name", "C:escape", ".", "bad name")) {
            assertThrows(IllegalArgumentException.class, () -> CliSessionStore.validateName(name), name);
        }
        assertThrows(IllegalArgumentException.class, () -> CliSessionStore.validateName(null));
    }

    @Test
    void missingCorruptAndIncompatibleSessionsAreRejected() throws Exception {
        CliSessionStore store = new CliSessionStore(workspace);
        assertThrows(IOException.class, () -> store.load("missing"));

        Path dir = workspace.resolve(".agent-cli/sessions");
        Files.createDirectories(dir);
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{not-json");
        assertThrows(IOException.class, () -> store.load("broken"));

        CliSessionSnapshot snapshot = snapshot(sessions());
        store.save("version", snapshot);
        Path versionFile = dir.resolve("version.json");
        String json = Files.readString(versionFile).replace("\"version\":1", "\"version\":99");
        Files.writeString(versionFile, json);
        assertThrows(IOException.class, () -> store.load("version"));
    }

    @Test
    void failedSaveCleansTemporaryFileAndDoesNotPublishPartialDocument() throws Exception {
        CliSessionStore store = new CliSessionStore(workspace, (path, bytes) -> {
            Files.write(path, bytes);
            throw new IOException("simulated disk failure");
        });

        assertThrows(IOException.class, () -> store.save("failure", snapshot(sessions())));
        Path dir = workspace.resolve(".agent-cli/sessions");
        assertFalse(Files.exists(dir.resolve("failure.json")));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsUnmatchedToolCallOrResultDuringRestoreValidation() {
        CliSessions sessions = sessions();
        List<Message> invalid = List.of(
                sessions.chat().history().get(0),
                Message.assistant("Calling", List.of(new ToolCall("missing", "read_file", "{}")))
        );
        assertThrows(IllegalArgumentException.class, () -> sessions.chat().validateRestoredHistory(invalid));
    }

    @Test
    void refusesToPersistRecognizableAuthorizationOrApiKeyMaterial() {
        CliSessions sessions = sessions();
        Map<CliMode, List<Message>> histories = Map.of(
                CliMode.CHAT, List.of(sessions.chat().history().get(0),
                        Message.user("Authorization: Bearer abcdefghijklmnop")),
                CliMode.READ, sessions.read().history(),
                CliMode.CODE, sessions.code().history()
        );
        CliSessionSnapshot snapshot = new CliSessionSnapshot(1, "2026-10-16T00:00:00Z", CliMode.CHAT,
                null, histories, new CliWorkingContext().snapshot());

        assertThrows(IOException.class, () -> new CliSessionStore(workspace).save("secret", snapshot));
        assertFalse(Files.exists(workspace.resolve(".agent-cli/sessions/secret.json")));

        Map<CliMode, List<Message>> jsonHistories = Map.of(
                CliMode.CHAT, List.of(sessions.chat().history().get(0),
                        Message.user("{\"api_key\":\"abcdefghijklmnop\"}")),
                CliMode.READ, sessions.read().history(),
                CliMode.CODE, sessions.code().history()
        );
        CliSessionSnapshot jsonSnapshot = new CliSessionSnapshot(1, "2026-10-16T00:00:00Z", CliMode.CHAT,
                null, jsonHistories, new CliWorkingContext().snapshot());
        assertThrows(IOException.class, () -> new CliSessionStore(workspace).save("json-secret", jsonSnapshot));
        assertFalse(Files.exists(workspace.resolve(".agent-cli/sessions/json-secret.json")));
    }

    private CliSessionSnapshot snapshot(CliSessions sessions) {
        return new CliSessionSnapshot(1, "2026-10-16T00:00:00Z", CliMode.AUTO, null,
                sessions.histories(), new CliWorkingContext().snapshot());
    }

    private CliSessions sessions() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = messages -> {
            calls.incrementAndGet();
            return new LLMResponse("unused", List.of());
        };
        return CliAgentFactory.createProfiles(client, workspace, AgentEventListener.NO_OP);
    }
}
