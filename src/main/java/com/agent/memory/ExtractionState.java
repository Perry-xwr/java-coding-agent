package com.agent.memory;

import com.agent.CliMode;
import com.agent.llm.ProviderTokenUsage;
import java.util.*;

/** Session-owned review ledger. Neither candidates nor extractor responses are model context. */
public record ExtractionState(boolean autoEnabled, boolean llmEnabled, int attempts,
                              Map<CliMode,Integer> cursors, List<Candidate> candidates,
                              List<ProviderTokenUsage> usage) {
    public ExtractionState {
        cursors = cursors == null ? Map.of() : Map.copyOf(cursors);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        usage = usage == null ? List.of() : List.copyOf(usage);
        if (attempts < 0 || attempts > 100 || candidates.size() > 100 || usage.size() > 100
                || cursors.values().stream().anyMatch(i -> i < 0)) throw new IllegalArgumentException("Invalid extraction ledger");
    }
    public static ExtractionState empty() { return new ExtractionState(false,false,0,Map.of(),List.of(),List.of()); }
    public ExtractionState settings(boolean auto,boolean llm) { return new ExtractionState(auto,llm,attempts,cursors,candidates,usage); }
    public ExtractionState reserve(CliMode profile,int end,boolean paid) {
        var next=new EnumMap<CliMode,Integer>(CliMode.class); next.putAll(cursors); next.put(profile,end);
        return new ExtractionState(autoEnabled,llmEnabled,attempts+(paid?1:0),next,candidates,usage);
    }
    public record Candidate(String candidateId,String candidateContent,String category,String sourceSessionId,
                            List<String> sourceMessageRefs,String evidenceQuote,String sourceText,String createdAt,String reviewStatus) {
        public Candidate {
            UUID.fromString(candidateId); UUID.fromString(sourceSessionId); java.time.Instant.parse(createdAt);
            sourceMessageRefs=List.copyOf(sourceMessageRefs);
            if (!List.of("PENDING","APPROVED","REJECTED").contains(reviewStatus)) throw new IllegalArgumentException("Invalid review state");
            MemoryExtractor.validate(new MemoryExtractor.Proposal(candidateContent,category,sourceMessageRefs,evidenceQuote,1,"PROPOSE"),
                    List.of(new MemoryExtractor.Source(sourceMessageRefs.get(0),sourceText)));
        }
        public Candidate reviewed(String state) { return new Candidate(candidateId,candidateContent,category,sourceSessionId,sourceMessageRefs,evidenceQuote,sourceText,createdAt,state); }
    }
}
