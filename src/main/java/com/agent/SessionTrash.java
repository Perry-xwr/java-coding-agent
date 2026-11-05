package com.agent;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
/** Current workspace only, exact IDs and no permanent deletion. */
final class SessionTrash {
 private final Path workspace,live,trash;private final CliSessionStore store;
 SessionTrash(Path workspace)throws IOException{this.workspace=DailyWorkspace.check(workspace);live=this.workspace.resolve(".agent-cli/conversations");trash=this.workspace.resolve(".agent-cli/trash/conversations");store=CliSessionStore.conversations(workspace);}
 private Path path(Path base,String id)throws IOException{try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("Invalid session ID");}for(Path p=base;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe path");return base.resolve(id+".json");}
 private String hash(Path file)throws IOException{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>8388608)throw new IOException("Unsafe snapshot");try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}}
 String fingerprint(String id)throws IOException{return hash(path(live,id));}
 private void writers()throws IOException{
  Set<Long> ancestors=new HashSet<>();for(var parent=ProcessHandle.current().parent();parent.isPresent();parent=parent.get().parent())ancestors.add(parent.get().pid());ancestors.add(ProcessHandle.current().pid());
  try(var processes=ProcessHandle.allProcesses()){if(processes.anyMatch(p->!ancestors.contains(p.pid())&&p.info().command().map(c->c.toLowerCase(Locale.ROOT).matches(".*[\\\\/](java|javaw)(\\.exe)?")).orElse(false)))throw new IOException("Other Java processes: close other writers first");}
 }
 void delete(String id,String expected)throws IOException{writers();store.withMemoryLock(()->{
  Path from=path(live,id),to=path(trash,id);store.loadConversation(id);if(!fingerprint(id).equals(expected))throw new IOException("Snapshot changed");Files.createDirectories(trash);if(Files.exists(to,LinkOption.NOFOLLOW_LINKS))throw new IOException("Trash conflict");
  Path metadata=trash.resolve(id+".properties");if(Files.exists(metadata,LinkOption.NOFOLLOW_LINKS))throw new IOException("Incomplete prior operation: inspect trash");
  var meta=new Properties();meta.setProperty("workspace",workspace.toRealPath().toString());meta.setProperty("title",store.loadConversation(id).title());meta.setProperty("deletedAt",java.time.Instant.now().toString());meta.setProperty("sha256",expected);
  try(var out=Files.newOutputStream(metadata,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){meta.store(out,"Session trash metadata");}
  Files.move(from,to);return null;
 });}
 void restore(String id)throws IOException{writers();store.withMemoryLock(()->{
  Path from=path(trash,id),to=path(live,id),metadata=trash.resolve(id+".properties");if(Files.exists(to,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)||Files.size(metadata)>8192)throw new IOException("Restore conflict/metadata");
  var meta=new Properties();try(var in=Files.newInputStream(metadata,LinkOption.NOFOLLOW_LINKS)){meta.load(in);}if(!workspace.toRealPath().toString().equals(meta.getProperty("workspace"))||!hash(from).equals(meta.getProperty("sha256")))throw new IOException("Wrong workspace or changed trash");java.time.Instant.parse(meta.getProperty("deletedAt"));
  Files.move(from,to);Files.move(metadata,trash.resolve(id+".restored-"+UUID.randomUUID()+".properties"));return null;
 });}
 List<String> list()throws IOException{path(trash,UUID.randomUUID().toString());if(!Files.exists(trash))return List.of();try(var files=Files.list(trash)){return files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).map(p->p.getFileName().toString().replace(".json","")).toList();}}
}
