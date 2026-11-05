package com.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Prevents accidental Fake writes to an existing user workspace. Not an OS sandbox. */
public final class TuiSessionWorkspace {
    private static final String MARKER=".tui2-offline-workspace";
    private TuiSessionWorkspace() { }
    private static Path safe(Path path)throws IOException {
        Path root=path.toAbsolutePath().normalize();
        for(Path cursor=root;cursor!=null;cursor=cursor.getParent())
            if(Files.isSymbolicLink(cursor))throw new IOException("Symbolic workspace path refused");
        return root;
    }
    public static Path create(Path path)throws IOException {
        Path root=safe(path);Files.createDirectory(root);
        Files.writeString(root.resolve(MARKER),"TUI2_FAKE_ONLY\n"+root,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        return root;
    }
    public static Path writable(Path path)throws IOException {
        Path root=safe(path),marker=root.resolve(MARKER);
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker)||Files.size(marker)>4096
                ||!Files.readString(marker,StandardCharsets.UTF_8).equals("TUI2_FAKE_ONLY\n"+root))
            throw new IOException("Fake writes require a fresh isolated TUI2 workspace");
        return root;
    }
    public static Path readable(Path path)throws IOException {
        Path root=safe(path);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Workspace unavailable");return root;
    }
}
