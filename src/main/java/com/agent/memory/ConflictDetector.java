package com.agent.memory;
import java.util.List;
public interface ConflictDetector {
    enum Relation { NEW,DUPLICATE,POSSIBLE_CONFLICT,RELATED,UNCERTAIN }
    record Suggestion(Relation relation,String targetMemoryId,String proposedAction,String proposedContent,String evidence,String explanation){}
    Suggestion detect(String content,List<LongTermMemory> authorized) throws java.io.IOException;
}
