package com.agent.memory;

import java.util.List;

/** Receives only explicitly selected user statements, never arbitrary Agent history. */
public interface MemoryExtractor {
    record Source(String messageRef, String userText) { }
    record Proposal(String proposedContent, String category, List<String> sourceMessageRefs,
                    String evidenceQuote, double confidence, String proposedAction) { }
    List<Proposal> extract(List<Source> sources) throws java.io.IOException;

    static void validate(Proposal proposal, List<Source> sources) {
        LongTermMemory.validateContent(proposal.proposedContent());
        if (!List.of("PREFERENCE", "PROJECT_CONSTRAINT", "EXPLICIT_FACT", "DECISION").contains(proposal.category())
                || !"PROPOSE".equals(proposal.proposedAction()) || !Double.isFinite(proposal.confidence())
                || proposal.confidence() < 0 || proposal.confidence() > 1
                || proposal.sourceMessageRefs() == null || proposal.sourceMessageRefs().size() != 1
                || proposal.evidenceQuote() == null || proposal.evidenceQuote().length() > 2048) {
            throw new IllegalArgumentException("Invalid memory proposal");
        }
        Source source = sources.stream().filter(s -> s.messageRef().equals(proposal.sourceMessageRefs().get(0)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown source"));
        // Extractive only: paraphrases cannot be mechanically proven from an LLM confidence score.
        if (!source.userText().contains(proposal.evidenceQuote())
                || !proposal.evidenceQuote().contains(proposal.proposedContent()))
            throw new IllegalArgumentException("Proposal lacks exact source evidence");
    }
}
