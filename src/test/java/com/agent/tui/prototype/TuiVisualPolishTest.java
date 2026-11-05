package com.agent.tui.prototype;

import com.agent.tui.runtime.FakeAgentAdapter;
import com.agent.tui.runtime.UiEventLoop;
import com.agent.tui.runtime.UiSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TuiVisualPolishTest {
    private UiEventLoop loop(List<UiSnapshot.Chat> history) {
        return new UiEventLoop(List.of(new UiEventLoop.SessionSeed("A", "视觉验收", true, history)),
                new FakeAgentAdapter(r -> FakeAgentAdapter.Scenario.NORMAL, 0, 100), 8);
    }

    private String rendered(UiEventLoop ui) {
        ui.resize(120, 30);
        return String.join("\n", TuiSessionsMain.frame(ui.snapshot(), new ConversationViewport(), true)
                .lines().stream().map(Object::toString).toList());
    }

    @Test void semanticMemoryStatusIsReadableInsteadOfOneMachineLine() {
        String raw = "LAST_TURN_STATUS=SKIPPED AUTO_MEMORY_STATUS=COMPLETED MEMORY_ENABLED=true "
                + "AUTO_MEMORY_ENABLED=true SEMANTIC_ENABLED=true RULE_CANDIDATES=1 "
                + "SEMANTIC_STATUS=SKIPPED SEMANTIC_SKIP_REASON=BUDGET_COOLDOWN_DUPLICATE_OR_CAP "
                + "SKIP_REASON=BUDGET_COOLDOWN_DUPLICATE_OR_CAP EFFECTIVE_SAVES=0 PENDING=1 "
                + "REJECTED=1 REJECTION_REASONS=INCOMPLETE_CANDIDATE:1 FAILURE_CODE=NONE ERROR=NONE AT=2026-11-04T10:48:30Z\n"
                + "semantic reserved requests=2 (including failed/discarded/in-flight); todayUsed=2 (no daily cap); "
                + "currentSessionUsed=0/3; cooldownRemainingMs=0; complete PROVIDER_REPORTED records=2, "
                + "missing/partial Usage UNKNOWN=0; reported subtotals input=443, output=91, total=534 "
                + "(not a total for UNKNOWN records); no retries; currency UNKNOWN";
        try (var ui = loop(List.of())) {
            ui.localOutput(raw);
            String frame = rendered(ui);
            assertTrue(frame.contains("Memory diagnostics"));
            assertTrue(frame.contains("Semantic request budget"));
            assertTrue(frame.contains("BUDGET_COOLDOWN_DUPLICATE_OR_CAP"));
            assertTrue(frame.contains("Effective 0"));
            assertTrue(frame.contains("Pending 1"));
            assertTrue(TuiStatusFormatter.present(raw).contains("INCOMPLETE_CANDIDATE:1"));
            assertTrue(frame.contains("443 input"));
            assertTrue(frame.contains("Cost      UNKNOWN"));
            assertFalse(frame.contains("LAST_TURN_STATUS="));
            assertFalse(frame.contains("semantic reserved requests="));
            assertEquals(1, ui.snapshot().current().history().size(), "rendering must not alter stored messages");
            assertEquals(raw, ui.snapshot().current().history().get(0).text());
        }
    }

    @Test void dailyFrameKeepsCompactHierarchyAndFixedInputAcrossSizes() {
        try (var ui = loop(List.of(new UiSnapshot.Chat("You", "请解释这个方案。"),
                new UiSnapshot.Chat("Agent", "先确认目标，再比较方案。"),
                new UiSnapshot.Chat("System", "[ok] read_file  OK")))) {
            for (int[] size : List.of(new int[]{120, 30}, new int[]{84, 24}, new int[]{40, 12})) {
                ui.resize(size[0], size[1]);
                var frame = TuiSessionsMain.frame(ui.snapshot(), new ConversationViewport(), true);
                assertEquals(size[1], frame.lines().size());
                assertTrue(frame.lines().stream().allMatch(line -> line.columnLength() <= size[0]));
                String text = String.join("\n", frame.lines().stream().map(Object::toString).toList());
                assertTrue(text.contains("Agent"));
                assertTrue(text.contains("Memory ON"));
                assertTrue(text.contains("› You"));
                assertTrue(text.contains("Ctrl-Q"));
            }
        }
    }
}
