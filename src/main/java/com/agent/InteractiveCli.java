package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class InteractiveCli {
    private final CliSessions sessions;
    private final ConsoleUi ui;
    private final Path workspace;

    private CliMode activeMode = CliMode.CHAT;

    public InteractiveCli(CliSessions sessions, ConsoleUi ui, Path workspace) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.ui = Objects.requireNonNull(ui, "ui must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
    }

    public void run(BufferedReader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        ui.printBanner(workspace, activeMode);
        while (true) {
            ui.promptUser(activeMode);
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            String command = input.toLowerCase(Locale.ROOT);
            if (command.equals("exit") || command.equals("quit") || command.equals("/exit")) {
                ui.printSystemMessage("Goodbye.");
                return;
            }
            if (command.equals("/chat")) {
                switchMode(CliMode.CHAT);
                continue;
            }
            if (command.equals("/read")) {
                switchMode(CliMode.READ);
                continue;
            }
            if (command.equals("/code")) {
                switchMode(CliMode.CODE);
                continue;
            }
            if (command.equals("/auto")) {
                ui.printSystemMessage("AUTO routing is reserved for a later release. Staying in "
                        + activeMode + " mode.");
                continue;
            }
            if (command.equals("/help")) {
                printHelp();
                continue;
            }
            if (command.equals("clear")) {
                sessions.session(activeMode).clearHistory();
                ui.printSystemMessage(activeMode + " history cleared.");
                continue;
            }
            ui.printAgentMessage(sessions.session(activeMode).run(line));
        }
    }

    private void switchMode(CliMode mode) {
        activeMode = mode;
        ui.printSystemMessage("Switched to " + mode + " mode.");
    }

    private void printHelp() {
        ui.printSystemMessage("Commands:");
        ui.printSystemMessage("/chat   General conversation");
        ui.printSystemMessage("/read   Read/search workspace");
        ui.printSystemMessage("/code   Modify and test code");
        ui.printSystemMessage("/auto   Reserved for future automatic routing");
        ui.printSystemMessage("/help   Show commands");
        ui.printSystemMessage("/exit   Exit");
    }
}
