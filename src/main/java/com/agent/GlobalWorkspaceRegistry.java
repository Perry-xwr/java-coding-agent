package com.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;

/** User-selected workspace catalog only. Session bodies remain in M1 stores. */
public final class GlobalWorkspaceRegistry {
    private final Path directory;
    private final ObjectMapper json=new ObjectMapper();
    public record Entry(String key,String workspace,CliConversationHeader header,String status){}
    public GlobalWorkspaceRegistry(Path directory){this.directory=directory.toAbsolutePath().normalize();}
    public static GlobalWorkspaceRegistry userDefault(){String local=System.getenv("LOCALAPPDATA");return new GlobalWorkspaceRegistry(local==null||local.isBlank()?Path.of(System.getProperty("user.home"),".agent-cli","catalog"):Path.of(local,"agent-cli","catalog"));}
    private void safe()throws IOException{for(Path p=directory;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe registry path");}
    private String identity(Path path)throws IOException{return DailyWorkspace.check(path).toRealPath().toString();}
    private String key(String path){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?path.toLowerCase(Locale.ROOT):path;}
    private List<String> read()throws IOException{
        safe();Path file=directory.resolve("workspaces.json");if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1048576)throw new IOException("Invalid registry");
        var root=json.readTree(Files.readAllBytes(file));if(!root.isArray()||root.size()>1000)throw new IOException("Invalid registry");
        List<String> paths=new ArrayList<>();for(var node:root){if(!node.isTextual()||!Path.of(node.asText()).isAbsolute())throw new IOException("Invalid workspace record");paths.add(node.asText());}return paths;
    }
    public void register(Path workspace)throws IOException{
        String real=identity(workspace);safe();Files.createDirectories(directory);Path lock=directory.resolve("catalog.lock");
        if(Files.isSymbolicLink(lock))throw new IOException("Unsafe registry lock");
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.tryLock()){
            if(held==null)throw new IOException("Registry busy");List<String> paths=new ArrayList<>(read());
            if(paths.stream().anyMatch(p->key(p).equals(key(real))))return;
            if(paths.size()>=1000)throw new IOException("Registry capacity reached");paths.add(real);
            Path temp=Files.createTempFile(directory,"catalog-",".tmp");try{Files.write(temp,json.writeValueAsBytes(paths));Files.move(temp,directory.resolve("workspaces.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
        }catch(OverlappingFileLockException ex){throw new IOException("Registry busy");}
    }
    public List<Entry> entries()throws IOException{
        List<Entry> result=new ArrayList<>();for(String path:read()){
            String prefix="foreign:"+UUID.nameUUIDFromBytes(key(path).getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
            try{Path w=DailyWorkspace.check(Path.of(path));for(var h:new CliConversationManager(w).headers())result.add(new Entry(prefix+h.id(),path,h,"AVAILABLE"));}
            catch(IOException|RuntimeException ex){result.add(new Entry(prefix+"unavailable",path,new CliConversationHeader("unavailable","[Workspace unavailable]","1970-01-01T00:00:00Z"),"UNAVAILABLE"));}
        }
        result.sort(Comparator.comparing((Entry e)->e.header().updatedAt()).reversed().thenComparing(Entry::key));return List.copyOf(result);
    }
    public boolean sameWorkspace(String path,Path current)throws IOException{return key(path).equals(key(identity(current)));}
}
