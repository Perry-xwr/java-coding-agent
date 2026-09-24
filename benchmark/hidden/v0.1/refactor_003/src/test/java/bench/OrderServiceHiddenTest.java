package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class OrderServiceHiddenTest { @Test void behaviorIsPreserved() { OrderService s=new OrderService(); assertEquals("reserved:book",s.reserve("book")); assertEquals("shipped:book",s.ship("book")); assertThrows(IllegalArgumentException.class,()->s.reserve(" ")); } }
