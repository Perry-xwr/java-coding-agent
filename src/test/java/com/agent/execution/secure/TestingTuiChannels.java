package com.agent.execution.secure;
import java.time.Duration;
/** Test-source native-input simulator; not shipped or selectable by CLI configuration. */
public final class TestingTuiChannels {
    private TestingTuiChannels() { }
    public static TuiMavenApprovalChannel create(){return new TuiMavenApprovalChannel(Duration.ofSeconds(3),System::nanoTime);}
}
