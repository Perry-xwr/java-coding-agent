package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        ConsoleUi ui = new ConsoleUi(System.out);
        CliSessions sessions = CliAgentFactory.createProfiles(workspace, ui);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            new InteractiveCli(sessions, ui, workspace).run(reader);
        }
    }
}
