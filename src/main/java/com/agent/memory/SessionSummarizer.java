package com.agent.memory;

import com.agent.CliMode;
import com.agent.llm.Message;
import java.util.List;
import java.util.Map;

/** Extension boundary; the M2 implementation is deterministic and makes zero model requests. */
public interface SessionSummarizer {
    SessionSummary summarize(String sessionId, Map<CliMode, List<Message>> histories, SessionSummary previous);
}
