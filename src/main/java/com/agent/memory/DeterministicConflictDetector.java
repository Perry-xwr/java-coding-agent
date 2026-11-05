package com.agent.memory;
import java.util.*;
import java.util.regex.Pattern;
/** Narrow explicit facts only. Similarity is never evidence of contradiction. */
public final class DeterministicConflictDetector implements ConflictDetector {
    private static String javaVersion(String s){var m=Pattern.compile("(?i)(?:java|jdk)\\s*(\\d+)").matcher(s);return m.find()?m.group(1):null;}
    private static String project(String s){
        var prefix=Pattern.compile("([\\p{IsHan}]{1,16})项目").matcher(s);
        if(prefix.find()&&!Set.of("这个","该","当前","本","以前","曾经","历史","过去").contains(prefix.group(1)))return prefix.group(1);
        var named=Pattern.compile("(?i)(?:项目|project)\\s+([A-Za-z0-9_-]{1,20})\\s+(?:使用|采用|uses?|java|jdk)").matcher(s);
        if(named.find())return named.group(1).toUpperCase(Locale.ROOT);
        var m=Pattern.compile("(?i)(?:项目|project)\\s*([A-Z])(?:\\s|使用|采用)").matcher(s);return m.find()?m.group(1).toUpperCase(Locale.ROOT):"";
    }
    static boolean sameProject(String a,String b){return project(a).equals(project(b));}
    public Suggestion detect(String content,List<LongTermMemory> authorized){
        LongTermMemory.validateContent(content);
        for(var m:authorized)if(m.content().equals(content))return suggest(Relation.DUPLICATE,m,content,"Exact content equality");
        for(var m:authorized){
            if(!project(content).equals(project(m.content())))continue;
            String a=javaVersion(content),b=javaVersion(m.content());
            if(a!=null&&b!=null){
                if((content+m.content()).matches("(?is).*(以前|曾经|历史|过去|previously|historically).*"))return suggest(Relation.UNCERTAIN,m,content,"Historical scope requires user interpretation");
                boolean simple=content.matches("(?i)(?:项目(?:使用|采用|统一使用)?\\s*)?(?:Java|JDK)\\s*\\d+[。.]?")
                        && m.content().matches("(?i)(?:项目(?:使用|采用|统一使用)?\\s*)?(?:Java|JDK)\\s*\\d+[。.]?");
                return suggest(a.equals(b)?(simple?Relation.DUPLICATE:Relation.RELATED):Relation.POSSIBLE_CONFLICT,m,content,"Explicit Java/JDK version comparison; replacement requires user approval");
            }
            if((a!=null&&m.content().toLowerCase(Locale.ROOT).contains("maven"))||(b!=null&&content.toLowerCase(Locale.ROOT).contains("maven")))return suggest(Relation.RELATED,m,content,"Independent toolchain facts may coexist");
        }
        return new Suggestion(authorized.isEmpty()?Relation.NEW:Relation.UNCERTAIN,null,"REVIEW",content,content,"No sufficient deterministic contradiction evidence");
    }
    private static Suggestion suggest(Relation r,LongTermMemory m,String content,String why){return new Suggestion(r,m.memoryId(),r==Relation.DUPLICATE?"SKIP":"REVIEW",content,content+" | "+m.content(),why);}
}
