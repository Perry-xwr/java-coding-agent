package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TotalsTest {
    @Test void projectBaselineIsValid() { assertNotNull(new Totals()); }
}
