package com.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspacePathResolverTest {
    @TempDir
    Path tempDir;

    private Path workspace;
    private Path outside;
    private WorkspacePathResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectory(tempDir.resolve("workspace"));
        outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(workspace.resolve("README.md"), "readme");
        Files.writeString(workspace.resolve("pom.xml"), "pom");
        Path source = Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(source.resolve("App.java"), "class App {}");
        resolver = new WorkspacePathResolver(workspace);
    }

    @Test
    void resolvesFileAtWorkspaceRoot() throws IOException {
        assertEquals(workspace.resolve("README.md").toRealPath(), resolver.resolveExisting("README.md"));
    }

    @Test
    void resolvesNestedFile() throws IOException {
        assertEquals(
                workspace.resolve("src/main/java/App.java").toRealPath(),
                resolver.resolveExisting("src/main/java/App.java")
        );
    }

    @Test
    void resolvesDotRelativeFile() throws IOException {
        assertEquals(workspace.resolve("README.md").toRealPath(), resolver.resolveExisting("./README.md"));
    }

    @Test
    void allowsNormalizationThatRemainsInsideWorkspace() throws IOException {
        assertEquals(workspace.resolve("pom.xml").toRealPath(), resolver.resolveExisting("src/../pom.xml"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "../../outside.txt", "src/../../../outside.txt"})
    void rejectsTraversalOutsideWorkspace(String path) {
        assertThrows(WorkspaceViolationException.class, () -> resolver.resolveExisting(path));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() throws IOException {
        Path outsideFile = Files.writeString(outside.resolve("outside.txt"), "outside");

        assertThrows(
                WorkspaceViolationException.class,
                () -> resolver.resolveExisting(outsideFile.toAbsolutePath().toString())
        );
    }

    @Test
    void rejectsAbsolutePathInsideWorkspace() {
        assertThrows(
                WorkspaceViolationException.class,
                () -> resolver.resolveExisting(workspace.resolve("README.md").toAbsolutePath().toString())
        );
    }

    @Test
    void rejectsSymlinkThatEscapesWorkspaceWhenSupported() throws IOException {
        Path outsideFile = Files.writeString(outside.resolve("secret.txt"), "outside");
        Path link = workspace.resolve("outside-link.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(
                WorkspaceViolationException.class,
                () -> resolver.resolveExisting("outside-link.txt")
        );
    }
}
