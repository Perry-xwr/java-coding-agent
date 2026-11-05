package com.agent;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Daily workspace identity and single-writer lease. Not a sandbox or a second session store. */
public final class DailyWorkspace implements AutoCloseable {
    private final Path root;private final FileChannel channel;private final FileLock lock;
    private DailyWorkspace(Path root,FileChannel channel,FileLock lock){this.root=root;this.channel=channel;this.lock=lock;}
    static Path check(Path path)throws IOException{
        Path root=path.toAbsolutePath().normalize();
        for(Path p=root;p!=null;p=p.getParent()){
            if(Files.isSymbolicLink(p))throw new IOException("DAILY_UNSAFE_WORKSPACE");
            if(Files.exists(p.resolve("final-api-owner.json"),LinkOption.NOFOLLOW_LINKS))throw new IOException("DAILY_REFUSES_EXPERIMENT_WORKSPACE");
        }
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.exists(root.resolve(".tui2-offline-workspace"),LinkOption.NOFOLLOW_LINKS))throw new IOException("DAILY_REQUIRES_REAL_PROJECT_DIRECTORY");
        if(Files.isSymbolicLink(root.resolve(".agent-cli")))throw new IOException("DAILY_UNSAFE_SESSION_DIRECTORY");return root;
    }
    public static DailyWorkspace acquire(Path path)throws IOException{
        Path root=check(path);
        String identity=root.toRealPath().toString();if(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))identity=identity.toLowerCase(java.util.Locale.ROOT);
        String hash;try{hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("SHA256_UNAVAILABLE");}
        Path directory=Path.of(System.getProperty("java.io.tmpdir"),"agent-cli-daily-locks").toAbsolutePath().normalize();
        if(directory.startsWith(root))throw new IOException("DAILY_LOCK_DIRECTORY_INSIDE_WORKSPACE");
        for(Path p=directory;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("DAILY_UNSAFE_LOCK_DIRECTORY");
        Files.createDirectories(directory);
        FileChannel channel=FileChannel.open(directory.resolve(hash+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{FileLock lock=channel.tryLock();if(lock==null)throw new IOException("DAILY_WORKSPACE_BUSY");return new DailyWorkspace(root,channel,lock);}
        catch(IOException|RuntimeException failure){channel.close();throw new IOException("DAILY_WORKSPACE_BUSY_OR_UNSAFE");}
    }
    public Path verify(Path path)throws IOException{
        if(!lock.isValid()||!check(path).equals(root))throw new IOException("DAILY_WORKSPACE_LEASE_INVALID");return root;
    }
    public Path root(){return root;}
    public void close()throws IOException{try{if(lock.isValid())lock.release();}finally{channel.close();}}
}
