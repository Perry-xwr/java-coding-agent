package com.agent.memory;

import com.agent.CliMode;
import com.agent.llm.Message;
import java.util.*;

/** Consent plus provenance, committed atomically with the raw conversation it protects. */
public record CrossSessionState(boolean enabled, List<ExposedTurn> exposures, String retrievalMode) {
    public CrossSessionState(boolean enabled,List<ExposedTurn> exposures){this(enabled,exposures,"keyword");}
    public CrossSessionState { retrievalMode=retrievalMode==null?"keyword":retrievalMode; if(!List.of("keyword","semantic","hybrid").contains(retrievalMode))throw new IllegalArgumentException("Invalid retrieval mode"); exposures = exposures == null ? List.of() : List.copyOf(exposures); }
    public static CrossSessionState off() { return new CrossSessionState(false, List.of()); }
    public CrossSessionState withEnabled(boolean value) { return new CrossSessionState(value, exposures, retrievalMode); }
    public record ExposedTurn(CliMode profile, int startInclusive, int endExclusive, List<String> memoryIds) {
        public ExposedTurn {
            if (profile == null || profile == CliMode.AUTO || startInclusive < 1 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("Invalid shared-memory exposure boundary");
            }
            memoryIds = List.copyOf(memoryIds);
            if (memoryIds.isEmpty()) throw new IllegalArgumentException("Exposure requires actual selected memory ids");
            memoryIds.forEach(UUID::fromString);
        }
    }
    public CrossSessionState exposed(CliMode profile, int start, int end, List<String> ids) {
        List<ExposedTurn> next = new ArrayList<>(exposures);
        if (next.stream().noneMatch(turn -> turn.profile() == profile && turn.startInclusive() == start)) {
            next.add(new ExposedTurn(profile, start, end, ids.stream().distinct().toList()));
        }
        return new CrossSessionState(enabled, next, retrievalMode);
    }
    public CrossSessionState cleared(CliMode profile) {
        return new CrossSessionState(enabled, exposures.stream().filter(turn -> turn.profile() != profile).toList(), retrievalMode);
    }
    public void validateHistories(Map<CliMode, List<Message>> histories) {
        for (ExposedTurn turn : exposures) {
            if (turn.endExclusive() > histories.getOrDefault(turn.profile(), List.of()).size()) {
                throw new IllegalArgumentException("Shared-memory exposure does not match stored history");
            }
        }
    }
    public Set<Integer> withheld(CliMode profile, int currentTurnStart) {
        Set<Integer> result = new HashSet<>();
        for (ExposedTurn turn : exposures) {
            if (turn.profile() != profile) continue;
            // Only previous turns can be withheld; an unexpected boundary fails privacy-closed.
            if (turn.endExclusive() > currentTurnStart) {
                for (int i = 1; i < currentTurnStart; i++) result.add(i);
            } else for (int i = turn.startInclusive(); i < turn.endExclusive(); i++) result.add(i);
        }
        return result;
    }
}
