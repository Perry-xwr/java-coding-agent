package com.agent.memory;

import com.agent.MemoryQualityPolicy;
import java.util.*;

/** Zero-API conservative fast path. Reads only explicit user-authored source spans. */
public final class DeterministicMemoryExtractor implements MemoryExtractor {
    public List<Proposal> extract(List<Source> sources) {
        List<Proposal> proposals = new ArrayList<>();
        for (Source source : sources) {
            String text = source.userText();
            if (!MemoryQualityPolicy.safeSource(text) || MemoryQualityPolicy.wholeMessageProhibited(text)) continue;
            // Validate each sentence independently: CR/LF in another paragraph or a
            // local no-memory clause cannot suppress a separate eligible preference.
            for (String sentence : MemoryQualityPolicy.semanticSegments(text)) {
                String safeSentence = sentence.replace('\r', ' ').replace('\n', ' ').strip();
                try { LongTermMemory.validateContent(safeSentence); }
                catch (IllegalArgumentException unsafe) { continue; }
                String exact = safeSentence.replaceFirst("[。；;!?！？]+$", "");
                if (!exact.isEmpty() && MemoryQualityPolicy.deterministicLowRisk(exact)) {
                    add(proposals, new Proposal(exact, "PREFERENCE", List.of(source.messageRef()), exact, 1, "PROPOSE"), sources);
                    if (proposals.size() == 8) return List.copyOf(proposals);
                    continue;
                }
                // Keep independent constraints, but reject dangling condition headers
                // below and leave unknown communication preferences to semantics.
                for (String clause : safeSentence.split("[，,]")) {
                    clause = clause.strip();
                    if (clause.isEmpty() || !clause.matches("(?is).*(项目使用|项目已经升级|统一使用|后续.*测试|我(?:长期)?(?:偏好|习惯)(?:是|为)?|长期(?:偏好|习惯|约定)(?:是|为)?|我始终(?:使用|采用|遵循)|始终(?:使用|采用|遵循)|project uses|always use|I prefer).*")) continue;
                    var candidate = new Proposal(clause, "PROJECT_CONSTRAINT", List.of(source.messageRef()), clause, 1, "PROPOSE");
                    // Unknown communication preferences need semantic evaluation, not a
                    // premature Pending duplicate that would hide the semantic result.
                    if (MemoryQualityPolicy.classifyCandidate(candidate, source).category().equals("PREFERENCE")) continue;
                    add(proposals, candidate, sources);
                    if (proposals.size() == 8) return List.copyOf(proposals);
                }
            }
        }
        return List.copyOf(proposals);
    }

    private static void add(List<Proposal> proposals, Proposal proposal, List<Source> sources) {
        if (!MemoryQualityPolicy.completeCandidate(proposal.proposedContent())) return;
        try { MemoryExtractor.validate(proposal, sources); }
        catch (IllegalArgumentException invalidSpan) { return; }
        if (proposals.stream().noneMatch(p -> p.proposedContent().equals(proposal.proposedContent()))) proposals.add(proposal);
    }
}
