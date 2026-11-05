package com.agent;

import java.util.Comparator;
import java.util.List;

/** Pure selector state. Permission/history changes remain in InteractiveCli's M1 switch path. */
final class SessionPicker {
    final List<CliConversation> items;
    private int index;
    SessionPicker(List<CliConversation> sessions, String current) {
        items = sessions.stream().sorted(Comparator.comparing(CliConversation::updatedAt).reversed()
                .thenComparing(CliConversation::id)).toList();
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(current)) index = i;
    }
    void up() { index = Math.max(0, index - 1); }
    void down() { index = Math.min(Math.max(0, items.size() - 1), index + 1); }
    String selected() { return items.isEmpty() ? null : items.get(index).id(); }
    String display(String current) {
        return display(current, 8);
    }
    String display(String current, int pageSize) {
        if (items.isEmpty()) return "No managed sessions. Enter a task or use /new.";
        StringBuilder out = new StringBuilder("Sessions: Up/Down, Enter to switch, Esc to cancel\n");
        int start = index / pageSize * pageSize;
        for (int i = start; i < Math.min(start + pageSize, items.size()); i++) {
            var item = items.get(i);
            out.append(i == index ? "> " : "  ").append(item.id().equals(current) ? "* " : "  ")
                    .append(SessionUiText.safe(item.title(), 40)).append(" | ").append(SessionUiText.localTime(item.updatedAt()))
                    .append(" | ").append(item.id(), 0, 8).append('\n');
        }
        return out.append(index + 1).append('/').append(items.size()).toString();
    }
}
