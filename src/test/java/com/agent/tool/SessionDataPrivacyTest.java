package com.agent.tool;

import com.agent.tool.execution.DefaultProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionDataPrivacyTest {
    @TempDir Path workspace;

    @Test
    void workspaceToolsCannotDiscoverReadEditOrCreatePrivateSessionData() throws Exception {
        Path state = workspace.resolve(".agent-cli/conversations");
        Files.createDirectories(state);
        Files.writeString(state.resolve("other.json"), "privateSessionMarker");
        Path memory = Files.createDirectories(workspace.resolve(".agent-cli/memory"));
        Files.writeString(memory.resolve("shared.json"), "privateSharedMarker");
        Files.writeString(workspace.resolve("App.txt"), "normal-source");
        ToolRegistry registry = ToolRegistry.withCliCodingTools(workspace, new DefaultProcessRunner(),
                workspace.resolve(".m2/repository"));
        assertFalse(registry.execute("list_files", "{}").output().contains("other.json"));
        assertFalse(registry.execute("search_code", "{\"keyword\":\"privateSessionMarker\"}").output().contains("privateSessionMarker"));
        assertFalse(registry.execute("find_files", "{\"pattern\":\"*.json\"}").output().contains("other.json"));
        assertFalse(registry.execute("list_files", "{}").output().contains("shared.json"));
        assertFalse(registry.execute("search_code", "{\"keyword\":\"privateSharedMarker\"}").output().contains("privateSharedMarker"));
        assertFalse(registry.execute("find_files", "{\"pattern\":\"*.json\"}").output().contains("shared.json"));
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("read_file", "{\"path\":\".agent-cli/memory/shared.json\"}").errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("apply_patch", "{\"path\":\".agent-cli/memory/shared.json\",\"oldText\":\"privateSharedMarker\",\"newText\":\"changed\"}").errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("read_file", "{\"path\":\".agent-cli/conversations/other.json\"}").errorCode());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION,
                registry.execute("create_file", "{\"path\":\".agent-cli/conversations/new.json\",\"content\":\"x\"}").errorCode());
        assertTrue(registry.execute("read_file", "{\"path\":\"App.txt\"}").success());
        assertThrows(WorkspaceViolationException.class, () -> new WorkspacePathResolver(workspace)
                .resolveExisting("x/../.agent-cli/conversations/other.json"));
    }

    @Test
    void aliasesCannotExposeSessionFilesEvenWhenSearchingASubdirectory() throws Exception {
        Path state = workspace.resolve(".agent-cli/conversations");
        Files.createDirectories(state);
        Path privateFile = Files.writeString(state.resolve("other.json"), "aliasPrivateMarker");
        Path subdirectory = Files.createDirectory(workspace.resolve("src"));
        Path alias = subdirectory.resolve("alias.txt");
        try { Files.createSymbolicLink(alias, privateFile); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Creating symlinks is unavailable in this environment");
        }
        WorkspacePathResolver resolver = new WorkspacePathResolver(workspace);
        assertThrows(WorkspaceViolationException.class, () -> resolver.resolveExisting("src/alias.txt"));
        assertEquals(java.util.List.of(), new ListFilesTool().listFiles(subdirectory));
        assertEquals(java.util.List.of(), new SearchCodeTool().searchCode("aliasPrivateMarker", subdirectory));
        assertFalse(new FindFilesTool(workspace).execute("{\"pattern\":\"*.txt\"}").output().contains("alias.txt"));
    }
}
