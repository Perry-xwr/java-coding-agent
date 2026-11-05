package com.agent;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        var config=com.agent.llm.ModelProviderConfig.fromEnvironment();
        ConsoleUi ui = new ConsoleUi(System.out,config.displayProvider(),config.model());
        CliSessions sessions = CliAgentFactory.createProfiles(workspace, ui);

        new InteractiveCli(sessions, ui, workspace).runInteractive();
    }
}
