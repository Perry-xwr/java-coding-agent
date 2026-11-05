package com.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.IOException;
/** Settings only; no chat, credentials, or Memory entries. One file per workspace. */
public final class MemoryConsentStore {
 public record Settings(int version,long revision,boolean enabled,boolean autoConsent,boolean semanticEnabled){public Settings(int version,long revision,boolean enabled,boolean autoConsent){this(version,revision,enabled,autoConsent,false);}public Settings{if(version!=1||revision<0||semanticEnabled&&(!enabled||!autoConsent))throw new IllegalArgumentException();}public static Settings off(){return new Settings(1,0,false,false);}}
 private final Path file;private final ObjectMapper json=new ObjectMapper();
 public MemoryConsentStore(Path file){this.file=file.toAbsolutePath().normalize();}
 public static MemoryConsentStore forWorkspace(Path w)throws IOException{String local=System.getenv("LOCALAPPDATA");Path root=local==null||local.isBlank()?Path.of(System.getProperty("user.home"),".agent-cli","catalog"):Path.of(local,"agent-cli","catalog");String identity=DailyWorkspace.check(w).toRealPath().toString();if(System.getProperty("os.name","").toLowerCase().contains("win"))identity=identity.toLowerCase(java.util.Locale.ROOT);return new MemoryConsentStore(root.resolve("memory-consent-"+com.agent.memory.SessionSummary.digest(identity)+".json"));}
 private void safe()throws IOException{for(Path p=file;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe settings path");}
 public Settings read()throws IOException{safe();if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Settings.off();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Invalid settings");try{Settings value=json.readValue(Files.readAllBytes(file),Settings.class);if(value==null)throw new IOException("Null settings");return value;}catch(RuntimeException bad){throw new IOException("Invalid settings");}}
 public Settings update(Settings expected,boolean enabled,boolean auto)throws IOException{
  return update(expected,enabled,auto,expected.semanticEnabled()&&enabled&&auto);
 }
 public Settings update(Settings expected,boolean enabled,boolean auto,boolean semantic)throws IOException{
  safe();Files.createDirectories(file.getParent());Path lock=file.resolveSibling(file.getFileName()+".lock");if(Files.isSymbolicLink(lock))throw new IOException("Unsafe settings lock");
  try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.tryLock()){
   if(held==null)throw new IOException("Settings busy");if(!read().equals(expected))throw new IOException("Settings changed; reload before retry");Settings next=new Settings(1,Math.addExact(expected.revision(),1),enabled,auto,semantic);Path temp=Files.createTempFile(file.getParent(),"consent-",".tmp");try{Files.write(temp,json.writeValueAsBytes(next));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}return next;
  }catch(OverlappingFileLockException busy){throw new IOException("Settings busy");}
 }
}
