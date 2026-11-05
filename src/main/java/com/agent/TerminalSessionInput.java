package com.agent;

import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.AttributedString;

import java.io.*;
import java.util.List;
import java.util.function.*;

/** A single stdin owner. No raw ANSI escapes, model input or approval input synthesis. */
final class TerminalSessionInput implements AutoCloseable {
    final PickerLineReader line;
    private final Terminal terminal;
    private boolean topLevel;
    private final Supplier<String> prompt;

    static TerminalSessionInput open(Supplier<String> prompt, Supplier<List<CliConversation>> list,
                                     Supplier<String> current, Consumer<String> select) {
        if (System.console() == null || Boolean.getBoolean("agent.terminal.disabled")) return null;
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).provider("jni").jni(true).jna(false).jansi(false).dumb(false).build();
            for (var cap : List.of(Capability.key_left, Capability.key_up, Capability.key_down,
                    Capability.cursor_up, Capability.clr_eol))
                if (KeyMap.key(terminal, cap) == null) throw new IOException("Missing terminal keys");
            if (terminal.getWidth() < 20 || terminal.getHeight() < 6) throw new IOException("Terminal too small");
            return new TerminalSessionInput(terminal, prompt, list, current, select);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            if (terminal != null) try { terminal.close(); } catch (IOException ignored) { }
            return null;
        }
    }

    TerminalSessionInput(Terminal terminal, Supplier<String> prompt, Supplier<List<CliConversation>> list,
                         Supplier<String> current, Consumer<String> select) {
        this.terminal = terminal;
        this.prompt = prompt;
        line = new PickerLineReader(terminal);
        line.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        // User text (including credentials) must not be persisted as a JLine history file.
        line.unsetOpt(LineReader.Option.HISTORY_BEEP);
        line.getWidgets().put("session-picker", () -> {
            if (!topLevel || line.getBuffer().length() != 0) {
                line.callWidget(LineReader.BACKWARD_CHAR);
                return true;
            }
            SessionPicker picker = new SessionPicker(list.get(), current.get());
            KeyMap<Binding> keys = new KeyMap<>();
            keys.setNomatch(new Reference("ignore"));
            keys.setUnicode(new Reference("ignore"));
            keys.bind(new Reference("up"), KeyMap.key(terminal, Capability.key_up));
            keys.bind(new Reference("down"), KeyMap.key(terminal, Capability.key_down));
            for (var cap : List.of(Capability.key_left, Capability.key_right)) {
                String key = KeyMap.key(terminal, cap);
                if (key != null) keys.bind(new Reference("ignore"), key);
            }
            keys.bind(new Reference("enter"), "\r", "\n");
            keys.bind(new Reference("cancel"), KeyMap.esc(), KeyMap.ctrl('C'), KeyMap.ctrl('D'));
            line.showMenu(() -> picker.display(current.get(), Math.max(1, Math.min(8, terminal.getHeight() - 4))));
            try { while (true) {
                Binding binding = line.readBinding(keys);
                if (!(binding instanceof Reference ref) || ref.name().equals("cancel")) break;
                if (ref.name().equals("enter")) {
                    line.hideMenu();
                    if (picker.selected() != null) select.accept(picker.selected());
                    line.setPrompt(prompt.get());
                    break;
                }
                if (ref.name().equals("up")) picker.up();
                else if (ref.name().equals("down")) picker.down();
                else continue;
                line.redisplay();
            } } finally { line.hideMenu(); }
            return true;
        });
        String left = KeyMap.key(terminal, Capability.key_left);
        if (left != null) for (String map : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS))
            line.getKeyMaps().get(map).bind(new Reference("session-picker"), left);
    }

    void topLevel(boolean value) { topLevel = value; }
    void printAbove(String message) { line.printAbove(message); }
    BufferedReader reader() {
        return new BufferedReader(new StringReader("")) {
            @Override public String readLine() {
                while (true) try { return line.readLine(topLevel ? prompt.get() : "... > "); }
                catch (UserInterruptException cancelled) { /* Cancel input, not the task/approval policy. */ }
                catch (EndOfFileException eof) { return null; }
            }
            @Override public boolean ready() { return false; }
        };
    }
    @Override public void close() throws IOException { terminal.close(); }

    /** Reuses the reader's own Display and post area; no second screen/input owner. */
    static final class PickerLineReader extends LineReaderImpl {
        PickerLineReader(Terminal terminal) { super(terminal, "agent-cli", new java.util.HashMap<>()); }
        void showMenu(Supplier<String> menu) {
            post = () -> {
                int width = Math.max(1, terminal.getWidth() - 1);
                return new AttributedString(java.util.Arrays.stream(menu.get().split("\n"))
                        .map(row -> new AttributedString(row).columnSubSequence(0, width).toString())
                        .collect(java.util.stream.Collectors.joining("\n")));
            };
            redisplay();
        }
        void hideMenu() { post = null; redisplay(); }
        boolean menuActive() { return post != null; }
    }
}
