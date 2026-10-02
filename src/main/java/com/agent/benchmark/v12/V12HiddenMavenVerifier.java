package com.agent.benchmark.v12;

import com.agent.tool.RunMavenTestTool;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.DefaultProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Injects evaluator-only Maven assets after the Agent run and always removes them. */
public final class V12HiddenMavenVerifier {
    public record Result(boolean available, boolean passed, String diagnosticSummary) {}

    private final Path hiddenRoot;
    private final Path localRepository;

    public V12HiddenMavenVerifier(Path hiddenRoot, Path localRepository) {
        this.hiddenRoot=hiddenRoot.toAbsolutePath().normalize();
        this.localRepository=localRepository.toAbsolutePath().normalize();
    }

    public Result verify(String taskId,Path workspace) throws IOException {
        Path source=hiddenRoot.resolve(taskId).normalize();
        if(!source.startsWith(hiddenRoot)||!Files.isDirectory(source))
            return new Result(false,false,"Hidden Maven evaluator assets are not defined for "+taskId);
        List<Path> injected=new ArrayList<>();
        Map<Path,byte[]> replaced=new LinkedHashMap<>();
        try(Stream<Path> paths=Files.walk(source)) {
            for(Path path:paths.filter(Files::isRegularFile).toList()) {
                Path target=workspace.resolve(source.relativize(path)).normalize();
                if(!target.startsWith(workspace.toAbsolutePath().normalize())) throw new IOException("Hidden evaluator escaped workspace");
                Files.createDirectories(target.getParent());
                if(Files.isRegularFile(target)) replaced.put(target,Files.readAllBytes(target));
                Files.copy(path,target,StandardCopyOption.REPLACE_EXISTING); injected.add(target);
            }
        }
        try {
            ToolResult result=new RunMavenTestTool(workspace,new DefaultProcessRunner(),localRepository).execute("{}");
            String diagnostic=result.success()?"BUILD SUCCESS":result.errorCode()+": "+safe(result.errorMessage());
            return new Result(true,result.success(),diagnostic);
        } finally {
            for(Path path:injected.stream().sorted(Comparator.reverseOrder()).toList()) {
                if(replaced.containsKey(path)) Files.write(path,replaced.get(path)); else Files.deleteIfExists(path);
            }
            removeEmptyParents(injected,workspace);
        }
    }

    private static void removeEmptyParents(List<Path> files,Path workspace) throws IOException {
        Path root=workspace.toAbsolutePath().normalize();
        for(Path file:files) for(Path parent=file.getParent();parent!=null&&parent.startsWith(root)&&!parent.equals(root);parent=parent.getParent()) {
            try(Stream<Path> children=Files.list(parent)){if(children.findAny().isPresent()) break;} Files.deleteIfExists(parent);
        }
    }
    private static String safe(String value){return value==null?"unknown":value.replaceAll("(?i)(Bearer\\s+)[^\\s]+","$1[REDACTED]");}
}
