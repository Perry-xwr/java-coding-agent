package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class RangeSumHiddenTest { @Test void includesBothBoundsAndReverses() { RangeSum r=new RangeSum(); assertEquals(6,r.sum(1,3)); assertEquals(6,r.sum(3,1)); assertEquals(4,r.sum(4,4)); } }
