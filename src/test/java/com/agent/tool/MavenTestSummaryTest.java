package com.agent.tool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MavenTestSummaryTest {
    @Test void aggregateDoesNotDoubleCountPerClassOutput(){
        var result=MavenTestSummary.parse("[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in fixture.SmokeTest\n[INFO] Results:\n[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
        assertTrue(result.available());assertEquals(1,result.total());assertEquals(1,result.passed());
    }
    @Test void distinctModuleAggregatesAreStillSummed(){
        var result=MavenTestSummary.parse("Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in a.First\nTests run: 3, Failures: 1, Errors: 0, Skipped: 0 -- in a.Second\nTests run: 5, Failures: 1, Errors: 0, Skipped: 0\nTests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in b.Third\nTests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
        assertEquals(6,result.total());assertEquals(1,result.failures());assertEquals(5,result.passed());
    }
    @Test void classOnlyAndInvalidCountersRemainExplicit(){
        assertEquals(5,MavenTestSummary.parse("Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in a.First\nTests run: 3, Failures: 0, Errors: 0, Skipped: 0 - in a.Second\n").total());
        assertFalse(MavenTestSummary.parse("Tests run: 0, Failures: 1, Errors: 0, Skipped: 0").available());assertFalse(MavenTestSummary.parse(null).available());
    }
}
