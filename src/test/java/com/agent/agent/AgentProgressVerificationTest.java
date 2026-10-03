package com.agent.agent;

import com.agent.environment.verification.VerificationResult;
import com.agent.environment.verification.VerificationStatus;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentProgressVerificationTest {
    @Test
    void newMutationMakesPreviousVerificationEvidenceStale() {
        AgentProgress progress = new AgentProgress();
        ToolResult changed = ToolResult.success("changed", Map.of("changed", true));
        progress.observe("apply_patch", Map.of("path", "src/App.py"), changed, 1);
        long firstSequence = progress.currentMutationSequence("src/App.py");
        progress.observeVerification(new VerificationResult(VerificationStatus.PASS, Path.of("src/App.py"),
                "python-py-compile", "", "", firstSequence));

        assertEquals(VerificationStatus.PASS, progress.currentVerificationResults().get(0).status());

        progress.observe("insert_after", Map.of("path", "src/App.py"), changed, 2);
        assertEquals(firstSequence + 1, progress.currentMutationSequence("src/App.py"));
        assertFalse(progress.hasCurrentVerificationFailure());
        assertEquals(0, progress.currentVerificationResults().size());
        // A late result tied to mutation A is ignored after mutation B.
        progress.observeVerification(new VerificationResult(VerificationStatus.FAIL, Path.of("src/App.py"),
                "python-py-compile", "stale", "", firstSequence));
        assertEquals(0, progress.currentVerificationResults().size());
    }
}
