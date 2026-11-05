package com.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Owned final-validation workspace only. Never adopts an existing project or personal session store. */
public final class FinalApiWorkspace {
    public static final String POM="<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>offline.fixture</groupId><artifactId>readonly-java-version</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties></project>";
    public record Owner(int version,String root,String transport){}
    private FinalApiWorkspace(){}
    private static Path safe(Path root)throws IOException{
        root=root.toAbsolutePath().normalize();for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("UNSAFE_FINAL_API_PATH");return root;
    }
    public static Path create(Path root,boolean live)throws IOException{
        root=safe(root);Files.createDirectory(root);
        new ObjectMapper().writeValue(root.resolve("final-api-owner.json").toFile(),new Owner(1,root.toString(),live?"LIVE":"MOCK_HTTP"));
        Path workspace=TuiSessionWorkspace.create(root.resolve("workspace"));Files.writeString(workspace.resolve("pom.xml"),POM,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.createDirectory(root.resolve("evidence"));return check(root,live);
    }
    public static Path check(Path root,boolean live)throws IOException{
        root=safe(root);Path owner=root.resolve("final-api-owner.json");
        if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.size(owner)>4096)throw new IOException("FINAL_API_OWNER_MISSING");
        Owner value=new ObjectMapper().readValue(Files.readAllBytes(owner),Owner.class);
        if(value.version()!=1||!value.root().equals(root.toString())||!value.transport().equals(live?"LIVE":"MOCK_HTTP"))throw new IOException("FINAL_API_OWNER_OR_TRANSPORT_MISMATCH");
        Path w=TuiSessionWorkspace.writable(root.resolve("workspace")),pom=w.resolve("pom.xml");
        if(!Files.isRegularFile(pom,LinkOption.NOFOLLOW_LINKS)||Files.size(pom)>4096||!Files.readString(pom,StandardCharsets.UTF_8).equals(POM))throw new IOException("FINAL_API_FIXTURE_CHANGED");
        if(!Files.isDirectory(root.resolve("evidence"),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root.resolve("evidence")))throw new IOException("UNSAFE_FINAL_API_EVIDENCE");
        try(var files=Files.list(w)){if(files.anyMatch(p->Files.isSymbolicLink(p)||!Set.of("pom.xml",".tui2-offline-workspace",".agent-cli").contains(p.getFileName().toString())))throw new IOException("UNEXPECTED_FINAL_API_WORKSPACE_CONTENT");}
        if(Files.exists(w.resolve(".agent-cli/memory"),LinkOption.NOFOLLOW_LINKS))throw new IOException("FINAL_API_SHARED_MEMORY_NOT_ALLOWED");
        for(var conversation:new CliConversationManager(w).list())try{FinalApiTuiAdapter.validate(conversation.snapshot());}catch(IllegalArgumentException invalid){throw new IOException("FINAL_API_SESSION_UNSAFE");}
        return w;
    }
}
