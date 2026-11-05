package com.agent;

import com.agent.llm.Message;
import java.util.List;
import java.util.Map;

/** Memory owned by one conversation; never a cross-conversation retrieval source. */
public interface SessionMemory {
    Map<CliMode, List<Message>> histories();
    CliWorkingContext.Snapshot workingContext();
    com.agent.memory.SessionSummary summary();
}
